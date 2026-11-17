"""The HTTP side of the gateway: checks the phone's token and passes chat requests to the engine."""
from __future__ import annotations

import hmac
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .gateway_config import MAX_BODY
from .hosted import OWNER, byo_from_headers
from .mcp_client import McpError
from .mcp_host import McpHost, UnknownResource, UnknownTool
from .upstream import Gateway


class Handler(BaseHTTPRequestHandler):
    server_version = "corepack-gateway"
    _tenant = None          # who the request came from (set by _authed)
    _byo_key = ""           # the tester's own API key for this request, so it can be scrubbed from messages

    def log_message(self, fmt, *a):
        pass

    @property
    def gw(self) -> Gateway:
        return self.server.gw

    @property
    def mcp(self) -> McpHost:
        return self.server.mcp

    def _send(self, code: int, body: bytes, ctype="application/json", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj, extra=None):
        self._send(code, json.dumps(obj).encode(), extra=extra)

    def _scrub(self, msg: str) -> str:
        msg = self.gw.redact(msg)
        return msg.replace(self._byo_key, "***") if self._byo_key else msg

    def _err(self, code: int, msg: str, typ="gateway_error", extra=None):
        self._json(code, {"error": {"message": self._scrub(msg), "type": typ, "code": code}}, extra)

    def _authed(self) -> bool:
        h = self.headers.get("Authorization", "")
        tok = h[7:] if h[:7].lower() == "bearer " else ""
        self._tenant = None
        if hmac.compare_digest(tok.encode(), self.gw.token.encode()):
            self._tenant = OWNER
            return True
        self._tenant = self.gw.tenants.lookup(tok)
        return self._tenant is not None

    def _is_owner(self) -> bool:
        return self._tenant is not None and self._tenant.owner

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True, "service": "corepack-gateway"})
        if self.path in ("/v1/models", "/models"):
            if not self._authed():
                return self._err(401, "Missing or wrong token", "unauthorized")
            c = self.gw.cfg
            return self._json(200, {"object": "list", "data": [
                {"id": c["model"], "object": "model", "owned_by": c.get("provider") or "upstream"}]})
        if self.path == "/v1/engines":
            if not self._authed():
                return self._err(401, "Missing or wrong token", "unauthorized")
            return self._json(200, {"engines": self.gw.engine_list()})
        if self.path in ("/v1/resources", "/v1/prompts"):
            if not self._authed():
                return self._err(401, "Missing or wrong token", "unauthorized")
            if not self._is_owner():
                return self._json(200, {"resources" if self.path == "/v1/resources" else "prompts": [], "errors": {}})
            if self.path == "/v1/resources":
                return self._json(200, {"resources": self.mcp.resources(), "errors": self.mcp.resource_errors})
            return self._json(200, {"prompts": self.mcp.prompts(), "errors": self.mcp.prompt_errors})
        if self.path == "/v1/tools":
            if not self._authed():
                return self._err(401, "Missing or wrong token", "unauthorized")
            if not self._is_owner():
                return self._json(200, {"tools": [], "errors": {}})
            tools = self.mcp.tools()
            return self._json(200, {"tools": tools, "errors": self.mcp.errors})
        self._err(404, "Not found", "not_found")

    def _tool_call(self):
        t0 = time.monotonic()
        if not self._authed():
            return self._err(401, "Missing or wrong token", "unauthorized")
        if not self._is_owner():
            return self._err(404, "No such tool", "unknown_tool")
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self._err(411, "Content-Length required", "bad_request")
        if n <= 0 or n > MAX_BODY:
            return self._err(413, "Request body too large or empty", "bad_request")
        try:
            body = json.loads(self.rfile.read(n))
            name, arguments = body["name"], body.get("arguments") or {}
            if not isinstance(name, str) or not isinstance(arguments, dict):
                raise ValueError
        except (ValueError, KeyError, TypeError, AttributeError):
            return self._err(400, "Body must be JSON like {\"name\": \"...\", \"arguments\": {...}}", "bad_request")
        try:
            result = self.mcp.call(name, arguments)
        except UnknownTool as e:
            return self._err(404, str(e), "unknown_tool")
        except McpError as e:
            self.gw.log(f"MCP call {name} failed after {int((time.monotonic() - t0) * 1000)} ms: {e}")
            return self._err(502, f"The tool server failed: {e}", "tool_server_error")
        self._json(200, result)
        self.gw.log(f"MCP call {name}  {'ok' if result['ok'] else 'error'}  {int((time.monotonic() - t0) * 1000)} ms")

    def _body_json(self):
        """The request body as a dict, or None once the right error has been sent."""
        if not self._authed():
            self._err(401, "Missing or wrong token", "unauthorized")
            return None
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self._err(411, "Content-Length required", "bad_request")
            return None
        if n <= 0 or n > MAX_BODY:
            self._err(413, "Request body too large or empty", "bad_request")
            return None
        try:
            body = json.loads(self.rfile.read(n))
        except ValueError:
            body = None
        if not isinstance(body, dict):
            self._err(400, "Body must be a JSON object", "bad_request")
            return None
        return body

    def _extra_call(self, what: str, fields: tuple[str, ...], run):
        t0 = time.monotonic()
        body = self._body_json()
        if body is None:
            return
        if not self._is_owner():
            return self._err(404, "Not offered here", "unknown_" + what)
        if not all(isinstance(body.get(f), str) for f in fields):
            return self._err(400, "Body needs these text fields: " + ", ".join(fields), "bad_request")
        try:
            result = run(body)
        except UnknownResource as e:
            return self._err(404, str(e), "unknown_" + what)
        except McpError as e:
            self.gw.log(f"MCP {what} failed after {int((time.monotonic() - t0) * 1000)} ms: {e}")
            return self._err(502, f"The tool server failed: {e}", "tool_server_error")
        self._json(200, result)
        self.gw.log(f"MCP {what}  ok  {int((time.monotonic() - t0) * 1000)} ms")

    def do_POST(self):
        if self.path == "/v1/tools/call":
            return self._tool_call()
        if self.path == "/v1/resources/read":
            return self._extra_call("resource", ("server", "uri"), lambda b: self.mcp.read_resource(b["server"], b["uri"]))
        if self.path == "/v1/prompts/get":
            return self._extra_call("prompt", ("server", "name"),
                                    lambda b: self.mcp.get_prompt(b["server"], b["name"], b.get("arguments") or {}))
        t0 = time.monotonic()
        if self.path not in ("/v1/chat/completions", "/chat/completions"):
            return self._err(404, "Not found", "not_found")
        self._byo_key = (self.headers.get("X-Corepack-Key") or "").strip()
        if not self._authed():
            return self._err(401, "Missing or wrong token", "unauthorized")
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self._err(411, "Content-Length required", "bad_request")
        if n <= 0 or n > MAX_BODY:
            return self._err(413, "Request body too large or empty", "bad_request")
        try:
            body = json.loads(self.rfile.read(n))
            msgs = body["messages"]
            if not isinstance(msgs, list) or not msgs:
                raise ValueError
        except (ValueError, KeyError, TypeError):
            return self._err(400, "Body must be JSON with a non-empty messages list", "bad_request")
        gw, tenant = self.gw, self._tenant
        byo, byo_err = byo_from_headers(self.headers, gw.cfg)
        if byo_err:
            return self._err(400, byo_err, "bad_key")
        if not tenant.owner:
            if not gw.tenants.take_rate(tenant):
                return self._err(429, "You're sending messages too fast. Wait a few seconds and try again.",
                                 "rate_limited", {"Retry-After": "10"})
            if not byo:
                if not gw.cfg.get("api_key") and gw.cfg.get("provider") != "local":
                    return self._err(400, "This server has no shared key. Add your own API key in the app's settings.",
                                     "needs_key")
                if not gw.tenants.take_daily(tenant):
                    return self._err(429, f"The shared test key has reached today's limit ({tenant.daily} messages). "
                                          "Add your own API key in the app's settings to keep chatting, or try again tomorrow.",
                                     "daily_limit", {"Retry-After": "3600"})
        payload = gw.build_payload(body)
        engine = None if not tenant.owner else gw.engine_for(body)      # testers never reach the owner's named engines
        private = self.mcp.recently_private() or self.mcp.conversation_is_private(body.get("messages"))
        if private:
            gw.log("private data in play: only a local engine may answer")
        if gw.slots is not None and not gw.slots.acquire(timeout=10):
            return self._err(503, "The server is busy. Try again in a moment.", "busy", {"Retry-After": "5"})
        try:
            if payload["stream"]:
                self._stream(payload, engine, private, byo)
            else:
                self._plain(payload, engine, private, byo)
        finally:
            if gw.slots is not None:
                gw.slots.release()
        gw.log(f"POST chat  who={tenant.name}  own_key={bool(byo)}  stream={payload['stream']}  "
               f"{int((time.monotonic() - t0) * 1000)} ms")

    def _plain(self, payload, engine=None, private=False, byo=None):
        gw = self.gw
        if not byo and not gw.limiter.acquire(float(gw.cfg["max_wait"])):
            return self._err(429, "Too many requests, try again shortly", "rate_limited", {"Retry-After": "10"})
        resp, err = gw.call_upstream(payload, engine=engine, private=private, byo=byo)
        if err:
            return self._err(*err)
        with resp:
            self._send(200, resp.read())

    def _sse(self, text: str):
        self.wfile.write(text.encode())
        self.wfile.flush()

    def _event(self, name: str, **kw):
        self._sse(": corepack " + json.dumps({"event": name, **kw}) + "\n\n")

    def _sse_error(self, code: int, msg: str, typ: str):
        err = {"error": {"message": self._scrub(msg), "type": typ, "code": code}}
        self._sse("data: " + json.dumps(err) + "\n\n")
        self._sse("data: [DONE]\n\n")

    def _stream(self, payload, engine=None, private=False, byo=None):
        gw = self.gw
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            ok = byo or gw.limiter.acquire(float(gw.cfg["max_wait"]),
                                           on_wait=lambda w: self._event("queued", wait=round(w, 1)))
            if not ok:
                return self._sse_error(429, "Too many requests, try again shortly", "rate_limited")
            self._event("upstream_start")
            resp, err = gw.call_upstream(payload, on_throttle=lambda ra: self._event("throttle", retry_after=ra),
                                         on_fallback=lambda label: self._event("fallback", provider=label), engine=engine,
                                         private=private, byo=byo)
            if err:
                return self._sse_error(*err)
            saw_done = False
            with resp:
                for line in resp:
                    if line.strip() == b"data: [DONE]":
                        saw_done = True
                    self.wfile.write(line)
                    self.wfile.flush()
            if not saw_done:
                self._sse("data: [DONE]\n\n")
        except (BrokenPipeError, ConnectionResetError):
            pass


class _Server(ThreadingHTTPServer):
    mcp: McpHost | None = None

    def server_close(self):
        if self.mcp is not None:
            self.mcp.close()                    # stop the tool servers that were started
        super().server_close()


def make_server(cfg: dict, host: str | None = None, port: int | None = None, verbose: bool = False):
    srv = _Server((host or cfg["host"], cfg["port"] if port is None else port), Handler)
    srv.daemon_threads = True
    srv.gw = Gateway(cfg, verbose)
    # A public server never starts tool servers: they run commands on the host, with its files and secrets.
    srv.mcp = McpHost(dict(cfg, mcp_servers={}) if cfg.get("hosted") else cfg)
    return srv
