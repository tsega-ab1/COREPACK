import argparse
import io
import ipaddress
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from corepack import gateway  # noqa: E402

TOKEN = "phone-token"


def upstream(behavior):
    """A fake engine on a free port. behavior(handler, body) writes the reply. Returns (server, request log)."""
    log = []

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            log.append({"auth": self.headers.get("Authorization"), "model": body.get("model"), "t": time.monotonic()})
            behavior(self, body)

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, log


def ok(text):
    def b(h, body):
        if body.get("stream"):
            h.send_response(200)
            h.send_header("Content-Type", "text/event-stream")
            h.end_headers()
            h.wfile.write(("data: " + json.dumps({"choices": [{"delta": {"content": text}}]}) + "\n\ndata: [DONE]\n\n").encode())
        else:
            out = json.dumps({"choices": [{"message": {"content": text}}]}).encode()
            h.send_response(200)
            h.send_header("Content-Length", str(len(out)))
            h.end_headers()
            h.wfile.write(out)
    return b


def fail(code, retry_after=None, message="boom"):
    def b(h, body):
        out = json.dumps({"error": {"message": message}}).encode()
        h.send_response(code)
        if retry_after is not None:
            h.send_header("Retry-After", str(retry_after))
        h.send_header("Content-Length", str(len(out)))
        h.end_headers()
        h.wfile.write(out)
    return b


def url(srv):
    return f"http://127.0.0.1:{srv.server_address[1]}"


def post(port, body):
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


