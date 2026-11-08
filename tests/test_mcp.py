import io
import json
import os
import re
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from contextlib import redirect_stdout

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from corepack import cli, gateway  # noqa: E402
from corepack.mcp_client import McpError, StdioMcpClient  # noqa: E402
from corepack.mcp_host import McpHost, UnknownTool, clean_schema, public_name  # noqa: E402

FAKE = os.path.join(os.path.dirname(__file__), "fake_mcp_server.py")
TOKEN = "phone-token"


def client(*flags, timeout=10.0):
    c = StdioMcpClient([sys.executable, FAKE, *flags], timeout=timeout, name="fake")
    c.start(startup_timeout=10)
    return c


def spec(*flags, **extra):
    return dict({"command": sys.executable, "args": [FAKE, *flags], "allow": ["*"]}, **extra)


def host(servers=None, **cfg):
    return McpHost(dict(gateway.DEFAULTS, mcp_servers=servers if servers is not None else {"fake": spec()}, **cfg))


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.clients = []

    def tearDown(self):
        for c in self.clients:
            c.close()

    def make(self, *flags, **kw):
        c = client(*flags, **kw)
        self.clients.append(c)
        return c

    def test_handshake_and_tool_list(self):
        c = self.make()
        self.assertEqual(c.server_info["name"], "fake")
        self.assertEqual(c.protocol_version, "2025-11-25")
        names = [t["name"] for t in c.list_tools()]
        self.assertIn("echo", names)
        self.assertEqual(len(names), 10)

    def test_older_servers_are_accepted(self):
        self.assertEqual(self.make("--version=2024-11-05").protocol_version, "2024-11-05")

    def test_unknown_protocol_version_is_refused(self):
        with self.assertRaises(McpError) as cm:
            client("--version=1999-01-01")
        self.assertIn("1999-01-01", str(cm.exception))

    def test_paged_lists_are_followed(self):
        self.assertEqual(len(self.make("--page-size=3").list_tools()), 10)

    def test_a_call_returns_the_servers_answer(self):
        r = self.make().call_tool("echo", {"text": "hi"})
        self.assertEqual(r["content"][0]["text"], "echo: hi")

    def test_a_server_error_is_raised_with_its_message(self):
        with self.assertRaises(McpError) as cm:
            self.make().call_tool("nope", {})
        self.assertIn("Unknown tool", str(cm.exception))
        self.assertEqual(cm.exception.code, -32602)

    def test_a_slow_call_times_out_and_the_client_survives(self):
        c = self.make()
        with self.assertRaises(McpError) as cm:
            c.call_tool("slow", {"seconds": 1.0}, timeout=0.3)
        self.assertIn("timed out", str(cm.exception))
        time.sleep(1.2)                          # the late answer arrives and is ignored
        self.assertEqual(c.call_tool("echo", {"text": "still here"})["content"][0]["text"], "echo: still here")

    def test_a_crash_is_reported_and_the_client_knows_it_is_gone(self):
        c = self.make()
        with self.assertRaises(McpError) as cm:
            c.call_tool("crash", {})
        self.assertIn("exited", str(cm.exception))
        time.sleep(0.2)
        self.assertFalse(c.alive())

    def test_noise_on_stdout_and_stderr_is_tolerated(self):
        self.assertEqual(self.make("--noisy").call_tool("echo", {"text": "x"})["content"][0]["text"], "echo: x")

    def test_server_pings_are_answered(self):
        c = self.make("--ping", timeout=5)
        self.assertEqual(c.call_tool("echo", {"text": "after ping"})["content"][0]["text"], "echo: after ping")

    def test_a_missing_program_is_a_clear_error(self):
        c = StdioMcpClient(["definitely-not-installed-xyz"])
        with self.assertRaises(McpError) as cm:
            c.start()
        self.assertIn("definitely-not-installed-xyz", str(cm.exception))

    def test_close_stops_the_process(self):
        c = self.make()
        proc = c._proc
        c.close()
        self.assertIsNotNone(proc.poll())
        self.assertFalse(c.alive())


