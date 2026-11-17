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
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

from corepack import gateway, hosted  # noqa: E402
from corepack.gateway_cli import cmd_invite  # noqa: E402
from test_gateway import FakeUpstream, start  # noqa: E402

OWNER = "owner-token"
SHARED_KEY = "sk-owner-shared-key"
ALICE = "cpt_alice-code-123"
BOB = "cpt_bob-code-456"
THEIR_KEY = "AIzaSyTesterOwnKey000111"


class EchoErrorUpstream(BaseHTTPRequestHandler):
    """Fails and, like some real engines, repeats the credentials it was given in the error text."""

    def log_message(self, *a):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        out = json.dumps({"error": {"message": "bad credentials: " + str(self.headers.get("Authorization"))}}).encode()
        self.send_response(400)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


def call(base, token, body=None, headers=None, path="/v1/chat/completions", method=None):
    h = {"Content-Type": "application/json"}
    if token is not None:
        h["Authorization"] = "Bearer " + token
    h.update(headers or {})
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, headers=h, method=method or ("POST" if data else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


CHAT = {"messages": [{"role": "user", "content": "hi"}]}


class HostedBase(unittest.TestCase):
    upstream_handler = FakeUpstream
    extra: dict = {}

    @classmethod
    def setUpClass(cls):
        cls.up = HTTPServer(("127.0.0.1", 0), cls.upstream_handler)
        start(cls.up)
        up_url = f"http://127.0.0.1:{cls.up.server_address[1]}"
        cfg = dict(gateway.DEFAULTS, provider="gemini", base_url=up_url, api_key=SHARED_KEY, model="shared-model",
                   token=OWNER, rpm=0, hosted=True, invite_rpm=3, invite_daily=4,
                   invites={"alice": {"sha256": hosted.digest(ALICE)}, "bob": {"sha256": hosted.digest(BOB), "daily": 0, "rpm": 0}},
                   preset_urls={"gemini": up_url})
        cfg.update(cls.extra)
        cls.srv = gateway.make_server(cfg, "127.0.0.1", 0)
        start(cls.srv)
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        cls.up.shutdown()
        cls.up.server_close()

    def setUp(self):
        FakeUpstream.seen.clear()
        FakeUpstream.fail_429_once = False
        # every test starts with fresh allowances
        self.srv.gw.tenants = hosted.Tenants(self.srv.gw.cfg)


class TestInvites(HostedBase):
    def test_invite_and_owner_tokens_work_others_do_not(self):
        self.assertEqual(call(self.base, ALICE, CHAT)[0], 200)
        self.assertEqual(call(self.base, OWNER, CHAT)[0], 200)
        self.assertEqual(call(self.base, "cpt_wrong", CHAT)[0], 401)
        self.assertEqual(call(self.base, None, CHAT)[0], 401)
        self.assertEqual(call(self.base, "", CHAT)[0], 401)

    def test_models_check_accepts_an_invite(self):
        code, _ = call(self.base, ALICE, path="/v1/models")
        self.assertEqual(code, 200)

    def test_per_minute_limit_is_per_tester(self):
        codes = [call(self.base, ALICE, CHAT)[0] for _ in range(4)]
        self.assertEqual(codes, [200, 200, 200, 429])
        self.assertEqual(call(self.base, BOB, CHAT)[0], 200)             # bob has no limit and is unaffected

    def test_daily_limit_on_the_shared_key_says_what_to_do(self):
        tenants = self.srv.gw.tenants
        tenants.table["alice"]["rpm"] = 0
        results = [call(self.base, ALICE, CHAT) for _ in range(5)]
        self.assertEqual([r[0] for r in results], [200, 200, 200, 200, 429])
        self.assertIn("own API key", json.loads(results[-1][1])["error"]["message"])
        self.assertEqual(json.loads(results[-1][1])["error"]["type"], "daily_limit")

    def test_the_shared_key_is_what_the_engine_sees_without_a_byo_key(self):
        call(self.base, ALICE, CHAT)
        self.assertEqual(FakeUpstream.seen[-1]["auth"], "Bearer " + SHARED_KEY)
        self.assertEqual(FakeUpstream.seen[-1]["body"]["model"], "shared-model")

    def test_invites_from_the_environment(self):
        env = json.dumps({"carol": "cpt_plain-code", "dave": {"sha256": hosted.digest("cpt_d"), "daily": 2}})
        with mock.patch.dict(os.environ, {"COREPACK_INVITES": env}):
            t = hosted.Tenants(dict(gateway.DEFAULTS))
        self.assertEqual(t.lookup("cpt_plain-code").name, "carol")
        self.assertEqual(t.lookup("cpt_d").daily, 2)
        self.assertIsNone(t.lookup("nope"))
        self.assertIsNone(t.lookup(""))

    def test_no_invites_means_the_old_single_token_behaviour(self):
        t = hosted.Tenants(dict(gateway.DEFAULTS))
        self.assertFalse(t.enabled)
        self.assertIsNone(t.lookup("anything"))


class TestBringYourOwnKey(HostedBase):
    def test_their_key_is_used_instead_of_the_shared_one(self):
        code, _ = call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY, "X-Corepack-Model": "their-model"})
        self.assertEqual(code, 200)
        self.assertEqual(FakeUpstream.seen[-1]["auth"], "Bearer " + THEIR_KEY)
        self.assertEqual(FakeUpstream.seen[-1]["body"]["model"], "their-model")

    def test_own_key_is_not_counted_against_the_daily_allowance(self):
        self.srv.gw.tenants.table["alice"]["rpm"] = 0
        for _ in range(6):
            self.assertEqual(call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY})[0], 200)

    def test_model_falls_back_to_the_servers_choice_for_that_provider(self):
        call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY})
        self.assertEqual(FakeUpstream.seen[-1]["body"]["model"], "shared-model")

    def test_a_tester_cannot_pick_an_address_or_an_unsupported_provider(self):
        code, text = call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY, "X-Corepack-Provider": "custom"})
        self.assertEqual(code, 400)
        code, _ = call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY, "X-Corepack-Provider": "http://169.254.169.254/"})
        self.assertEqual(code, 400)
        self.assertEqual(FakeUpstream.seen, [])

    def test_bad_key_or_model_text_is_refused_before_anything_is_sent(self):
        for headers in ({"X-Corepack-Key": "short"}, {"X-Corepack-Key": "has space inside the key"},
                        {"X-Corepack-Key": THEIR_KEY, "X-Corepack-Model": "../../etc"},
                        {"X-Corepack-Key": THEIR_KEY, "X-Corepack-Model": "m" * 200}):
            self.assertEqual(call(self.base, ALICE, CHAT, headers)[0], 400, headers)
        self.assertEqual(FakeUpstream.seen, [])

    def test_streaming_works_with_their_key(self):
        code, text = call(self.base, ALICE, dict(CHAT, stream=True), {"X-Corepack-Key": THEIR_KEY})
        self.assertEqual(code, 200)
        self.assertIn("[DONE]", text)
        self.assertEqual(FakeUpstream.seen[-1]["auth"], "Bearer " + THEIR_KEY)

    def test_unit_provider_defaults(self):
        cfg = dict(gateway.DEFAULTS, provider="local", model="m")
        prov, err = hosted.byo_from_headers({"X-Corepack-Key": THEIR_KEY}, cfg)
        self.assertIsNone(prov)
        self.assertIn("model", err)
        prov, err = hosted.byo_from_headers({"X-Corepack-Key": THEIR_KEY, "X-Corepack-Model": "models/gemini-x"}, cfg)
        self.assertEqual(prov["model"], "gemini-x")
        self.assertIn("generativelanguage", prov["base_url"])
        self.assertEqual(hosted.byo_from_headers({}, cfg), (None, None))


