"""The gateway's MCP host: starts the configured MCP servers when needed and offers their tools to the phone.

The phone never talks to an MCP server. It asks the gateway for a list of tools and asks it to call one, so
the servers (and any API keys they need) stay on this PC. Safety rules:

- A server's tools are hidden until you allow them (`allow` in the config).
- Tools that are not marked read-only are flagged `needs_confirm`, so the phone can ask before running them.
- Results are cut to a size limit, and a server only sees the environment variables you give it.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import threading
import time

from .mcp_client import McpError, StdioMcpClient

SEP = "__"
MAX_NAME = 64
MAX_DESCRIPTION = 1000
LISTING_TTL = 60.0                  # seconds a tool list is reused before asking the server again
CONCURRENT_CALLS = 4
FAILED_RETRY = 60.0                 # seconds before a server that failed to start is tried again by a listing
PASS_THROUGH_ENV = ("PATH", "HOME", "USER", "LANG", "LC_ALL", "TMPDIR", "SHELL",
                    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
                    "SSL_CERT_FILE", "NODE_EXTRA_CA_CERTS")
MAX_SCHEMA_DEPTH = 6
MAX_PARAM_DESCRIPTION = 500


class UnknownTool(McpError):
    """The phone asked for a tool that is not on the list (or not allowed)."""


def clean_schema(schema) -> dict:
    """Reduces a tool's JSON schema to what every engine's function calling accepts: type, description, enum,
    properties, required and items. Engines differ in what else they take (Gemini refuses `format: uri`,
    `exclusiveMinimum`, `$ref`, `additionalProperties` and `"type": [..., "null"]`), so the rest is dropped or
    rewritten. Property names are kept exactly as they are."""
    defs: dict = {}
    if isinstance(schema, dict):
        for key in ("$defs", "definitions"):
            if isinstance(schema.get(key), dict):
                defs.update(schema[key])
    out = _clean(schema, defs, 0)
    out["type"] = "object"                      # tool arguments are always an object
    out.setdefault("properties", {})
    return out


def _clean(node, defs: dict, depth: int) -> dict:
    if not isinstance(node, dict) or depth > MAX_SCHEMA_DEPTH:
        return {"type": "string"}
    ref = node.get("$ref")
    if isinstance(ref, str):                    # a pointer into $defs: put the definition in its place
        target = defs.get(ref.rsplit("/", 1)[-1])
        if not isinstance(target, dict):
            return {"type": "string"}
        merged = dict(target)
        merged.update({k: v for k, v in node.items() if k != "$ref"})
        return _clean(merged, defs, depth + 1)
    for key in ("anyOf", "oneOf"):              # optional values are written as "X or null": keep X
        branches = node.get(key)
        if isinstance(branches, list):
            real = [b for b in branches if isinstance(b, dict) and b.get("type") != "null"]
            if real:
                merged = dict(real[0])
                if "description" in node:
                    merged.setdefault("description", node["description"])
                return _clean(merged, defs, depth + 1)
    if "const" in node and "enum" not in node:
        node = dict(node, enum=[node["const"]])
    kind = node.get("type")
    if isinstance(kind, list):
        kind = next((k for k in kind if k != "null"), "string")
    if kind is None:
        kind = "object" if "properties" in node else "array" if "items" in node else "string"
    out: dict = {"type": kind if isinstance(kind, str) else "string"}
    description = node.get("description")
    if isinstance(description, str) and description.strip():
        out["description"] = " ".join(description.split())[:MAX_PARAM_DESCRIPTION]
    enum = node.get("enum")
    if isinstance(enum, list) and enum:
        if out["type"] == "string" and all(isinstance(e, str) for e in enum):
            out["enum"] = list(enum)
        else:                                   # engines only take string enums, so say it in words
            note = "One of: " + ", ".join(str(e) for e in enum)
            out["description"] = (out.get("description", "") + " " + note).strip()
    props = node.get("properties")
    if isinstance(props, dict):
        out["properties"] = {str(k): _clean(v, defs, depth + 1) for k, v in props.items()}
        required = [r for r in node.get("required") or [] if r in out["properties"]]
        if required:
            out["required"] = required
    if out["type"] == "array":
        out["items"] = _clean(node["items"], defs, depth + 1) if isinstance(node.get("items"), dict) else {"type": "string"}
    return out


def public_name(server: str, tool: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "_", f"{server}{SEP}{tool}")[:MAX_NAME]


def result_text(result: dict) -> str:
    """The reply of a tools/call as plain text for the model."""
    parts = []
    for item in result.get("content") or []:
        if not isinstance(item, dict):
            continue
        kind = item.get("type")
        if kind == "text":
            parts.append(str(item.get("text", "")))
        elif kind == "resource" and isinstance(item.get("resource"), dict) and "text" in item["resource"]:
            parts.append(str(item["resource"]["text"]))
        elif kind == "resource_link":
            parts.append(f"[link: {item.get('uri', '')}]")
        else:
            parts.append(f"[{kind or 'unknown'} content omitted]")
    text = "\n".join(p for p in parts if p)
    if not text and result.get("structuredContent") is not None:
        text = json.dumps(result["structuredContent"], ensure_ascii=False)
    return text or "(the tool returned nothing)"


class McpHost:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.errors: dict[str, str] = {}
        self._clients: dict[str, StdioMcpClient] = {}
        self._listing: dict[str, tuple[float, list[dict]]] = {}     # server -> (when, tools as offered)
        self._routes: dict[str, tuple[str, str]] = {}               # public name -> (server, tool)
        self._lock = threading.RLock()                              # guards the dictionaries above, never held while waiting
        self._start_locks: dict[str, threading.Lock] = {}           # one per server: only one start at a time
        self._failed: dict[str, tuple[float, str]] = {}             # server -> (when, why) its last start failed
        self._closed = False
        self._slots = threading.Semaphore(CONCURRENT_CALLS)

    # ---------------------------------------------------------------- configuration

    def servers(self) -> dict[str, dict]:
        raw = self.cfg.get("mcp_servers")
        if not isinstance(raw, dict):
            return {}
        return {str(n): s for n, s in raw.items() if isinstance(s, dict) and s.get("command")}

    def _timeout(self, spec: dict) -> float:
        try:
            return float(spec.get("timeout") or self.cfg.get("mcp_timeout") or 30)
        except (TypeError, ValueError):
            return 30.0

    def _max_result(self) -> int:
        try:
            return max(200, int(self.cfg.get("mcp_max_result") or 4000))
        except (TypeError, ValueError):
            return 4000

    @staticmethod
    def allowed(spec: dict, tool: str) -> bool:
        allow = spec.get("allow") or []
        return isinstance(allow, list) and ("*" in allow or tool in allow)

    @staticmethod
    def child_env(spec: dict) -> dict:
        env = {k: os.environ[k] for k in PASS_THROUGH_ENV if k in os.environ}
        for key, value in (spec.get("env") or {}).items():
            env[str(key)] = os.path.expandvars(str(value))
        return env

    # ---------------------------------------------------------------- servers

    def _start_lock(self, name: str) -> threading.Lock:
        with self._lock:
            return self._start_locks.setdefault(name, threading.Lock())

    def _client(self, name: str, wait: bool = True) -> StdioMcpClient:
        """The running server, starting it if needed. With wait=False it never waits for a start that is already
        under way, and does not retry a start that failed a moment ago; it raises McpError instead."""
        with self._lock:
            client = self._clients.get(name)
            if client is not None and client.alive():
                return client
            failed = self._failed.get(name)
        if not wait and failed and time.monotonic() - failed[0] < FAILED_RETRY:
            raise McpError(failed[1])
        lock = self._start_lock(name)
        if not lock.acquire(blocking=wait):
            raise McpError("The server is still starting up")
        try:
            with self._lock:
                client = self._clients.get(name)
                if client is not None and client.alive():
                    return client
                dead = self._clients.pop(name, None)
                self._listing.pop(name, None)
            if dead is not None:
                dead.close()
            spec = self.servers().get(name)
            if spec is None:
                raise McpError(f"No MCP server called {name!r}")
            try:
                exe = shutil.which(str(spec["command"]), path=os.environ.get("PATH"))
                if exe is None:
                    raise McpError(f"{spec['command']!r} was not found on this PC's PATH")
                client = StdioMcpClient([exe] + [str(a) for a in spec.get("args") or []], env=self.child_env(spec),
                                        timeout=self._timeout(spec), name=name)
                client.start(startup_timeout=float(spec.get("startup_timeout") or 60))
            except McpError as e:
                with self._lock:
                    self._failed[name] = (time.monotonic(), str(e))
                raise
            with self._lock:
                self._failed.pop(name, None)
                if self._closed:
                    stop = True
                else:
                    stop = False
                    self._clients[name] = client
            if stop:
                client.close()
                raise McpError("The gateway is shutting down")
            return client
        finally:
            lock.release()

    def _offered(self, name: str, wait: bool = True) -> list[dict]:
        """What the server offers, from a short-lived cache."""
        client = self._client(name, wait)
        with self._lock:
            cached = self._listing.get(name)
            if cached and time.monotonic() - cached[0] < LISTING_TTL and not client.stale:
                return cached[1]
        tools = client.list_tools()
        with self._lock:
            self._listing[name] = (time.monotonic(), tools)
        return tools

    def warm(self) -> list[threading.Thread]:
        """Starts every configured server in the background, so the first request does not wait for a download.
        A failure is remembered and shown by tools(); this itself never raises."""
        threads = []
        for name in self.servers():
            t = threading.Thread(target=self._warm_one, args=(name,), daemon=True)
            t.start()
            threads.append(t)
        return threads

    def _warm_one(self, name: str) -> None:
        try:
            self._offered(name, wait=True)
        except McpError:
            pass

    def probe(self, name: str) -> list[dict]:
        """Everything a server offers, allowed or not, so the person can decide what to allow."""
        with self._lock:
            self._listing.pop(name, None)
        spec = self.servers()[name]
        return [{"tool": t["name"], "description": " ".join(str(t.get("description") or "").split())[:200],
                 "read_only": bool((t.get("annotations") or {}).get("readOnlyHint")),
                 "allowed": self.allowed(spec, t["name"])} for t in self._offered(name, wait=True)]

    # ---------------------------------------------------------------- what the phone uses

    def tools(self) -> list[dict]:
        """The allowed tools of every server that is working, in the shape the phone needs. A server that is still
        starting, or that failed to start, is left out and explained in `errors`; this never waits for a start."""
        out: list[dict] = []
        routes: dict[str, tuple[str, str]] = {}
        errors: dict[str, str] = {}
        for name, spec in self.servers().items():
            try:
                offered = self._offered(name, wait=False)
            except McpError as e:
                errors[name] = str(e)
                continue
            mode = spec.get("confirm") if spec.get("confirm") in ("auto", "always", "never") else "auto"
            for tool in offered:
                if not self.allowed(spec, tool["name"]):
                    continue
                pub = public_name(name, tool["name"])
                n = 2
                while pub in routes:                     # two tools that clean up to the same name
                    pub = public_name(name, tool["name"])[:MAX_NAME - 3] + f"_{n}"
                    n += 1
                routes[pub] = (name, tool["name"])
                read_only = bool((tool.get("annotations") or {}).get("readOnlyHint"))
                schema = clean_schema(tool.get("inputSchema"))
                out.append({
                    "name": pub,
                    "server": name,
                    "description": " ".join(str(tool.get("description") or tool["name"]).split())[:MAX_DESCRIPTION],
                    "parameters": schema,
                    "needs_confirm": mode == "always" or (mode == "auto" and not read_only),
                })
        with self._lock:
            self._routes = routes
            self.errors = errors
        return out

    def call(self, name: str, arguments: dict) -> dict:
        """Runs one tool. Returns {"ok": bool, "text": str}; raises McpError if the server itself fails."""
        if not isinstance(arguments, dict):
            raise McpError("Tool arguments must be an object")
        with self._lock:
            route = self._routes.get(name)
        if route is None:
            self.tools()                                       # the phone may have an older list
            with self._lock:
                route = self._routes.get(name)
        if route is None:
            raise UnknownTool(f"No allowed tool called {name!r}")
        server, tool = route
        spec = self.servers().get(server) or {}
        if not self.allowed(spec, tool):
            raise UnknownTool(f"No allowed tool called {name!r}")
        with self._slots:
            client = self._client(server)
            result = client.call_tool(tool, arguments, timeout=self._timeout(spec))
        text = result_text(result)
        limit = self._max_result()
        if len(text) > limit:
            text = text[:limit] + f"\n[cut: {len(text) - limit} more characters]"
        return {"ok": not result.get("isError"), "text": text}

    def status(self) -> list[tuple[str, str, str]]:
        """A quick check that needs no process: (state, server, detail) with state ok / warn / fail."""
        out = []
        for name, spec in self.servers().items():
            if shutil.which(str(spec["command"])) is None:
                out.append(("fail", name, f"{spec['command']!r} was not found on PATH"))
            elif not spec.get("allow"):
                out.append(("warn", name, "no tools allowed yet (see: corepack gateway mcp tools " + name + ")"))
            else:
                out.append(("ok", name, "allows " + ("all tools" if "*" in spec["allow"] else ", ".join(spec["allow"]))))
        return out

    def close(self) -> None:
        with self._lock:
            self._closed = True
            clients, self._clients = list(self._clients.values()), {}
            self._listing.clear()
            self._routes = {}
        for c in clients:
            c.close()