class HelperTests(unittest.TestCase):
    def test_schema_cleaning_keeps_property_names_and_the_basics(self):
        s = {"$schema": "x", "type": "object", "additionalProperties": False,
             "properties": {"title": {"type": "string", "description": "a  title\n here", "additionalProperties": False},
                            "nested": {"type": "object", "properties": {"$defs": {"type": "number"}}, "additionalProperties": False}},
             "required": ["title"]}
        self.assertEqual(clean_schema(s), {
            "type": "object",
            "properties": {"title": {"type": "string", "description": "a title here"},
                           "nested": {"type": "object", "properties": {"$defs": {"type": "number"}}}},
            "required": ["title"]})

    def test_schema_cleaning_removes_what_gemini_refuses(self):
        # shaped like the schema of the Fetch server, written by pydantic
        s = {"type": "object", "properties": {
            "url": {"type": "string", "format": "uri", "minLength": 1, "description": "URL to fetch"},
            "max_length": {"type": "integer", "default": 5000, "exclusiveMaximum": 1000000, "exclusiveMinimum": 0, "description": "Maximum characters"},
            "raw": {"type": "boolean", "default": False},
            "start_index": {"anyOf": [{"type": "integer", "minimum": 0}, {"type": "null"}], "description": "Where to start"}},
            "required": ["url", "missing_property"]}
        out = clean_schema(s)
        text = json.dumps(out)
        for banned in ("format", "minLength", "exclusiveMaximum", "exclusiveMinimum", "default", "minimum", "anyOf", "null"):
            self.assertNotIn(banned, text)
        self.assertEqual(out["properties"]["url"], {"type": "string", "description": "URL to fetch"})
        self.assertEqual(out["properties"]["start_index"], {"type": "integer", "description": "Where to start"})
        self.assertEqual(out["required"], ["url"], "a required name that is not a property is dropped")

    def test_schema_cleaning_resolves_references_and_odd_types(self):
        s = {"$defs": {"Mode": {"type": "string", "enum": ["fast", "slow"]}},
             "type": "object", "properties": {
                 "mode": {"$ref": "#/$defs/Mode", "description": "which one"},
                 "maybe": {"type": ["string", "null"]},
                 "level": {"type": "integer", "enum": [1, 2, 3]},
                 "kind": {"const": "text"},
                 "tags": {"type": "array"},
                 "untyped": {},
                 "gone": {"$ref": "#/$defs/DoesNotExist"}}}
        p = clean_schema(s)["properties"]
        self.assertEqual(p["mode"], {"type": "string", "enum": ["fast", "slow"], "description": "which one"})
        self.assertEqual(p["maybe"], {"type": "string"})
        self.assertEqual(p["level"], {"type": "integer", "description": "One of: 1, 2, 3"})
        self.assertEqual(p["kind"], {"type": "string", "enum": ["text"]})
        self.assertEqual(p["tags"], {"type": "array", "items": {"type": "string"}})
        self.assertEqual(p["untyped"], {"type": "string"})
        self.assertEqual(p["gone"], {"type": "string"})
        self.assertNotIn("$defs", json.dumps(clean_schema(s)))

    def test_a_tool_without_a_schema_gets_an_empty_object_schema(self):
        self.assertEqual(clean_schema(None), {"type": "object", "properties": {}})
        self.assertEqual(clean_schema({}), {"type": "object", "properties": {}})

    def test_a_self_referencing_schema_cannot_loop_forever(self):
        s = {"$defs": {"Node": {"type": "object", "properties": {"next": {"$ref": "#/$defs/Node"}}}},
             "type": "object", "properties": {"head": {"$ref": "#/$defs/Node"}}}
        self.assertIn("head", clean_schema(s)["properties"])

    def test_public_names_are_valid_function_names(self):
        for server, tool in [("fake", "echo"), ("my server!", "weird name!"), ("a" * 40, "b" * 40)]:
            n = public_name(server, tool)
            self.assertRegex(n, r"^[A-Za-z0-9_-]{1,64}$")
        self.assertEqual(public_name("fake", "echo"), "fake__echo")