class TestKeyIsScrubbed(HostedBase):
    upstream_handler = EchoErrorUpstream

    def test_error_text_never_repeats_their_key(self):
        code, text = call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY})
        self.assertNotEqual(code, 200)
        self.assertNotIn(THEIR_KEY, text)
        self.assertIn("***", text)

    def test_streamed_error_text_never_repeats_their_key(self):
        code, text = call(self.base, ALICE, dict(CHAT, stream=True), {"X-Corepack-Key": THEIR_KEY})
        self.assertNotIn(THEIR_KEY, text)

    def test_error_text_never_repeats_the_shared_key(self):
        code, text = call(self.base, ALICE, CHAT)
        self.assertNotIn(SHARED_KEY, text)


class TestToolsAreOff(HostedBase):
    extra = {"mcp_servers": {"files": {"command": "cat", "args": [], "allow": ["*"]}}}

    def test_hosted_never_starts_tool_servers(self):
        self.assertEqual(self.srv.mcp.servers(), {})

    def test_testers_see_no_tools_and_cannot_call_any(self):
        code, text = call(self.base, ALICE, path="/v1/tools")
        self.assertEqual((code, json.loads(text)["tools"]), (200, []))
        self.assertEqual(call(self.base, ALICE, {"name": "files__read", "arguments": {}}, path="/v1/tools/call")[0], 404)
        self.assertEqual(call(self.base, ALICE, {"server": "files", "uri": "x"}, path="/v1/resources/read")[0], 404)

    def test_testers_cannot_use_named_engines(self):
        self.srv.gw.cfg["engines"] = {"big": {"base_url": "http://127.0.0.1:1/v1", "model": "x", "api_key": "k"}}
        try:
            code, _ = call(self.base, ALICE, dict(CHAT, model="big"))
            self.assertEqual(code, 200)                       # answered by the main engine, not the named one
            self.assertEqual(FakeUpstream.seen[-1]["body"]["model"], "shared-model")
        finally:
            self.srv.gw.cfg["engines"] = {}