class FailoverTests(unittest.TestCase):
    def setUp(self):
        self.servers = []

    def tearDown(self):
        for s in self.servers:
            s.shutdown()
            s.server_close()

    def engine(self, behavior):
        srv, log = upstream(behavior)
        self.servers.append(srv)
        return srv, log

    def gw(self, main, backups=(), **over):
        cfg = dict(gateway.DEFAULTS)
        cfg.update({"provider": "main", "base_url": url(main), "api_key": "KEY-MAIN", "model": "test-model", "token": TOKEN, "rpm": 0})
        cfg["fallbacks"] = [{"provider": f"backup{i + 1}", "base_url": url(b), "api_key": f"KEY-B{i + 1}", "model": f"backup-model-{i + 1}"} for i, b in enumerate(backups)]
        cfg.update(over)
        srv = gateway.make_server(cfg, "127.0.0.1", 0)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.servers.append(srv)
        return srv

    MSG = {"messages": [{"role": "user", "content": "hi"}]}

    def test_backup_answers_when_main_is_down(self):
        main, mlog = self.engine(fail(503))
        back, blog = self.engine(ok("from backup"))
        code, out = post(self.gw(main, [back]).server_address[1], self.MSG)
        self.assertEqual(code, 200)
        self.assertIn("from backup", out)
        self.assertEqual((mlog[0]["model"], mlog[0]["auth"]), ("test-model", "Bearer KEY-MAIN"))
        self.assertEqual((blog[0]["model"], blog[0]["auth"]), ("backup-model-1", "Bearer KEY-B1"), "the backup uses its own model and key")

    def test_stream_announces_the_switch(self):
        main, _ = self.engine(fail(503))
        back, _ = self.engine(ok("streamed from backup"))
        code, raw = post(self.gw(main, [back]).server_address[1], dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertIn('"event": "fallback"', raw)
        self.assertIn('"provider": "backup1"', raw)
        self.assertIn("streamed from backup", raw)
        self.assertTrue(raw.rstrip().endswith("data: [DONE]"))

    def test_rate_limit_switches_at_once_and_main_cools_down(self):
        main, mlog = self.engine(fail(429, retry_after=30, message="quota"))
        back, blog = self.engine(ok("backup ok"))
        port = self.gw(main, [back]).server_address[1]
        t0 = time.monotonic()
        code, out = post(port, self.MSG)
        self.assertEqual(code, 200)
        self.assertLess(time.monotonic() - t0, 3, "must not sit through the 30 s Retry-After when a backup exists")
        self.assertEqual(len(mlog), 1)
        post(port, self.MSG)
        post(port, self.MSG)
        self.assertEqual(len(mlog), 1, "the rate-limited main engine is left alone while it cools down")
        self.assertEqual(len(blog), 3)

    def test_a_server_error_makes_main_sit_out_for_a_while(self):
        main, mlog = self.engine(fail(503, message="high demand"))
        back, blog = self.engine(ok("backup ok"))
        port = self.gw(main, [back]).server_address[1]
        for _ in range(3):
            code, out = post(port, self.MSG)
            self.assertEqual(code, 200)
        self.assertEqual(len(mlog), 1, "after one 503 the busy engine is not asked again on every message")
        self.assertEqual(len(blog), 3)

    def test_main_comes_back_after_the_cooldown(self):
        main, mlog = self.engine(fail(503))
        back, blog = self.engine(ok("backup ok"))
        g = self.gw(main, [back])
        port = g.server_address[1]
        post(port, self.MSG)
        g.gw.cooling[0] = 0                    # the cooldown has run out
        post(port, self.MSG)
        self.assertEqual(len(mlog), 2)

    def test_a_single_engine_is_still_tried_after_a_server_error(self):
        main, mlog = self.engine(fail(503, message="busy"))
        port = self.gw(main).server_address[1]
        for _ in range(3):
            code, out = post(port, self.MSG)
        self.assertEqual(code, 502)
        self.assertEqual(len(mlog), 3, "with no backup there is nowhere else to go, so it keeps trying")

    def test_an_engine_that_recovers_is_first_in_line_again(self):
        calls = {"n": 0}

        def flaky(h, body):
            calls["n"] += 1
            (fail(503) if calls["n"] == 1 else ok("main is back"))(h, body)

        main, mlog = self.engine(flaky)
        back, blog = self.engine(fail(503))
        port = self.gw(main, [back]).server_address[1]
        post(port, self.MSG)                   # both fail, so both cool down
        code, out = post(port, self.MSG)       # main is the last resort, and it has recovered
        self.assertIn("main is back", out)
        post(port, self.MSG)
        self.assertEqual((len(mlog), len(blog)), (3, 1), "once it answered, main is first in line again")

    def test_a_client_error_does_not_bench_the_engine(self):
        main, mlog = self.engine(fail(400, message="bad request"))
        back, blog = self.engine(ok("backup handled it"))
        port = self.gw(main, [back]).server_address[1]
        post(port, self.MSG)
        post(port, self.MSG)
        self.assertEqual(len(mlog), 2, "a 400 is about that request, not about the engine, so main is still tried first")

    def test_main_is_used_when_healthy(self):
        main, mlog = self.engine(ok("main ok"))
        back, blog = self.engine(ok("backup ok"))
        code, out = post(self.gw(main, [back]).server_address[1], self.MSG)
        self.assertIn("main ok", out)
        self.assertEqual(len(blog), 0)

    def test_second_backup_is_tried(self):
        main, _ = self.engine(fail(503))
        b1, _ = self.engine(fail(500))
        b2, b2log = self.engine(ok("second backup"))
        code, out = post(self.gw(main, [b1, b2]).server_address[1], self.MSG)
        self.assertIn("second backup", out)
        self.assertEqual(b2log[0]["model"], "backup-model-2")

    def test_a_cooling_engine_is_still_the_last_resort(self):
        main, mlog = self.engine(fail(429, retry_after=30))
        back, _ = self.engine(fail(503))
        port = self.gw(main, [back]).server_address[1]
        t0 = time.monotonic()
        post(port, self.MSG)
        n = len(mlog)
        post(port, self.MSG)
        self.assertGreater(len(mlog), n, "if every engine is failing, the cooling one is tried again rather than giving up")
        self.assertLess(time.monotonic() - t0, 5, "and it fails fast instead of waiting out the Retry-After")

    def test_everything_failing_is_a_clean_error(self):
        main, _ = self.engine(fail(503, message="main down"))
        back, _ = self.engine(fail(500))
        code, out = post(self.gw(main, [back]).server_address[1], self.MSG)
        self.assertEqual(code, 502)
        self.assertIn("main down", out)
        for secret in ("KEY-MAIN", "KEY-B1"):
            self.assertNotIn(secret, out)

    def test_stream_when_everything_fails_ends_cleanly(self):
        main, _ = self.engine(fail(503))
        back, _ = self.engine(fail(503))
        code, raw = post(self.gw(main, [back]).server_address[1], dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertIn('"error"', raw)
        self.assertTrue(raw.rstrip().endswith("data: [DONE]"))

    def test_bad_request_to_main_still_gets_a_chance_on_the_backup(self):
        main, _ = self.engine(fail(400, message="Function call is missing a thought_signature"))
        back, _ = self.engine(ok("backup handled it"))
        code, out = post(self.gw(main, [back]).server_address[1], self.MSG)
        self.assertIn("backup handled it", out)

    def test_malformed_backups_are_ignored(self):
        main, _ = self.engine(ok("x"))
        g = self.gw(main, [], fallbacks=[{"provider": "x"}, "junk", None, {"base_url": "http://a", "model": "m"}])
        self.assertEqual([p["label"] for p in g.gw.providers()], ["main", "backup"])

    def test_redaction_covers_backup_keys(self):
        main, _ = self.engine(ok("x"))
        back, _ = self.engine(ok("x"))
        g = self.gw(main, [back])
        self.assertEqual(g.gw.redact("a KEY-MAIN b KEY-B1 c"), "a *** b *** c")


class HelperTests(unittest.TestCase):
    def test_pairing_code_roundtrip(self):
        uri = gateway.pairing_uri("http://192.168.1.20:8787/v1", "a b+c/d=")
        q = urllib.parse.parse_qs(urllib.parse.urlparse(uri).query)
        self.assertTrue(uri.startswith("corepack://pair?"))
        self.assertEqual(q["url"], ["http://192.168.1.20:8787/v1"])
        self.assertEqual(q["token"], ["a b+c/d="])

    def test_lan_addresses_are_private_ipv4_only(self):
        for ip in gateway.lan_addresses():
            a = ipaddress.ip_address(ip)
            self.assertTrue(a.version == 4 and a.is_private and not a.is_loopback and not a.is_link_local, ip)

    def test_adb_parsing(self):
        out = "List of devices attached\nR58X60QAE9F\tdevice usb:2-10 product:a15nsxx\nZZZ\tunauthorized\nOFF\toffline\n\n"
        self.assertEqual(gateway.parse_adb_devices(out), ["R58X60QAE9F"])
        self.assertEqual(gateway.parse_adb_unauthorized(out), ["ZZZ"])
        self.assertEqual(gateway.parse_adb_devices("List of devices attached\nR58X60QAE9F\tdevice\n"), ["R58X60QAE9F"])
        self.assertEqual(gateway.parse_adb_devices("List of devices attached\n\n"), [])
        self.assertTrue(gateway.parse_adb_reverse("host-19 tcp:8787 tcp:8787\n", 8787))
        self.assertFalse(gateway.parse_adb_reverse("host-19 tcp:9000 tcp:9000\n", 8787))
        self.assertFalse(gateway.parse_adb_reverse("", 8787))


class DoctorTests(unittest.TestCase):
    def setUp(self):
        self.servers = []

    def tearDown(self):
        for s in self.servers:
            s.shutdown()
            s.server_close()

    def eng(self, behavior):
        srv, _ = upstream(behavior)
        self.servers.append(srv)
        return srv

    def cfg(self, main, backups=()):
        c = dict(gateway.DEFAULTS)
        c.update({"provider": "main", "base_url": url(main), "api_key": "K", "model": "m", "token": TOKEN, "rpm": 0})
        c["fallbacks"] = [{"provider": "b", "base_url": url(b), "api_key": "K2", "model": "bm"} for b in backups]
        return c

    def status(self, checks, what):
        return [st for st, w, _ in checks if w.startswith(what)]

    def test_incomplete_setup_is_a_single_failure(self):
        checks = gateway.run_doctor(dict(gateway.DEFAULTS), 8791)
        self.assertEqual(len(checks), 1)
        self.assertEqual(checks[0][0], "fail")
        self.assertFalse(gateway.doctor_verdict(checks))

    def test_healthy_main_with_backup_is_ready(self):
        checks = gateway.run_doctor(self.cfg(self.eng(ok("ready")), [self.eng(ok("ready"))]), 8791)
        self.assertEqual(self.status(checks, "Main engine"), ["ok"])
        self.assertEqual(self.status(checks, "Backup engine"), ["ok"])
        self.assertTrue(gateway.doctor_verdict(checks))

    def test_missing_backup_is_a_warning_not_a_failure(self):
        checks = gateway.run_doctor(self.cfg(self.eng(ok("ready"))), 8791)
        self.assertIn("warn", self.status(checks, "Backup engine"))
        self.assertTrue(gateway.doctor_verdict(checks))

    def test_dead_main_without_backup_is_not_ready(self):
        checks = gateway.run_doctor(self.cfg(self.eng(fail(503, message="down"))), 8791)
        self.assertEqual(self.status(checks, "Main engine"), ["fail"])
        self.assertFalse(gateway.doctor_verdict(checks))

    def test_dead_main_with_working_backup_is_still_ready(self):
        checks = gateway.run_doctor(self.cfg(self.eng(fail(503)), [self.eng(ok("ready"))]), 8791)
        self.assertEqual(self.status(checks, "Main engine"), ["warn"])
        self.assertTrue(gateway.doctor_verdict(checks))

    def test_wrong_answer_format_is_caught(self):
        def odd(h, body):
            out = b'{"hello": "world"}'
            h.send_response(200); h.send_header("Content-Length", str(len(out))); h.end_headers(); h.wfile.write(out)
        checks = gateway.run_doctor(self.cfg(self.eng(odd)), 8791)
        self.assertEqual(self.status(checks, "Main engine"), ["fail"])

    def test_keys_never_appear_in_the_report(self):
        checks = gateway.run_doctor(self.cfg(self.eng(fail(503, message="bad key K here"))), 8791)
        self.assertNotIn("K here", json.dumps(checks))


class FallbackCommandTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["COREPACK_HOME"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("COREPACK_HOME", None))
        os.environ["TEST_BACKUP_KEY"] = "sk-backup-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_BACKUP_KEY", None))
        gateway.save(dict(gateway.DEFAULTS, token="t", base_url="http://x", model="m", api_key="k"))

    def args(self, cmd, **kw):
        return argparse.Namespace(fb_cmd=cmd, provider=kw.get("provider"), base_url=kw.get("base_url"), model=kw.get("model"), api_key_env=kw.get("api_key_env"))

    def test_add_list_clear(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(gateway.cmd_fallback(self.args("add", provider="custom", base_url="http://127.0.0.1:11434/v1", model="qwen3", api_key_env="TEST_BACKUP_KEY")), 0)
        cfg = gateway.load_file()
        self.assertEqual(cfg["fallbacks"], [{"provider": "custom", "base_url": "http://127.0.0.1:11434/v1", "api_key": "sk-backup-secret", "model": "qwen3"}])
        self.assertEqual(os.stat(gateway.file_path()).st_mode & 0o777, 0o600)
        buf = io.StringIO()
        with redirect_stdout(buf):
            gateway.cmd_fallback(self.args("list"))
        self.assertIn("qwen3", buf.getvalue())
        self.assertNotIn("sk-backup-secret", buf.getvalue(), "listing must not print the key")
        with redirect_stdout(io.StringIO()):
            gateway.cmd_fallback(self.args("clear"))
        self.assertEqual(gateway.load_file()["fallbacks"], [])

    def test_local_backup_needs_no_key(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(gateway.cmd_fallback(self.args("add", provider="local", base_url="http://127.0.0.1:8080/v1", model="tiny")), 0)
        self.assertEqual(gateway.load_file()["fallbacks"][0]["api_key"], "")

    def test_missing_key_is_refused(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(gateway.cmd_fallback(self.args("add", provider="gemini", base_url="http://127.0.0.1:1/v1", model="x", api_key_env="NOT_SET_ANYWHERE")), 1)
        self.assertEqual(gateway.load_file()["fallbacks"], [])


if __name__ == "__main__":
    unittest.main()