class HostTests(unittest.TestCase):
    def setUp(self):
        self.hosts = []

    def tearDown(self):
        for h in self.hosts:
            h.close()

    def make(self, *a, **kw):
        h = host(*a, **kw)
        self.hosts.append(h)
        return h

    def test_tools_stay_hidden_until_allowed(self):
        h = self.make({"fake": spec(allow=[])})
        self.assertEqual(h.tools(), [])
        h2 = self.make({"fake": {"command": sys.executable, "args": [FAKE]}})
        self.assertEqual(h2.tools(), [])

    def test_only_allowed_tools_are_offered_and_callable(self):
        h = self.make({"fake": spec(allow=["echo"])})
        self.assertEqual([t["name"] for t in h.tools()], ["fake__echo"])
        with self.assertRaises(UnknownTool):
            h.call("fake__delete_everything", {})

    def test_the_whole_server_can_be_allowed(self):
        names = {t["name"] for t in self.make().tools()}
        self.assertIn("fake__delete_everything", names)
        self.assertIn("fake__weird_name_", names, "names are cleaned up for the engine")

    def test_the_schema_reaches_the_phone_cleaned(self):
        echo = next(t for t in self.make().tools() if t["name"] == "fake__echo")
        self.assertNotIn("additionalProperties", json.dumps(echo["parameters"]))
        self.assertNotIn("$schema", echo["parameters"])
        self.assertEqual(echo["parameters"]["required"], ["text"])
        self.assertIn("title", echo["parameters"]["properties"], "a property that happens to be called title is kept")
        self.assertTrue(echo["description"].startswith("Echo"))

    def test_confirmation_flags(self):
        by = {t["name"]: t["needs_confirm"] for t in self.make().tools()}
        self.assertFalse(by["fake__echo"], "read-only tools run without asking")
        self.assertTrue(by["fake__delete_everything"], "tools that are not marked read-only ask first")
        always = {t["name"]: t["needs_confirm"] for t in self.make({"fake": spec(confirm="always")}).tools()}
        self.assertTrue(all(always.values()))
        never = {t["name"]: t["needs_confirm"] for t in self.make({"fake": spec(confirm="never")}).tools()}
        self.assertFalse(any(never.values()))

    def test_a_call_returns_text(self):
        h = self.make()
        h.tools()
        self.assertEqual(h.call("fake__echo", {"text": "hi"}), {"ok": True, "text": "echo: hi"})

    def test_calling_before_listing_still_works(self):
        self.assertEqual(self.make().call("fake__echo", {"text": "hi"})["text"], "echo: hi")

    def test_tool_errors_are_flagged(self):
        r = self.make().call("fake__fail", {})
        self.assertEqual(r, {"ok": False, "text": "it broke"})

    def test_long_results_are_cut(self):
        r = self.make(mcp_max_result=1000).call("fake__big", {})
        self.assertTrue(r["text"].startswith("x" * 1000))
        self.assertIn("19000 more characters", r["text"])
        self.assertLess(len(r["text"]), 1100)

    def test_pictures_and_structured_results_become_text(self):
        h = self.make()
        self.assertEqual(h.call("fake__image", {})["text"], "[image content omitted]")
        self.assertEqual(json.loads(h.call("fake__structured", {})["text"]), {"answer": 42})

    def test_a_crashed_server_comes_back_on_the_next_call(self):
        h = self.make()
        with self.assertRaises(McpError):
            h.call("fake__crash", {})
        time.sleep(0.2)
        self.assertEqual(h.call("fake__echo", {"text": "again"})["text"], "echo: again")

    def test_a_broken_server_does_not_hide_the_others(self):
        h = self.make({"fake": spec(allow=["echo"]), "ghost": {"command": "definitely-not-installed-xyz", "allow": ["*"]}})
        self.assertEqual([t["name"] for t in h.tools()], ["fake__echo"])
        self.assertIn("ghost", h.errors)
        self.assertIn("not found", h.errors["ghost"])

    def test_the_server_only_sees_the_environment_it_is_given(self):
        os.environ["GATEWAY_SECRET_FOR_TEST"] = "do-not-leak"
        os.environ["SHELL_VALUE_FOR_TEST"] = "from-the-shell"
        self.addCleanup(lambda: [os.environ.pop(k, None) for k in ("GATEWAY_SECRET_FOR_TEST", "SHELL_VALUE_FOR_TEST")])
        h = self.make({"fake": spec(env={"MY_KEY": "$SHELL_VALUE_FOR_TEST", "PLAIN": "abc"})})
        self.assertEqual(h.call("fake__getenv", {"name": "GATEWAY_SECRET_FOR_TEST"})["text"], "<unset>")
        self.assertEqual(h.call("fake__getenv", {"name": "MY_KEY"})["text"], "from-the-shell")
        self.assertEqual(h.call("fake__getenv", {"name": "PLAIN"})["text"], "abc")

    def test_the_server_is_started_once(self):
        h = self.make()
        h.tools()
        first = h._clients["fake"]
        h.tools()
        h.call("fake__echo", {"text": "x"})
        self.assertIs(h._clients["fake"], first)

    def test_non_object_arguments_are_refused(self):
        with self.assertRaises(McpError):
            self.make().call("fake__echo", "not an object")

    def test_close_stops_every_server(self):
        h = self.make()
        h.tools()
        proc = h._clients["fake"]._proc
        h.close()
        self.assertIsNotNone(proc.poll())

    def test_warm_up_starts_servers_without_making_anyone_wait(self):
        h = self.make({"slow": spec("--init-delay=1.5"), "fast": spec(allow=["echo"])})
        threads = h.warm()
        deadline = time.monotonic() + 10
        while "fast" not in h._clients and time.monotonic() < deadline:
            time.sleep(0.05)
        t0 = time.monotonic()
        names = [t["name"] for t in h.tools()]
        self.assertLess(time.monotonic() - t0, 1.0, "a listing must not wait for the slow server")
        self.assertEqual(names, ["fast__echo"])
        self.assertIn("still starting", h.errors["slow"])
        for t in threads:
            t.join(15)
        self.assertIn("slow__echo", [t["name"] for t in h.tools()], "once it is up, its tools appear")
        self.assertNotIn("slow", h.errors)

    def test_a_failed_start_is_not_retried_on_every_listing(self):
        with tempfile.TemporaryDirectory() as d:
            counter = os.path.join(d, "launches")
            h = self.make({"bad": spec("--exit-at-start", f"--count-file={counter}")})
            for _ in range(3):
                self.assertEqual(h.tools(), [])
            with open(counter) as f:
                launches = len(f.read().split())
            self.assertEqual(launches, 1, "the broken server was started once, not three times")
            self.assertIn("exited", h.errors["bad"])

    def test_a_call_may_retry_a_server_that_failed_before(self):
        with tempfile.TemporaryDirectory() as d:
            h = self.make({"fake": spec(allow=["echo"])})
            h.tools()
            h._failed["fake"] = (time.monotonic(), "pretend the last start failed")
            self.assertEqual(h.call("fake__echo", {"text": "hi"})["text"], "echo: hi", "a call is something the person is waiting for")

    def test_the_start_timeout_comes_from_the_config(self):
        h = self.make({"slow": spec("--init-delay=3", startup_timeout=0.5)})
        self.assertEqual(h.tools(), [])
        self.assertIn("timed out after 0.5 s", h.errors["slow"])

    def test_closing_during_warm_up_leaves_nothing_running(self):
        h = self.make({"slow": spec("--init-delay=1")})
        threads = h.warm()
        time.sleep(0.3)
        h.close()
        for t in threads:
            t.join(15)
        self.assertEqual(h._clients, {})

    def test_status_needs_no_process(self):
        h = self.make({"a": spec(allow=["echo"]), "b": spec(allow=[]), "c": {"command": "definitely-not-installed-xyz", "allow": ["*"]}})
        states = {name: state for state, name, _ in h.status()}
        self.assertEqual(states, {"a": "ok", "b": "warn", "c": "fail"})
        self.assertEqual(h._clients, {}, "checking must not start anything")


