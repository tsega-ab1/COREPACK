import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from corepack import gateway  # noqa: E402
from corepack.pairing import lan_addresses, pairing_uri  # noqa: E402

TOKEN = "phone-token"


def fetch(host, port, path, token=None, data=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{host}:{port}{path}", data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


class WifiTests(unittest.TestCase):
    def serve(self, bind):
        cfg = dict(gateway.DEFAULTS, provider="main", base_url="http://127.0.0.1:9", api_key="k", model="m", token=TOKEN, rpm=0)
        srv = gateway.make_server(cfg, bind, 0)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(lambda: (srv.shutdown(), srv.server_close()))
        return srv.server_address[1]

    def wifi_address(self):
        found = lan_addresses()
        if not found:
            self.skipTest("this machine has no Wi-Fi or LAN address to test with")
        return found[0] if isinstance(found[0], str) else found[0][0]

    def test_by_default_only_this_pc_can_connect(self):
        ip = self.wifi_address()
        port = self.serve("127.0.0.1")
        self.assertEqual(fetch("127.0.0.1", port, "/health"), 200)
        with self.assertRaises((urllib.error.URLError, ConnectionError, OSError)):
            fetch(ip, port, "/health")

    def test_wifi_mode_is_reachable_on_the_lan_address(self):
        ip = self.wifi_address()
        port = self.serve("0.0.0.0")
        self.assertEqual(fetch(ip, port, "/health"), 200, "the phone can reach it at this PC's Wi-Fi address")

    def test_wifi_mode_still_needs_the_token_for_everything_that_matters(self):
        ip = self.wifi_address()
        port = self.serve("0.0.0.0")
        body = json.dumps({"messages": [{"role": "user", "content": "hi"}]}).encode()
        for path in ("/v1/tools", "/v1/engines", "/v1/resources", "/v1/prompts"):
            self.assertEqual(fetch(ip, port, path), 401, path)
            self.assertEqual(fetch(ip, port, path, token="wrong"), 401, path)
        self.assertEqual(fetch(ip, port, "/v1/chat/completions", data=body), 401)
        self.assertEqual(fetch(ip, port, "/v1/chat/completions", token="wrong", data=body), 401)
        self.assertEqual(fetch(ip, port, "/v1/tools/call", data=b'{"name":"x"}'), 401)
        self.assertEqual(fetch(ip, port, "/v1/tools", token=TOKEN), 200)
        self.assertEqual(fetch(ip, port, "/v1/engines", token=TOKEN), 200)

    def test_a_pairing_code_carries_the_wifi_address_and_the_token(self):
        uri = pairing_uri("http://192.168.1.20:8787/v1", "a b+c/d")
        parsed = urllib.parse.urlparse(uri)
        query = urllib.parse.parse_qs(parsed.query)
        self.assertEqual((parsed.scheme, parsed.netloc), ("corepack", "pair"))
        self.assertEqual(query["url"], ["http://192.168.1.20:8787/v1"])
        self.assertEqual(query["token"], ["a b+c/d"], "odd characters survive the trip")


if __name__ == "__main__":
    unittest.main()
