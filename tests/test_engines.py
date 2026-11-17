import io
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

from corepack import cli, gateway  # noqa: E402
from test_gateway_reliability import TOKEN, fail, ok, post, upstream, url  # noqa: E402


def get(port, path, token=TOKEN):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"Authorization": "Bearer " + token} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


class NamedEngineTests(unittest.TestCase):
    MSG = {"messages": [{"role": "user", "content": "hi"}]}

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

    def gw(self, main, named=None, backups=(), **over):
        cfg = dict(gateway.DEFAULTS)
        cfg.update({"provider": "main", "base_url": url(main), "api_key": "KEY-MAIN", "model": "main-model", "token": TOKEN, "rpm": 0})
        cfg["fallbacks"] = [{"provider": f"backup{i + 1}", "base_url": url(b), "api_key": f"KEY-B{i + 1}", "model": f"backup-model-{i + 1}"} for i, b in enumerate(backups)]
        cfg["engines"] = {name: {"provider": "custom", "base_url": url(srv), "api_key": "KEY-" + name.upper(), "model": name + "-model",
                                 "description": f"The {name} engine"} for name, srv in (named or {}).items()}
        cfg.update(over)
        srv = gateway.make_server(cfg, "127.0.0.1", 0)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.servers.append(srv)
        return srv

    def test_a_request_can_ask_for_an_engine_by_name(self):
        main, mlog = self.engine(ok("from main"))
        fast, flog = self.engine(ok("from fast"))
        port = self.gw(main, {"fast": fast}).server_address[1]
        code, out = post(port, dict(self.MSG, model="fast"))
        self.assertEqual(code, 200)
        self.assertIn("from fast", out)
        self.assertEqual((flog[0]["model"], flog[0]["auth"]), ("fast-model", "Bearer KEY-FAST"), "it uses its own model and key")
        self.assertEqual(mlog, [], "the main engine was not asked")

    def test_streaming_works_too(self):
        main, _ = self.engine(ok("from main"))
        fast, _ = self.engine(ok("streamed from fast"))
        code, raw = post(self.gw(main, {"fast": fast}).server_address[1], dict(self.MSG, model="fast", stream=True))
        self.assertEqual(code, 200)
        self.assertIn("streamed from fast", raw)
        self.assertNotIn('"event": "fallback"', raw, "a named engine that works is the first choice, not a fallback")

    def test_without_a_name_or_with_an_unknown_one_the_main_engine_answers(self):
        main, mlog = self.engine(ok("from main"))
        fast, flog = self.engine(ok("from fast"))
        port = self.gw(main, {"fast": fast}).server_address[1]
        self.assertIn("from main", post(port, self.MSG)[1])
        self.assertIn("from main", post(port, dict(self.MSG, model="gpt-whatever"))[1])
        self.assertEqual(flog, [])
        self.assertEqual(mlog[1]["model"], "main-model", "the unknown name is never passed on to the engine")

    def test_a_failing_named_engine_falls_back_to_the_main_chain(self):
        main, mlog = self.engine(ok("from main"))
        fast, flog = self.engine(fail(503))
        port = self.gw(main, {"fast": fast}).server_address[1]
        code, out = post(port, dict(self.MSG, model="fast"))
        self.assertEqual(code, 200)
        self.assertIn("from main", out)
        code, raw = post(port, dict(self.MSG, model="fast", stream=True))
        self.assertIn('"event": "fallback"', raw)
        self.assertIn('"provider": "main"', raw)

    def test_a_named_engine_that_failed_sits_out_for_a_while(self):
        main, mlog = self.engine(ok("from main"))
        fast, flog = self.engine(fail(503))
        port = self.gw(main, {"fast": fast}).server_address[1]
        for _ in range(3):
            post(port, dict(self.MSG, model="fast"))
        self.assertEqual(len(flog), 1, "after one failure it is not asked again on every request")
        self.assertEqual(len(mlog), 3)

    def test_a_named_engines_cooldown_does_not_bench_the_main_engine(self):
        main, mlog = self.engine(ok("from main"))
        fast, flog = self.engine(fail(503))
        port = self.gw(main, {"fast": fast}).server_address[1]
        post(port, dict(self.MSG, model="fast"))
        mlog.clear()
        post(port, self.MSG)
        self.assertEqual(len(mlog), 1)

    def test_the_phone_can_list_engines_but_learns_no_secrets(self):
        main, _ = self.engine(ok("x"))
        fast, _ = self.engine(ok("x"))
        deep, _ = self.engine(ok("x"))
        port = self.gw(main, {"fast": fast, "deep": deep}).server_address[1]
        code, text = get(port, "/v1/engines")
        self.assertEqual(code, 200)
        listed = {e["name"]: e["description"] for e in json.loads(text)["engines"]}
        self.assertEqual(listed, {"fast": "The fast engine", "deep": "The deep engine"})
        for secret in ("KEY-", "127.0.0.1", "fast-model"):
            self.assertNotIn(secret, text)
        self.assertEqual(get(port, "/v1/engines", token="wrong")[0], 401)
        self.assertEqual(get(port, "/v1/engines", token=None)[0], 401)

    def test_no_engines_is_an_empty_list(self):
        main, _ = self.engine(ok("x"))
        self.assertEqual(json.loads(get(self.gw(main).server_address[1], "/v1/engines")[1]), {"engines": []})

    def test_a_named_engines_key_is_hidden_in_errors(self):
        main, _ = self.engine(fail(500, message="main broke"))
        fast, _ = self.engine(fail(500, message="the key KEY-FAST was rejected"))
        port = self.gw(main, {"fast": fast}).server_address[1]
        code, out = post(port, dict(self.MSG, model="fast"))
        self.assertEqual(code, 502)
        self.assertNotIn("KEY-FAST", out)
        self.assertIn("***", out)

    def test_providers_keep_their_keys_when_a_named_engine_is_added(self):
        main, _ = self.engine(ok("x"))
        back, _ = self.engine(ok("x"))
        fast, _ = self.engine(ok("x"))
        g = self.gw(main, {"fast": fast}, [back]).gw
        self.assertEqual([(p["label"], p["key"]) for p in g.providers()], [("main", 0), ("backup1", 1)])
        self.assertEqual([(p["label"], p["key"]) for p in g.providers("fast")], [("fast", "engine:fast"), ("main", 0), ("backup1", 1)])
        self.assertEqual([p["label"] for p in g.providers("nope")], ["main", "backup1"])

    def test_doctor_checks_named_engines(self):
        main, _ = self.engine(ok("ready"))
        fast, _ = self.engine(ok("ready"))
        broken, _ = self.engine(fail(500))
        g = self.gw(main, {"fast": fast, "broken": broken})
        checks = {what.split(" (")[0]: state for state, what, _ in gateway.run_doctor(g.gw.cfg, 8787)}
        self.assertEqual(checks["Engine fast"], "ok")
        self.assertEqual(checks["Engine broken"], "warn", "a named engine that fails is a warning, not a blocker")


class EngineCommandTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["COREPACK_HOME"] = self.tmp
        os.environ["TEST_ENGINE_KEY"] = "secret-from-env"
        self.addCleanup(lambda: [os.environ.pop(k, None) for k in ("COREPACK_HOME", "TEST_ENGINE_KEY")])
        gateway.save(dict(gateway.DEFAULTS, token="t", base_url="http://x", model="m", api_key="k"))

    def run_cmd(self, *argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.main(["gateway", "engine", *argv])
        return code, buf.getvalue()

    def test_add_list_replace_remove(self):
        code, out = self.run_cmd("add", "fast", "--provider", "custom", "--base-url", "http://127.0.0.1:1", "--model", "m-fast",
                                 "--api-key-env", "TEST_ENGINE_KEY", "--description", "quick answers")
        self.assertEqual(code, 0, out)
        e = gateway.load_file()["engines"]["fast"]
        self.assertEqual((e["provider"], e["model"], e["api_key"], e["description"]), ("custom", "m-fast", "secret-from-env", "quick answers"))
        self.assertEqual(os.stat(gateway.file_path()).st_mode & 0o777, 0o600)
        code, out = self.run_cmd("list")
        self.assertIn("fast", out)
        self.assertIn("quick answers", out)
        self.assertNotIn("secret-from-env", out, "the key is never printed")
        self.run_cmd("add", "fast", "--provider", "custom", "--base-url", "http://127.0.0.1:1", "--model", "m-fast-2", "--api-key-env", "TEST_ENGINE_KEY")
        self.assertEqual(gateway.load_file()["engines"]["fast"]["model"], "m-fast-2")
        self.assertEqual(self.run_cmd("remove", "fast")[0], 0)
        self.assertEqual(gateway.load_file()["engines"], {})

    def test_bad_input_is_refused(self):
        self.assertEqual(self.run_cmd("add", "bad name!", "--provider", "custom", "--base-url", "http://x", "--model", "m")[0], 1)
        self.assertEqual(self.run_cmd("remove", "missing")[0], 1)
        self.assertEqual(self.run_cmd("add", "fast", "--provider", "custom", "--base-url", "http://x", "--model", "m",
                                      "--api-key-env", "NOT_SET_ANYWHERE")[0], 1, "an engine that needs a key is refused without one")
        self.assertEqual(gateway.load_file()["engines"], {})

    def test_enter_at_the_key_prompt_reuses_the_main_key_for_the_same_address(self):
        with mock.patch("corepack.gateway_cli.getpass.getpass", return_value="") as asked:
            code, out = self.run_cmd("add", "fast", "--provider", "custom", "--base-url", "http://x", "--model", "m-fast")
        self.assertEqual(code, 0, out)
        self.assertIn("Enter reuses the main engine's key", asked.call_args[0][0])
        self.assertEqual(gateway.load_file()["engines"]["fast"]["api_key"], "k")
        self.assertNotIn("api_key", out)

    def test_the_main_key_is_never_offered_to_a_different_address(self):
        with mock.patch("corepack.gateway_cli.getpass.getpass", return_value="") as asked:
            code, out = self.run_cmd("add", "elsewhere", "--provider", "custom", "--base-url", "http://other.example", "--model", "m")
        self.assertEqual(code, 1)
        self.assertNotIn("reuses", asked.call_args[0][0], "the prompt does not suggest sending the main key to another server")
        self.assertEqual(gateway.load_file()["engines"], {})

    def test_a_typed_key_wins_over_the_main_key(self):
        with mock.patch("corepack.gateway_cli.getpass.getpass", return_value="typed-key"):
            self.assertEqual(self.run_cmd("add", "fast", "--provider", "custom", "--base-url", "http://x", "--model", "m")[0], 0)
        self.assertEqual(gateway.load_file()["engines"]["fast"]["api_key"], "typed-key")

    def test_backup_engines_can_reuse_the_main_key_too(self):
        buf = io.StringIO()
        with mock.patch("corepack.gateway_cli.getpass.getpass", return_value=""), redirect_stdout(buf):
            code = cli.main(["gateway", "fallback", "add", "--provider", "custom", "--base-url", "http://x", "--model", "b2"])
        self.assertEqual(code, 0, buf.getvalue())
        self.assertEqual(gateway.load_file()["fallbacks"][0]["api_key"], "k")

    def test_backup_engines_still_work(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.main(["gateway", "fallback", "add", "--provider", "custom", "--base-url", "http://127.0.0.1:1", "--model", "b1",
                             "--api-key-env", "TEST_ENGINE_KEY"])
        self.assertEqual(code, 0)
        self.assertEqual(gateway.load_file()["fallbacks"][0]["model"], "b1")


if __name__ == "__main__":
    unittest.main()
