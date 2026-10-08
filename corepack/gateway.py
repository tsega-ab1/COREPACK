"""Phone AI gateway: an authenticated, rate-limited, OpenAI-compatible front door.

The phone app talks to this. This talks to the real engine (Gemini, Grok, a local
llama.cpp / Ollama server, or later a big model hosted by COREPACK). API keys live
here on the PC and never on the phone. Stdlib only.

Streaming replies are plain OpenAI-style server-sent events, plus SSE *comment*
lines that carry gateway events (ignored by ordinary OpenAI clients):

    : corepack {"event":"queued","wait":12.3}
    : corepack {"event":"upstream_start"}
    : corepack {"event":"throttle","retry_after":5}
"""
from __future__ import annotations

import argparse
import getpass
import hmac
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import config, ui

PRESETS = {
    "gemini": {"label": "Google Gemini", "base_url": "https://generativelanguage.googleapis.com/v1beta/openai/",
               "needs_key": True},
    "xai": {"label": "xAI Grok", "base_url": "https://api.x.ai/v1", "needs_key": True},
    "local": {"label": "Local server (llama.cpp / Ollama)", "base_url": "http://127.0.0.1:8080/v1",
              "needs_key": False},
    "custom": {"label": "Other OpenAI-compatible", "base_url": "", "needs_key": True},
}

DEFAULTS = {
    "provider": "",
    "base_url": "",
    "api_key": "",
    "model": "",
    "token": "",              # what the phone must send as "Authorization: Bearer <token>"
    "host": "127.0.0.1",      # loopback only; the phone reaches it through `adb reverse`
    "port": 8787,
    "rpm": 10,                # requests per minute sent upstream (0 = unlimited)
    "max_wait": 60,           # seconds a request may queue for a free slot
    "max_tokens": 1024,       # cap on reply length
    "timeout": 180,           # seconds to wait on the upstream engine
    "system_prompt": "",      # optional default persona if the client sends none
}

ALLOWED = {"messages", "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop",
           "response_format", "tools", "tool_choice", "seed"}
MAX_BODY = 1_000_000


# ---------------------------------------------------------------- config

def file_path():
    return config.home() / "gateway.json"


def load_file() -> dict:
    cfg = dict(DEFAULTS)
    try:
        cfg.update(json.loads(file_path().read_text()))
    except (OSError, ValueError):
        pass
    return cfg


def load() -> dict:
    cfg = load_file()
    env_key = os.environ.get("COREPACK_API_KEY")
    if env_key:
        cfg["api_key"] = env_key
    return cfg


def save(cfg: dict) -> None:
    config.home().mkdir(parents=True, exist_ok=True)
    p = file_path()
    fd = os.open(str(p), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps(cfg, indent=2))
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass


def validate(cfg: dict) -> list[str]:
    bad = []
    if not cfg.get("base_url"):
        bad.append("No upstream base URL set")
    if not cfg.get("model"):
        bad.append("No model set")
    if not cfg.get("token"):
        bad.append("No phone token set")
    if cfg.get("provider") != "local" and not cfg.get("api_key"):
        bad.append("No API key set")
    return bad


# ---------------------------------------------------------------- rate limiter

class RateLimiter:
    """Sliding window (default one minute). acquire() blocks (up to max_wait) until a slot is free."""

    def __init__(self, rpm: int, window: float = 60.0):
        self.rpm = rpm
        self.window = window
        self.lock = threading.Lock()
        self.stamps: deque[float] = deque()

    def acquire(self, max_wait: float, on_wait=None) -> bool:
        deadline = time.monotonic() + max_wait
        announced = False
        while True:
            with self.lock:
                now = time.monotonic()
                while self.stamps and now - self.stamps[0] >= self.window:
                    self.stamps.popleft()
                if self.rpm <= 0 or len(self.stamps) < self.rpm:
                    self.stamps.append(now)
                    return True
                wait = self.window - (now - self.stamps[0])
            if time.monotonic() + wait > deadline:
                return False
            if not announced and on_wait:
                on_wait(wait)
                announced = True
            time.sleep(min(wait, 1.0))