class TestNoSharedKey(HostedBase):
    extra = {"api_key": ""}

    def test_without_a_shared_key_testers_are_told_to_bring_their_own(self):
        code, text = call(self.base, ALICE, CHAT)
        self.assertEqual(code, 400)
        self.assertIn("own API key", json.loads(text)["error"]["message"])
        self.assertEqual(call(self.base, ALICE, CHAT, {"X-Corepack-Key": THEIR_KEY})[0], 200)


class TestInviteCommand(unittest.TestCase):
    def run_cmd(self, **kw):
        out = io.StringIO()
        with redirect_stdout(out):
            rc = cmd_invite(SimpleNamespace(**kw))
        return rc, out.getvalue()

    def test_add_list_export_revoke(self):
        with tempfile.TemporaryDirectory() as d, mock.patch.dict(os.environ, {"COREPACK_HOME": d}):
            rc, out = self.run_cmd(invite_cmd="add", name="alice", rpm=None, daily=7)
            self.assertEqual(rc, 0)
            code = [w for w in out.split() if w.startswith("cpt_")][0]
            with open(os.path.join(d, "gateway.json")) as f:
                saved = f.read()
            self.assertNotIn(code, saved)                                  # only the hash is stored
            self.assertIn(hosted.digest(code), saved)
            self.assertEqual(self.run_cmd(invite_cmd="list")[0], 0)
            rc, out = self.run_cmd(invite_cmd="export")
            exported = json.loads(out)
            self.assertEqual(exported["alice"]["daily"], 7)
            with mock.patch.dict(os.environ, {"COREPACK_INVITES": out}):
                self.assertEqual(hosted.Tenants(dict(gateway.DEFAULTS)).lookup(code).name, "alice")
            self.assertEqual(self.run_cmd(invite_cmd="revoke", name="alice")[0], 0)
            self.assertEqual(self.run_cmd(invite_cmd="revoke", name="alice")[0], 1)
            self.assertEqual(self.run_cmd(invite_cmd="add", name="bad name!", rpm=None, daily=None)[0], 1)


if __name__ == "__main__":
    unittest.main()
