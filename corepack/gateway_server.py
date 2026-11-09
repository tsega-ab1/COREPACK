"""The HTTP side of the gateway: checks the phone's token and passes chat requests to the engine."""
from __future__ import annotations

import hmac
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .gateway_config import MAX_BODY
from .mcp_client import McpError
from .mcp_host import McpHost, UnknownTool
from .upstream import Gateway


class Handler(BaseHTTPRequestHandler):
    server_version = "corepack-gateway"

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

    def _err(self, code: int, msg: str, typ="gateway_error", extra=None):
        self._json(code, {"error": {"message": self.gw.redact(msg), "type": typ, "code": code}}, extra)

    def _authed(self) -> bool:
        h = self.headers.get("Authorization", "")
        tok = h[7:] if h[:7].lower() == "bearer " else ""
        return hmac.compare_digest(tok.encode(), self.gw.token.encode())

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True, "service": "corepack-gateway"})
        if self.path in ("/v1/models", "/models"):
            if not self._authed():
                return self._err(401, "Missing or wrong token", "unauthorized")
            c = self.gw.cfg
            return self._json(200, {"object": "list", "data": [
                {"id": c["model"], "object": "model", "owned_by": c.get("provider") or "upstream"}]})
        if self.path == "/v1/tools":
            if not self._authed():
                return self._err(401, "Missing or wrong token", "unauthorized")
            tools = self.mcp.tools()
            return self._json(200, {"tools": tools, "errors": self.mcp.errors})
        self._err(404, "Not found", "not_found")

    def _tool_call(self):
        t0 = time.monotonic()
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

    def do_POST(self):
        if self.path == "/v1/tools/call":
            return self._tool_call()
        t0 = time.monotonic()
        if self.path not in ("/v1/chat/completions", "/chat/completions"):
            return self._err(404, "Not found", "not_found")
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
        payload = self.gw.build_payload(body)
        if payload["stream"]:
            self._stream(payload)
        else:
            self._plain(payload)
        self.gw.log(f"POST chat  stream={payload['stream']}  {int((time.monotonic() - t0) * 1000)} ms")

    def _plain(self, payload):
        gw = self.gw
        if not gw.limiter.acquire(float(gw.cfg["max_wait"])):
            return self._err(429, "Too many requests, try again shortly", "rate_limited", {"Retry-After": "10"})
        resp, err = gw.call_upstream(payload)
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
        err = {"error": {"message": self.gw.redact(msg), "type": typ, "code": code}}
        self._sse("data: " + json.dumps(err) + "\n\n")
        self._sse("data: [DONE]\n\n")

    def _stream(self, payload):
        gw = self.gw
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            ok = gw.limiter.acquire(float(gw.cfg["max_wait"]),
                                    on_wait=lambda w: self._event("queued", wait=round(w, 1)))
            if not ok:
                return self._sse_error(429, "Too many requests, try again shortly", "rate_limited")
            self._event("upstream_start")
            resp, err = gw.call_upstream(payload, on_throttle=lambda ra: self._event("throttle", retry_after=ra),
                                         on_fallback=lambda label: self._event("fallback", provider=label))
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
    srv.mcp = McpHost(cfg)
    return srv