def get(port, path, token=TOKEN):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"Authorization": "Bearer " + token} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def post(port, path, body, token=TOKEN, raw=None):
    data = raw if raw is not None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method="POST",
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


class HttpTests(unittest.TestCase):
    def setUp(self):
        cfg = dict(gateway.DEFAULTS, provider="main", base_url="http://127.0.0.1:9", api_key="KEY-SECRET", model="m",
                   token=TOKEN, rpm=0, mcp_servers={"fake": spec(allow=["echo", "slow", "crash"]),
                                                     "ghost": {"command": "definitely-not-installed-xyz", "allow": ["*"]}})
        self.srv = gateway.make_server(cfg, "127.0.0.1", 0)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.port = self.srv.server_address[1]

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()

    def test_listing_needs_the_token(self):
        self.assertEqual(get(self.port, "/v1/tools", token="wrong")[0], 401)
        self.assertEqual(get(self.port, "/v1/tools", token=None)[0], 401)

    def test_listing_shows_allowed_tools_and_explains_broken_servers(self):
        code, body = get(self.port, "/v1/tools")
        self.assertEqual(code, 200)
        self.assertEqual(sorted(t["name"] for t in body["tools"]), ["fake__crash", "fake__echo", "fake__slow"])
        self.assertIn("ghost", body["errors"])
        self.assertTrue(all({"name", "description", "parameters", "needs_confirm", "server"} <= set(t) for t in body["tools"]))

    def test_calling_a_tool(self):
        code, body = post(self.port, "/v1/tools/call", {"name": "fake__echo", "arguments": {"text": "hello"}})
        self.assertEqual((code, body), (200, {"ok": True, "text": "echo: hello"}))

    def test_calling_needs_the_token(self):
        self.assertEqual(post(self.port, "/v1/tools/call", {"name": "fake__echo"}, token="wrong")[0], 401)

    def test_an_unlisted_tool_is_not_found(self):
        code, body = post(self.port, "/v1/tools/call", {"name": "fake__delete_everything", "arguments": {}})
        self.assertEqual(code, 404)
        self.assertEqual(body["error"]["type"], "unknown_tool")

    def test_bad_requests_are_rejected(self):
        self.assertEqual(post(self.port, "/v1/tools/call", None, raw=b"not json")[0], 400)
        self.assertEqual(post(self.port, "/v1/tools/call", {"arguments": {}})[0], 400)
        self.assertEqual(post(self.port, "/v1/tools/call", {"name": "fake__echo", "arguments": "no"})[0], 400)

    def test_a_server_that_dies_is_a_clean_502_and_recovers(self):
        code, body = post(self.port, "/v1/tools/call", {"name": "fake__crash", "arguments": {}})
        self.assertEqual((code, body["error"]["type"]), (502, "tool_server_error"))
        time.sleep(0.2)
        self.assertEqual(post(self.port, "/v1/tools/call", {"name": "fake__echo", "arguments": {"text": "back"}})[0], 200)

    def test_chat_still_works_alongside(self):
        code, body = post(self.port, "/v1/chat/completions", {"messages": []})
        self.assertEqual(code, 400)          # the chat route is still there and still validates its body

    def test_stopping_the_gateway_stops_the_tool_servers(self):
        get(self.port, "/v1/tools")
        proc = self.srv.mcp._clients["fake"]._proc
        self.srv.shutdown()
        self.srv.server_close()
        self.assertIsNotNone(proc.poll())
        self.srv.mcp = None                  # tearDown closes again without complaint


class CommandTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["COREPACK_HOME"] = self.tmp
        self.addCleanup(lambda: os.environ.pop("COREPACK_HOME", None))
        gateway.save(dict(gateway.DEFAULTS, token="t", base_url="http://x", model="m", api_key="k"))

    def run_cmd(self, *argv):
        # through the real entry point, so the parser and the dispatch are exercised too
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.main(["gateway", "mcp", *argv])
        return code, buf.getvalue()

    def test_add_list_allow_remove(self):
        code, out = self.run_cmd("add", "--env", "KEY=$KEY", "fetch", "--", sys.executable, FAKE, "--noisy")
        self.assertEqual(code, 0)
        self.assertIn("No tools are allowed yet", out)
        s = gateway.load_file()["mcp_servers"]["fetch"]
        self.assertEqual((s["command"], s["args"], s["env"], s["allow"], s["confirm"]), (sys.executable, [FAKE, "--noisy"], {"KEY": "$KEY"}, [], "auto"))
        self.assertEqual(os.stat(gateway.file_path()).st_mode & 0o777, 0o600)
        self.assertEqual(self.run_cmd("allow", "fetch", "echo", "slow")[0], 0)
        self.assertEqual(gateway.load_file()["mcp_servers"]["fetch"]["allow"], ["echo", "slow"])
        self.run_cmd("allow", "fetch", "echo", "big")
        self.assertEqual(gateway.load_file()["mcp_servers"]["fetch"]["allow"], ["echo", "slow", "big"])
        code, out = self.run_cmd("list")
        self.assertIn("fetch", out)
        self.assertIn("echo, slow, big", out)
        self.assertIn("env: KEY", out)
        self.assertNotIn("$KEY", out, "values of environment variables are never printed")
        self.assertEqual(self.run_cmd("allow", "fetch", "--all")[0], 0)
        self.assertEqual(gateway.load_file()["mcp_servers"]["fetch"]["allow"], ["*"])
        self.assertEqual(self.run_cmd("allow", "fetch", "--none")[0], 0)
        self.assertEqual(gateway.load_file()["mcp_servers"]["fetch"]["allow"], [])
        self.assertEqual(self.run_cmd("remove", "fetch")[0], 0)
        self.assertEqual(gateway.load_file()["mcp_servers"], {})

    def test_add_with_allow_options(self):
        self.run_cmd("add", "--allow", "x, y", "--confirm", "never", "a", "--", "prog")
        s = gateway.load_file()["mcp_servers"]["a"]
        self.assertEqual((s["allow"], s["confirm"], s["command"], s["args"]), (["x", "y"], "never", "prog", []))
        self.run_cmd("add", "--allow-all", "b", "--", "prog", "-y", "pkg")
        self.assertEqual(gateway.load_file()["mcp_servers"]["b"]["allow"], ["*"])

    def test_a_start_timeout_can_be_set(self):
        self.run_cmd("add", "--startup-timeout", "120", "slowpoke", "--", "prog")
        self.assertEqual(gateway.load_file()["mcp_servers"]["slowpoke"]["startup_timeout"], 120.0)
        self.run_cmd("add", "normal", "--", "prog")
        self.assertNotIn("startup_timeout", gateway.load_file()["mcp_servers"]["normal"])

    def test_bad_input_is_refused(self):
        self.assertEqual(self.run_cmd("add", "bad name!", "--", "prog")[0], 1)
        self.assertEqual(self.run_cmd("add", "ok")[0], 1, "a command is required")
        self.assertEqual(self.run_cmd("add", "--env", "NOEQUALS", "ok", "--", "prog")[0], 1)
        code, out = self.run_cmd("add", "ok", "--allow", "echo", "--", "prog")
        self.assertEqual(code, 1)
        self.assertIn("options before the name", out, "options after the name would be taken for the command")
        self.assertEqual(self.run_cmd("remove", "missing")[0], 1)
        self.assertEqual(self.run_cmd("tools", "missing")[0], 1)
        self.assertEqual(gateway.load_file()["mcp_servers"], {})

    def test_tools_shows_what_a_server_offers_and_stops_it(self):
        self.run_cmd("add", "--allow", "echo", "fake", "--", sys.executable, FAKE)
        code, out = self.run_cmd("tools", "fake")
        self.assertEqual(code, 0)
        self.assertIn("delete_everything", out)
        self.assertIn("echo", out)

    def test_tools_reports_a_server_that_cannot_start(self):
        self.run_cmd("add", "ghost", "--", "definitely-not-installed-xyz")
        code, out = self.run_cmd("tools", "ghost")
        self.assertEqual(code, 1)
        self.assertIn("not found", out)

    def test_doctor_lists_tool_servers_without_starting_them(self):
        cfg = dict(gateway.DEFAULTS, token="t", provider="custom", base_url="http://127.0.0.1:9", model="m", api_key="k",
                   mcp_servers={"a": spec(allow=["echo"]), "c": {"command": "definitely-not-installed-xyz", "allow": ["*"]}})
        labels = {what: st for st, what, _ in gateway.run_doctor(cfg, 8787)}
        self.assertEqual(labels["Tool server a"], "ok")
        self.assertEqual(labels["Tool server c"], "fail")


if __name__ == "__main__":
    unittest.main()