# ---------------------------------------------------------------- core

def _retry_after(headers) -> float:
    try:
        return max(0.0, min(60.0, float(headers.get("Retry-After", "5"))))
    except (TypeError, ValueError):
        return 5.0


def _short(text: str) -> str:
    try:
        data = json.loads(text)
        if isinstance(data, list) and data:
            data = data[0]
        msg = data.get("error", {}).get("message")
        if msg:
            text = msg
    except (ValueError, AttributeError):
        pass
    text = " ".join(text.split())
    return text[:300]


class Gateway:
    def __init__(self, cfg: dict, verbose: bool = False):
        self.cfg = cfg
        self.token = cfg["token"]
        self.limiter = RateLimiter(int(cfg["rpm"]))
        self.verbose = verbose

    def log(self, msg: str) -> None:
        if self.verbose:
            print(f"  {time.strftime('%H:%M:%S')}  {msg}", file=sys.stderr, flush=True)

    def redact(self, s: str) -> str:
        key = self.cfg.get("api_key")
        return s.replace(key, "***") if key else s

    def build_payload(self, body: dict) -> dict:
        payload = {k: v for k, v in body.items() if k in ALLOWED}
        payload["model"] = self.cfg["model"]
        payload["stream"] = bool(body.get("stream"))
        cap = int(self.cfg["max_tokens"])
        mt = payload.get("max_tokens")
        if cap:
            payload["max_tokens"] = min(mt, cap) if isinstance(mt, int) and mt > 0 else cap
        sp = self.cfg.get("system_prompt")
        msgs = payload["messages"]
        if sp and not any(isinstance(m, dict) and m.get("role") == "system" for m in msgs):
            payload["messages"] = [{"role": "system", "content": sp}] + msgs
        return payload

    def open_upstream(self, payload: dict):
        headers = {"Content-Type": "application/json",
                   "Accept": "text/event-stream" if payload.get("stream") else "application/json"}
        if self.cfg.get("api_key"):
            headers["Authorization"] = "Bearer " + self.cfg["api_key"]
        url = self.cfg["base_url"].rstrip("/") + "/chat/completions"
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
        return urllib.request.urlopen(req, timeout=float(self.cfg["timeout"]))

    def call_upstream(self, payload: dict, on_throttle=None):
        """Returns (response, None) or (None, (status, message, type))."""
        for attempt in (0, 1):
            try:
                return self.open_upstream(payload), None
            except urllib.error.HTTPError as e:
                text = e.read()[:4000].decode("utf-8", "replace")
                if e.code == 429 and attempt == 0:
                    ra = _retry_after(e.headers)
                    if on_throttle:
                        on_throttle(ra)
                    if ra <= float(self.cfg["max_wait"]):
                        time.sleep(ra)
                        if self.limiter.acquire(float(self.cfg["max_wait"])):
                            continue
                if e.code == 429:
                    return None, (429, "The engine is rate-limiting this key: " + _short(text), "rate_limited")
                if e.code in (401, 403):
                    return None, (502, "The engine rejected the API key", "upstream_auth")
                status = 400 if e.code == 400 else 502
                return None, (status, f"Engine error {e.code}: {_short(text)}", "upstream_error")
            except (urllib.error.URLError, OSError) as e:
                return None, (502, f"Can't reach the engine: {getattr(e, 'reason', e)}", "upstream_unreachable")
        return None, (502, "Engine unavailable", "upstream_error")


class Handler(BaseHTTPRequestHandler):
    server_version = "corepack-gateway"

    def log_message(self, fmt, *a):
        pass

    @property
    def gw(self) -> Gateway:
        return self.server.gw

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
        self._err(404, "Not found", "not_found")

    def do_POST(self):
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
            resp, err = gw.call_upstream(payload, on_throttle=lambda ra: self._event("throttle", retry_after=ra))
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


