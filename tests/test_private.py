import io
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

from corepack import cli, gateway  # noqa: E402
from corepack.providers import is_local_engine  # noqa: E402
from test_gateway_reliability import TOKEN, ok, post, upstream, url  # noqa: E402
from test_mcp import FAKE, spec  # noqa: E402


def call_tool(port, name, arguments=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/tools/call", method="POST",
                                 data=json.dumps({"name": name, "arguments": arguments or {}}).encode(),
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


class LocalEngineTests(unittest.TestCase):
    def test_what_counts_as_local(self):
        self.assertTrue(is_local_engine("local", "http://192.168.1.50:8080/v1"), "the local preset is local wherever it points")
        for host in ("http://127.0.0.1:8080/v1", "http://localhost:11434/v1", "http://[::1]:8080/v1", "http://127.0.1.1/v1"):
            self.assertTrue(is_local_engine("custom", host), host)
        for host in ("https://generativelanguage.googleapis.com/v1beta/openai/", "http://192.168.1.50:8080/v1", "https://api.x.ai/v1", ""):
            self.assertFalse(is_local_engine("gemini", host), host)

    def test_an_explicit_setting_wins(self):
        self.assertFalse(is_local_engine("local", "http://127.0.0.1:8080/v1", False))
        self.assertTrue(is_local_engine("custom", "http://192.168.1.50:8080/v1", True), "a model on another PC you trust can be marked local")
        self.assertTrue(is_local_engine("gemini", "https://example.com", True))


class PrivateRoutingTests(unittest.TestCase):
    MSG = {"messages": [{"role": "user", "content": "what was on my screen?"}]}

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

    def gw(self, cloud, local=None, named=None, window=600, mcp=None):
        cfg = dict(gateway.DEFAULTS)
        cfg.update({"provider": "gemini", "base_url": url(cloud), "api_key": "KEY-CLOUD", "model": "cloud-model", "local": False,
                    "token": TOKEN, "rpm": 0, "private_window": window})
        cfg["fallbacks"] = [{"provider": "local", "base_url": url(local), "api_key": "", "model": "local-model"}] if local else []
        cfg["engines"] = {n: {"provider": "gemini", "base_url": url(s), "api_key": "KEY-" + n.upper(), "model": n + "-model", "local": False,
                              "description": n} for n, s in (named or {}).items()}
        cfg["mcp_servers"] = mcp if mcp is not None else {"fake": spec(allow=["*"], private=True), "plain": spec(allow=["*"])}
        srv = gateway.make_server(cfg, "127.0.0.1", 0)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.servers.append(srv)
        return srv

    def test_without_private_data_the_main_engine_answers_as_usual(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        code, out = post(self.gw(cloud, local).server_address[1], self.MSG)
        self.assertEqual(code, 200)
        self.assertIn("from the cloud", out)
        self.assertEqual(llog, [])

    def test_after_a_private_tool_runs_only_a_local_engine_answers(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local).server_address[1]
        self.assertEqual(call_tool(port, "fake__echo", {"text": "secret"}), 200)
        code, out = post(port, self.MSG)
        self.assertEqual(code, 200)
        self.assertIn("from local", out)
        self.assertEqual(clog, [], "the cloud engine never saw the request")
        self.assertEqual(llog[0]["model"], "local-model")

    def test_streaming_is_private_too(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("streamed from local"))
        port = self.gw(cloud, local).server_address[1]
        call_tool(port, "fake__echo", {"text": "secret"})
        code, raw = post(port, dict(self.MSG, stream=True))
        self.assertEqual(code, 200)
        self.assertIn("streamed from local", raw)
        self.assertNotIn('"event": "fallback"', raw, "the local engine is the only choice, not a fallback")
        self.assertEqual(clog, [])

    def test_the_cloud_comes_back_once_the_window_has_passed(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local, window=0.3).server_address[1]
        call_tool(port, "fake__echo", {"text": "secret"})
        self.assertIn("from local", post(port, self.MSG)[1])
        time.sleep(0.5)
        self.assertIn("from the cloud", post(port, self.MSG)[1])

    def test_a_conversation_that_already_holds_private_data_stays_private(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local, window=0.001).server_address[1]
        history = {"messages": [
            {"role": "user", "content": "what was on my screen?"},
            {"role": "assistant", "content": None, "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "fake__echo", "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": "c1", "content": "your secret screen"},
            {"role": "user", "content": "and then?"}]}
        self.assertIn("from local", post(port, history)[1], "the tool's answer is in the history, so the history is private")
        self.assertEqual(clog, [])
        clean = {"messages": [{"role": "assistant", "content": None, "tool_calls": [
            {"id": "c2", "type": "function", "function": {"name": "plain__echo", "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": "c2", "content": "x"}, {"role": "user", "content": "hi"}]}
        self.assertIn("from the cloud", post(port, clean)[1], "a tool from a server that is not private does not matter")

    def test_without_a_local_engine_private_questions_are_refused_not_leaked(self):
        cloud, clog = self.engine(ok("from the cloud"))
        port = self.gw(cloud).server_address[1]
        call_tool(port, "fake__echo", {"text": "secret"})
        code, out = post(port, self.MSG)
        self.assertEqual(code, 503)
        self.assertIn("needs_local_engine", out)
        self.assertIn("engine add local", out)
        self.assertEqual(clog, [], "refusing means the cloud engine was never asked")

    def test_a_named_cloud_engine_cannot_be_used_for_private_data(self):
        cloud, clog = self.engine(ok("from the cloud"))
        fast, flog = self.engine(ok("from fast"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local, named={"fast": fast}).server_address[1]
        call_tool(port, "fake__echo", {"text": "secret"})
        self.assertIn("from local", post(port, dict(self.MSG, model="fast"))[1])
        self.assertEqual((clog, flog), ([], []))

    def test_a_named_local_engine_can_be(self):
        cloud, clog = self.engine(ok("from the cloud"))
        mine, mlog = self.engine(ok("from my own model"))
        cfg_named = {"mine": mine}
        srv = self.gw(cloud, named=cfg_named)
        srv.gw.cfg["engines"]["mine"]["local"] = True
        port = srv.server_address[1]
        call_tool(port, "fake__echo", {"text": "secret"})
        self.assertIn("from my own model", post(port, dict(self.MSG, model="mine"))[1])
        self.assertEqual(clog, [])

    def test_servers_that_are_not_private_leave_no_mark(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local).server_address[1]
        self.assertEqual(call_tool(port, "plain__echo", {"text": "hi"}), 200)
        self.assertEqual(call_tool(port, "fake__nosuchtool"), 404)
        self.assertIn("from the cloud", post(port, self.MSG)[1])

    def test_a_failed_call_still_counts_because_errors_can_carry_data(self):
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local).server_address[1]
        call_tool(port, "fake__fail")
        self.assertIn("from local", post(port, self.MSG)[1])

    def test_resources_and_prompts_from_a_private_server_count_too(self):
        servers = {"fake": spec("--resources", "--prompts", allow=[], private=True, resources=True, prompts=True)}
        cloud, clog = self.engine(ok("from the cloud"))
        local, llog = self.engine(ok("from local"))
        port = self.gw(cloud, local, mcp=servers).server_address[1]

        def read():
            req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/resources/read", method="POST",
                                         data=json.dumps({"server": "fake", "uri": "file:///notes.txt"}).encode(),
                                         headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
            return urllib.request.urlopen(req, timeout=30).status
        self.assertIn("from the cloud", post(port, self.MSG)[1])
        self.assertEqual(read(), 200)
        self.assertIn("from local", post(port, self.MSG)[1])

    def test_resource_helpers_in_the_history_are_recognised(self):
        host = gateway.make_server(dict(gateway.DEFAULTS, base_url="http://x", model="m", token=TOKEN,
                                        mcp_servers={"fake": spec("--resources", allow=[], private=True, resources=True),
                                                     "open": spec(allow=["*"])}), "127.0.0.1", 0)
        self.addCleanup(host.server_close)          # never started, so it must not be asked to shut down

        def history(name, args):
            return [{"role": "assistant", "tool_calls": [{"function": {"name": name, "arguments": args}}]}]
        mcp = host.mcp
        self.assertTrue(mcp.conversation_is_private(history("read_resource", '{"server": "fake", "uri": "u"}')))
        self.assertFalse(mcp.conversation_is_private(history("read_resource", '{"server": "open", "uri": "u"}')))
        self.assertTrue(mcp.conversation_is_private(history("list_resources", "{}")), "the list could include the private server")
        self.assertFalse(mcp.conversation_is_private(history("read_resource", "not json")))
        self.assertFalse(mcp.conversation_is_private("not a list"))
        self.assertFalse(mcp.conversation_is_private([{"role": "user", "content": "hi"}, None, 5]))


class PrivateSetupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["COREPACK_HOME"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("COREPACK_HOME", None))
        gateway.save(dict(gateway.DEFAULTS, token="t", base_url="https://cloud.example/v1", provider="gemini", model="m", api_key="k"))

    def run_cmd(self, *argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.main(["gateway", "mcp", *argv])
        return code, buf.getvalue()

    def test_a_server_can_be_marked_private(self):
        self.assertEqual(self.run_cmd("add", "--private", "--allow", "echo", "screen", "--", sys.executable, FAKE)[0], 0)
        self.assertTrue(gateway.load_file()["mcp_servers"]["screen"]["private"])
        self.assertIn("PRIVATE", self.run_cmd("list")[1])
        self.run_cmd("add", "open", "--", sys.executable, FAKE)
        self.assertNotIn("private", gateway.load_file()["mcp_servers"]["open"])

    def test_the_doctor_warns_when_private_data_has_nowhere_safe_to_go(self):
        cfg = dict(gateway.DEFAULTS, token="t", base_url="http://127.0.0.1:9", provider="gemini", model="m", api_key="k", local=False,
                   mcp_servers={"screen": spec(allow=["echo"], private=True)})
        checks = {what: state for state, what, _ in gateway.run_doctor(cfg, 8787)}
        self.assertEqual(checks["Private tool servers"], "warn")
        cfg["fallbacks"] = [{"provider": "local", "base_url": "http://127.0.0.1:9", "model": "m"}]
        checks = {what: state for state, what, _ in gateway.run_doctor(cfg, 8787)}
        self.assertEqual(checks["Private tool servers"], "ok")


if __name__ == "__main__":
    unittest.main()
