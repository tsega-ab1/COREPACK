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
PASS_THROUGH_ENV = ("PATH", "HOME", "USER", "LANG", "LC_ALL", "TMPDIR", "SHELL",
                    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
                    "SSL_CERT_FILE", "NODE_EXTRA_CA_CERTS")
# JSON Schema keywords that the engines' function-calling APIs reject or do not need.
DROP_KEYS = {"$schema", "$id", "$comment", "additionalProperties", "examples", "definitions", "$defs"}


class UnknownTool(McpError):
    """The phone asked for a tool that is not on the list (or not allowed)."""


def clean_schema(node):
    """Removes the schema keywords that engines reject, keeping property names exactly as they are."""
    if isinstance(node, list):
        return [clean_schema(x) for x in node]
    if not isinstance(node, dict):
        return node
    out = {}
    for key, value in node.items():
        if key in DROP_KEYS:
            continue
        if key == "properties" and isinstance(value, dict):
            out[key] = {name: clean_schema(sub) for name, sub in value.items()}
        else:
            out[key] = clean_schema(value)
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
        self._lock = threading.RLock()
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

    def _client(self, name: str) -> StdioMcpClient:
        with self._lock:
            client = self._clients.get(name)
            if client is not None and client.alive():
                return client
            if client is not None:
                client.close()
                self._listing.pop(name, None)
            spec = self.servers().get(name)
            if spec is None:
                raise McpError(f"No MCP server called {name!r}")
            exe = shutil.which(str(spec["command"]), path=os.environ.get("PATH"))
            if exe is None:
                raise McpError(f"{spec['command']!r} was not found on this PC's PATH")
            client = StdioMcpClient([exe] + [str(a) for a in spec.get("args") or []], env=self.child_env(spec),
                                    timeout=self._timeout(spec), name=name)
            client.start(startup_timeout=float(spec.get("startup_timeout") or 60))
            self._clients[name] = client
            return client

    def _offered(self, name: str) -> list[dict]:
        """What the server offers, from a short-lived cache."""
        with self._lock:
            client = self._client(name)
            cached = self._listing.get(name)
            if cached and time.monotonic() - cached[0] < LISTING_TTL and not client.stale:
                return cached[1]
            tools = client.list_tools()
            self._listing[name] = (time.monotonic(), tools)
            return tools

    def probe(self, name: str) -> list[dict]:
        """Everything a server offers, allowed or not, so the person can decide what to allow."""
        with self._lock:
            self._listing.pop(name, None)
            spec = self.servers()[name]
            return [{"tool": t["name"], "description": " ".join(str(t.get("description") or "").split())[:200],
                     "read_only": bool((t.get("annotations") or {}).get("readOnlyHint")),
                     "allowed": self.allowed(spec, t["name"])} for t in self._offered(name)]

    # ---------------------------------------------------------------- what the phone uses

    def tools(self) -> list[dict]:
        """The allowed tools of every server that is working, in the shape the phone needs."""
        out: list[dict] = []
        routes: dict[str, tuple[str, str]] = {}
        errors: dict[str, str] = {}
        with self._lock:
            for name, spec in self.servers().items():
                try:
                    offered = self._offered(name)
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
                    schema = clean_schema(tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {})
                    schema.setdefault("type", "object")
                    schema.setdefault("properties", {})
                    out.append({
                        "name": pub,
                        "server": name,
                        "description": " ".join(str(tool.get("description") or tool["name"]).split())[:MAX_DESCRIPTION],
                        "parameters": schema,
                        "needs_confirm": mode == "always" or (mode == "auto" and not read_only),
                    })
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
            clients, self._clients = list(self._clients.values()), {}
            self._listing.clear()
            self._routes = {}
        for c in clients:
            c.close()