def make_server(cfg: dict, host: str | None = None, port: int | None = None, verbose: bool = False):
    srv = ThreadingHTTPServer((host or cfg["host"], cfg["port"] if port is None else port), Handler)
    srv.daemon_threads = True
    srv.gw = Gateway(cfg, verbose)
    return srv


# ---------------------------------------------------------------- CLI

def list_models(base: str, key: str) -> list[str]:
    req = urllib.request.Request(base.rstrip("/") + "/models",
                                 headers={"Authorization": "Bearer " + key} if key else {})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read())
    except (urllib.error.URLError, OSError, ValueError):
        return []
    rows = data.get("data") if isinstance(data, dict) else None
    return sorted(str(m.get("id", "")) for m in (rows or []) if isinstance(m, dict) and m.get("id"))


def _pick_models(provider: str, ids: list[str]) -> list[str]:
    if provider == "gemini":
        ids = [i.removeprefix("models/") for i in ids]
        ids = [i for i in ids if i.startswith("gemini") and not any(
            w in i for w in ("embedding", "tts", "image", "live", "aqa"))]
    return ids[:20]


def adb_reverse(port: int) -> tuple[bool, str]:
    adb = shutil.which("adb")
    if not adb:
        return False, "adb not found (Linux Mint: sudo apt install adb)"
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15).stdout
    except (OSError, subprocess.SubprocessError) as e:
        return False, f"adb failed: {e}"
    devs = [ln.split()[0] for ln in out.splitlines()[1:] if ln.strip().endswith("device")]
    if not devs:
        return False, "no phone found over USB (enable USB debugging and accept the prompt on the phone)"
    cmd = [adb, "-s", devs[0], "reverse", f"tcp:{port}", f"tcp:{port}"]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as e:
        return False, f"adb failed: {e}"
    if r.returncode != 0:
        return False, "adb reverse failed: " + (r.stderr.strip() or r.stdout.strip())
    return True, f"phone {devs[0]} can now reach this gateway at 127.0.0.1:{port}"


def cmd_init(args) -> int:
    cfg = load_file()
    ui.header("Phone AI gateway setup")
    prov = args.provider
    if not prov:
        pick = ui.menu("Which engine should answer?", [
            ("1", "Google Gemini", "free API key"), ("2", "xAI Grok", "API key or credits"),
            ("3", "Local server", "llama.cpp or Ollama on this PC"), ("4", "Other OpenAI-compatible", "any base URL")])
        prov = {"1": "gemini", "2": "xai", "3": "local", "4": "custom"}.get(pick)
        if not prov:
            ui.fail("Pick 1-4")
            return 1
    preset = PRESETS[prov]
    default_base = cfg["base_url"] if cfg.get("provider") == prov and cfg.get("base_url") else preset["base_url"]
    base = args.base_url or ui.ask("Base URL", default_base)
    if not base:
        ui.fail("A base URL is required")
        return 1
    key = ""
    if preset["needs_key"]:
        if args.api_key_env:
            key = os.environ.get(args.api_key_env, "")
        else:
            had = cfg.get("api_key") if cfg.get("provider") == prov else ""
            key = getpass.getpass("  API key (hidden" + ("; Enter keeps the saved one" if had else "") + "): ").strip()
            key = key or had
        if not key:
            ui.fail("An API key is required for this engine")
            return 1
    model = args.model
    if not model:
        ui.info("Asking the engine which models you can use...")
        ids = _pick_models(prov, list_models(base, key))
        if ids:
            for i, m in enumerate(ids, 1):
                print(f"   {i:>2}. {m}")
            ans = ui.ask("Pick a number, or type a model name", "1")
            model = ids[int(ans) - 1] if ans.isdigit() and 1 <= int(ans) <= len(ids) else ans
        else:
            ui.warn("Couldn't list models (bad key or no network?). Type one yourself.")
            model = ui.ask("Model name", cfg.get("model", ""))
    if prov == "gemini":
        model = model.removeprefix("models/")
    default_rpm = "0" if prov == "local" else str(cfg.get("rpm", 10))
    rpm = args.rpm if args.rpm is not None else int(ui.ask("Max requests per minute (0 = unlimited)", default_rpm))
    cfg.update({"provider": prov, "base_url": base, "api_key": key, "model": model, "rpm": rpm})
    if not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(24)
    save(cfg)
    ui.ok(f"Saved to {file_path()} (readable only by you)")
    ui.hint("Next:  corepack gateway test     then     corepack gateway start")
    return 0


