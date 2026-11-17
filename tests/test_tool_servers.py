"""The example tool server: the calendar maths, the protocol, and a run through a real gateway."""
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from datetime import date, timedelta

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(ROOT, "tool_servers", "ethiopian_calendar_server.py")
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "tool_servers"))

import ethiopian_calendar_server as cal  # noqa: E402
from corepack.mcp_client import StdioMcpClient  # noqa: E402


class CalendarMaths(unittest.TestCase):
    # Dates the Ethiopian calendar is known for, with their Gregorian dates.
    KNOWN = [
        ((2015, 1, 1), date(2022, 9, 11)), ((2016, 1, 1), date(2023, 9, 12)), ((2017, 1, 1), date(2024, 9, 11)),
        ((2018, 1, 1), date(2025, 9, 11)),                  # New Year is 12 September in the year before a leap year
        ((2000, 1, 1), date(2007, 9, 12)),                  # the Millennium
        ((2017, 4, 29), date(2025, 1, 7)),                  # Genna (Christmas)
        ((2017, 5, 11), date(2025, 1, 19)),                 # Timkat (Epiphany)
        ((2017, 6, 23), date(2025, 3, 2)),                  # Adwa Victory Day
        ((2015, 13, 6), date(2023, 9, 11)),                 # Pagume 6, the leap day
    ]

    def test_known_dates_both_ways(self):
        for eth, greg in self.KNOWN:
            self.assertEqual(cal.ethiopian_to_gregorian(*eth), greg, eth)
            self.assertEqual(cal.gregorian_to_ethiopian(greg.year, greg.month, greg.day), eth, greg)

    def test_every_day_from_1900_to_2100_round_trips(self):
        d, end, previous = date(1900, 1, 1), date(2100, 12, 31), None
        while d <= end:
            eth = cal.gregorian_to_ethiopian(d.year, d.month, d.day)
            self.assertEqual(cal.ethiopian_to_gregorian(*eth), d)
            if previous is not None:  # days follow on: day, then month, then year rolls over correctly
                y, m, dd = previous
                nxt = (y, m, dd + 1) if (dd < 30 and not (m == 13 and dd == (6 if y % 4 == 3 else 5))) else ((y, m + 1, 1) if m < 13 else (y + 1, 1, 1))
                self.assertEqual(eth, nxt, d)
            previous = eth
            d += timedelta(days=1)

    def test_not_real_dates_are_refused(self):
        for bad in [(2017, 14, 1), (2017, 1, 31), (2017, 13, 6), (2016, 13, 6), (2017, 0, 1), (2017, 1, 0), (0, 1, 1)]:
            with self.assertRaises(cal.BadInput, msg=str(bad)):
                cal.ethiopian_to_gregorian(*bad)
        for bad in [(2025, 2, 30), (2025, 13, 1), (2023, 2, 29)]:
            with self.assertRaises(cal.BadInput, msg=str(bad)):
                cal.gregorian_to_ethiopian(*bad)

    def test_pagume_has_six_days_only_in_leap_years(self):
        self.assertEqual(cal.ethiopian_to_gregorian(2015, 13, 6), date(2023, 9, 11))  # 2015 % 4 == 3
        with self.assertRaises(cal.BadInput):
            cal.ethiopian_to_gregorian(2016, 13, 6)

    def test_the_words(self):
        self.assertEqual(cal.tool_gregorian_to_ethiopian({"year": 2025, "month": 1, "day": 7}), "Tuesday 7 January 2025 is Tahsas 29, 2017 E.C.")
        self.assertEqual(cal.tool_ethiopian_to_gregorian({"year": 2017, "month": 1, "day": 1}), "Meskerem 1, 2017 E.C. is Wednesday 11 September 2024")
        self.assertIn("E.C.", cal.tool_today({}))

    def test_numbers_may_arrive_as_text_or_floats(self):
        self.assertEqual(cal.to_int({"year": "2025"}, "year"), 2025)
        self.assertEqual(cal.to_int({"year": 2025.0}, "year"), 2025)
        for bad in [{"year": 2025.5}, {"year": True}, {"year": "x"}, {}, {"year": None}]:
            with self.assertRaises(cal.BadInput):
                cal.to_int(bad, "year")


class Protocol(unittest.TestCase):
    def test_the_gateways_own_client_can_use_it(self):
        client = StdioMcpClient([sys.executable, SERVER], timeout=20)
        client.start()
        try:
            tools = {t["name"]: t for t in client.list_tools()}
            self.assertEqual(set(tools), {"gregorian_to_ethiopian", "ethiopian_to_gregorian", "today"})
            self.assertTrue(all(t["annotations"]["readOnlyHint"] for t in tools.values()))
            self.assertNotIn("func", json.dumps(tools))
            out = client.call_tool("gregorian_to_ethiopian", {"year": 2025, "month": 1, "day": 7})
            self.assertFalse(out.get("isError"))
            self.assertIn("Tahsas 29, 2017", out["content"][0]["text"])
            bad = client.call_tool("ethiopian_to_gregorian", {"year": 2016, "month": 13, "day": 6})
            self.assertTrue(bad["isError"])
            self.assertIn("does not exist", bad["content"][0]["text"])
        finally:
            client.close()


class ThroughTheGateway(unittest.TestCase):
    """Add the server to a real gateway, then ask for its tools and run one, the way the phone does."""

    def setUp(self):
        self.home = tempfile.mkdtemp()
        self.env = {**os.environ, "COREPACK_HOME": self.home, "FAKEKEY": "x", "PYTHONPATH": ROOT}
        run = lambda *a: subprocess.run([sys.executable, "-m", "corepack", "gateway", *a], env=self.env, cwd=ROOT, capture_output=True, text=True, timeout=60)
        self.assertEqual(run("init", "--provider", "custom", "--base-url", "http://127.0.0.1:9", "--api-key-env", "FAKEKEY", "--model", "m", "--rpm", "0").returncode, 0)
        self.assertEqual(run("mcp", "add", "--allow-all", "ethiopia", "--", sys.executable, SERVER).returncode, 0)
        self.token = run("token").stdout.strip().splitlines()[-1].strip()
        self.port = 8795
        self.proc = subprocess.Popen([sys.executable, "-m", "corepack", "gateway", "start", "--port", str(self.port), "--no-adb"], env=self.env, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def tearDown(self):
        self.proc.terminate()
        self.proc.wait(timeout=10)

    def request(self, path, body=None):
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/v1{path}", data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        for _ in range(40):
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    return json.load(r)
            except OSError:
                time.sleep(0.25)
        self.fail("the gateway did not answer")

    def test_the_phone_sees_and_runs_the_tools(self):
        listing = self.request("/tools")
        names = {t["name"]: t for t in listing["tools"]}
        self.assertEqual(set(names), {"ethiopia__gregorian_to_ethiopian", "ethiopia__ethiopian_to_gregorian", "ethiopia__today"})
        self.assertFalse(any(t["needs_confirm"] for t in names.values()))
        out = self.request("/tools/call", {"name": "ethiopia__ethiopian_to_gregorian", "arguments": {"year": 2017, "month": 5, "day": 11}})
        self.assertTrue(out["ok"])
        self.assertIn("Sunday 19 January 2025", out["text"])


if __name__ == "__main__":
    unittest.main()
