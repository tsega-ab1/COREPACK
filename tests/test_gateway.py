import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from corepack import gateway  # noqa: E402

UPSTREAM_KEY = "sk-upstream-secret"
PHONE_TOKEN = "phone-token"


class FakeUpstream(BaseHTTPRequestHandler):
    seen: list = []
    fail_429_once = False
    state = {"failed": False}

    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers["Content-Length"])
        body = json.loads(self.rfile.read(n))
        FakeUpstream.seen.append({"auth": self.headers.get("Authorization"), "body": body})
        if FakeUpstream.fail_429_once and not FakeUpstream.state["failed"]:
            FakeUpstream.state["failed"] = True
            self.send_response(429)
            self.send_header("Retry-After", "0")
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":{"message":"slow down"}}')
            return
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for tok in ("Hel", "lo", "!"):
                chunk = {"choices": [{"delta": {"content": tok}}]}
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
                self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            out = json.dumps({"choices": [{"message": {"content": "Hello!"}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)


def start(server):
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return t


def call(port, path, body=None, token=PHONE_TOKEN, method=None):
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


class GatewayTests(unittest.TestCase):
    def setUp(self):
        FakeUpstream.seen = []
        FakeUpstream.fail_429_once = False
        FakeUpstream.state = {"failed": False}
        self.up = HTTPServer(("127.0.0.1", 0), FakeUpstream)
        start(self.up)
        self.servers = [self.up]

    def tearDown(self):
        for s in self.servers:
            s.shutdown()
            s.server_close()

    def gw(self, **over):
        cfg = dict(gateway.DEFAULTS)
        cfg.update({"provider": "custom", "base_url": f"http://127.0.0.1:{self.up.server_address[1]}",
                    "api_key": UPSTREAM_KEY, "model": "test-model", "token": PHONE_TOKEN, "rpm": 0})
        cfg.update(over)
        srv = gateway.make_server(cfg, "127.0.0.1", 0)
        start(srv)
        self.servers.append(srv)
        return srv.server_address[1]

    MSG = {"messages": [{"role": "user", "content": "hi"}]}

    def test_health_needs_no_token_but_models_do(self):
        port = self.gw()
        self.assertEqual(call(port, "/health", token=None)[0], 200)
        self.assertEqual(call(port, "/v1/models", token=None)[0], 401)
        self.assertEqual(call(port, "/v1/models", token="wrong")[0], 401)
        code, body = call(port, "/v1/models")
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(body)["data"][0]["id"], "test-model")

    def test_chat_requires_token(self):
        port = self.gw()
        self.assertEqual(call(port, "/v1/chat/completions", self.MSG, token=None)[0], 401)
        self.assertEqual(call(port, "/v1/chat/completions", self.MSG, token="nope")[0], 401)
        self.assertEqual(FakeUpstream.seen, [])

    def test_bad_bodies_rejected(self):
        port = self.gw()
        self.assertEqual(call(port, "/v1/chat/completions", {"messages": []})[0], 400)
        self.assertEqual(call(port, "/v1/chat/completions", {"nope": 1})[0], 400)

    def test_plain_completion_forces_model_and_whitelists_fields(self):
        port = self.gw()
        body = dict(self.MSG, model="gpt-evil", temperature=0.2, max_tokens=99999, admin=True)
        code, out = call(port, "/v1/chat/completions", body)
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(out)["choices"][0]["message"]["content"], "Hello!")
        sent = FakeUpstream.seen[0]
        self.assertEqual(sent["auth"], "Bearer " + UPSTREAM_KEY)
        self.assertEqual(sent["body"]["model"], "test-model")
        self.assertEqual(sent["body"]["max_tokens"], 1024)
        self.assertNotIn("admin", sent["body"])
        self.assertEqual(sent["body"]["temperature"], 0.2)

    def test_stream_emits_events_then_tokens_then_done(self):
        port = self.gw()
        code, raw = call(port, "/v1/chat/completions", dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertLess(raw.index(": corepack"), raw.index("data:"))
        self.assertIn('"event": "upstream_start"', raw)
        text = ""
        for line in raw.splitlines():
            if line.startswith("data: ") and "[DONE]" not in line:
                text += json.loads(line[6:])["choices"][0]["delta"]["content"]
        self.assertEqual(text, "Hello!")
        self.assertTrue(raw.rstrip().endswith("data: [DONE]"))
        self.assertNotIn(UPSTREAM_KEY, raw)

    def test_rate_limit_rejects_when_queue_wait_exhausted(self):
        port = self.gw(rpm=1, max_wait=0)
        self.assertEqual(call(port, "/v1/chat/completions", self.MSG)[0], 200)
        code, out = call(port, "/v1/chat/completions", self.MSG)
        self.assertEqual(code, 429)
        self.assertEqual(len(FakeUpstream.seen), 1)

    def test_stream_reports_queue_when_rate_limited(self):
        port = self.gw(rpm=1, max_wait=0)
        call(port, "/v1/chat/completions", self.MSG)
        code, raw = call(port, "/v1/chat/completions", dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertIn("rate_limited", raw)
        self.assertTrue(raw.rstrip().endswith("data: [DONE]"))

    def test_stream_waits_in_queue_then_succeeds(self):
        cfg_port = self.gw(rpm=1, max_wait=5)
        srv = self.servers[-1]
        srv.gw.limiter = gateway.RateLimiter(1, window=1.0)
        call(cfg_port, "/v1/chat/completions", self.MSG)
        t0 = time.monotonic()
        code, raw = call(cfg_port, "/v1/chat/completions", dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertIn('"event": "queued"', raw)
        self.assertLess(raw.index('"queued"'), raw.index('"upstream_start"'))
        self.assertIn("Hel", raw)
        self.assertGreater(time.monotonic() - t0, 0.5)

    def test_upstream_429_is_retried_and_announced(self):
        FakeUpstream.fail_429_once = True
        port = self.gw()
        code, raw = call(port, "/v1/chat/completions", dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertIn('"event": "throttle"', raw)
        self.assertIn("Hel", raw)
        self.assertEqual(len(FakeUpstream.seen), 2)

    def test_unreachable_engine_becomes_clean_error(self):
        port = self.gw(base_url="http://127.0.0.1:1")
        code, out = call(port, "/v1/chat/completions", self.MSG)
        self.assertEqual(code, 502)
        self.assertNotIn(UPSTREAM_KEY, out)

    def test_default_system_prompt_injected_only_when_missing(self):
        port = self.gw(system_prompt="Be tiny.")
        call(port, "/v1/chat/completions", self.MSG)
        self.assertEqual(FakeUpstream.seen[0]["body"]["messages"][0], {"role": "system", "content": "Be tiny."})
        with_sys = {"messages": [{"role": "system", "content": "mine"}, {"role": "user", "content": "hi"}]}
        call(port, "/v1/chat/completions", with_sys)
        self.assertEqual(FakeUpstream.seen[1]["body"]["messages"][0]["content"], "mine")


class LimiterTests(unittest.TestCase):
    def test_allows_rpm_then_refuses_without_wait(self):
        lim = gateway.RateLimiter(2)
        self.assertTrue(lim.acquire(0))
        self.assertTrue(lim.acquire(0))
        t0 = time.monotonic()
        self.assertFalse(lim.acquire(0.1))
        self.assertLess(time.monotonic() - t0, 1)

    def test_zero_means_unlimited(self):
        lim = gateway.RateLimiter(0)
        self.assertTrue(all(lim.acquire(0) for _ in range(50)))


class ConfigTests(unittest.TestCase):
    def test_save_is_private_and_roundtrips(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            os.environ["COREPACK_HOME"] = d
            try:
                cfg = dict(gateway.DEFAULTS, api_key="k", token="t")
                gateway.save(cfg)
                mode = os.stat(gateway.file_path()).st_mode & 0o777
                self.assertEqual(mode, 0o600)
                self.assertEqual(gateway.load_file()["api_key"], "k")
            finally:
                del os.environ["COREPACK_HOME"]

    def test_validate_reports_missing_pieces(self):
        self.assertTrue(gateway.validate(dict(gateway.DEFAULTS)))
        ok = dict(gateway.DEFAULTS, base_url="x", model="m", token="t", api_key="k")
        self.assertEqual(gateway.validate(ok), [])
        local = dict(ok, provider="local", api_key="")
        self.assertEqual(gateway.validate(local), [])


if __name__ == "__main__":
    unittest.main()