def cmd_test(args) -> int:
    cfg = load()
    bad = validate(cfg)
    if bad:
        for b in bad:
            ui.fail(b)
        ui.hint("Run: corepack gateway init")
        return 1
    gw = Gateway(cfg)
    payload = {"model": cfg["model"], "stream": False, "max_tokens": 64,
               "messages": [{"role": "user", "content": "Reply with one short sentence saying you are ready."}]}
    t0 = time.monotonic()
    resp, err = gw.call_upstream(payload)
    if err:
        ui.fail(gw.redact(err[1]))
        return 1
    try:
        with resp:
            text = json.loads(resp.read())["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError):
        ui.fail("The engine answered, but not in the expected format")
        return 1
    ui.ok(f"{cfg['model']} replied in {time.monotonic() - t0:.1f}s:  {(text or '').strip()}")
    return 0


def cmd_token(args) -> int:
    cfg = load_file()
    if args.rotate or not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(24)
        save(cfg)
        ui.ok("New token created. Update it in the phone app.")
    print(f"  {cfg['token']}")
    return 0


def cmd_start(args) -> int:
    cfg = load()
    bad = validate(cfg)
    if bad:
        for b in bad:
            ui.fail(b)
        ui.hint("Run: corepack gateway init")
        return 1
    host = args.host or cfg["host"]
    port = args.port or int(cfg["port"])
    try:
        srv = make_server(cfg, host, port, verbose=True)
    except OSError as e:
        ui.fail(f"Can't listen on {host}:{port}: {e}")
        return 1
    ui.header("Phone AI gateway")
    ui.ok(f"Engine   {cfg['model']}  via {PRESETS.get(cfg['provider'], {}).get('label', cfg['provider'])}")
    ui.ok(f"Limit    {cfg['rpm'] or 'none'} requests/min")
    ui.ok(f"URL      http://127.0.0.1:{port}/v1")
    ui.ok(f"Token    {cfg['token']}")
    if host not in ("127.0.0.1", "localhost", "::1"):
        ui.warn(f"Listening on {host}: anyone who reaches this port with the token can spend your API quota.")
    if not args.no_adb:
        ok, msg = adb_reverse(port)
        (ui.ok if ok else ui.warn)(msg)
        if not ok:
            ui.hint(f"Later:  adb reverse tcp:{port} tcp:{port}")
    ui.hint("Phone app: set the URL and token above, then tap the pet to chat. Ctrl-C stops the gateway.")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        srv.server_close()
    return 0


def add_parsers(sub) -> None:
    g = sub.add_parser("gateway", help="AI gateway for the phone overlay app")
    gs = g.add_subparsers(dest="gw_cmd")
    i = gs.add_parser("init", help="set the engine, API key and model")
    i.add_argument("--provider", choices=list(PRESETS))
    i.add_argument("--base-url")
    i.add_argument("--model")
    i.add_argument("--api-key-env", help="read the API key from this environment variable")
    i.add_argument("--rpm", type=int)
    gs.add_parser("test", help="check that the API key and model work")
    s = gs.add_parser("start", help="run the gateway (and wire up the USB cable with adb)")
    s.add_argument("--host")
    s.add_argument("--port", type=int)
    s.add_argument("--no-adb", action="store_true", help="don't run adb reverse")
    t = gs.add_parser("token", help="show or rotate the phone token")
    t.add_argument("--rotate", action="store_true")


def dispatch(args, parser) -> int:
    fn = {"init": cmd_init, "test": cmd_test, "start": cmd_start, "token": cmd_token}.get(args.gw_cmd)
    if not fn:
        parser.parse_args(["gateway", "--help"])
        return 0
    return fn(args)
