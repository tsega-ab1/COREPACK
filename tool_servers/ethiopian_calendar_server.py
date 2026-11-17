"""A tool server for the pet: the Ethiopian calendar.

It speaks MCP over stdio and needs nothing but Python 3.9+. Add it to the gateway with:

    corepack gateway mcp add --allow-all ethiopia -- python3 /full/path/to/tool_servers/ethiopian_calendar_server.py

The pet can then convert dates both ways ("what is 7 January 2025 in the Ethiopian calendar?") and say today's date
in both calendars. It is also a template for your own tool server: a list of tools, a function per tool, and a
small loop that answers the gateway. See docs/TOOLS.md.
"""
import json
import sys
from datetime import date, datetime, timedelta, timezone

MONTHS = ["Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir", "Yekatit", "Megabit", "Miazia", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume"]
WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
GREGORIAN_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
EAT = timezone(timedelta(hours=3))  # East Africa Time; Ethiopia has no daylight saving

JDN_OFFSET = 1721425      # date.toordinal() + this = the Julian Day Number of that day
ETHIOPIAN_EPOCH = 1723856


def ethiopian_to_jdn(year, month, day):
    return ETHIOPIAN_EPOCH + 365 * year + year // 4 + 30 * month + day - 31


def jdn_to_ethiopian(jdn):
    since = jdn - ETHIOPIAN_EPOCH
    r = since % 1461
    n = r % 365 + 365 * (r // 1460)
    return 4 * (since // 1461) + r // 365 - r // 1460, n // 30 + 1, n % 30 + 1


class BadInput(ValueError):
    pass


def to_int(args, name):
    try:
        value = args[name]
        if isinstance(value, bool) or (isinstance(value, float) and value != int(value)):
            raise ValueError
        return int(value)
    except (KeyError, TypeError, ValueError):
        raise BadInput("year, month and day must all be whole numbers")


def gregorian_to_ethiopian(year, month, day):
    try:
        d = date(year, month, day)
    except ValueError:
        raise BadInput(f"{year}-{month}-{day} is not a real date in the Gregorian calendar")
    return jdn_to_ethiopian(d.toordinal() + JDN_OFFSET)


def ethiopian_to_gregorian(year, month, day):
    # A date is real only if converting it there and back gives the same date (this catches Pagume 6 in a
    # year that is not a leap year, day 31, month 14, and so on).
    if not (1 <= month <= 13 and 1 <= day <= 30 and 1 <= year <= 9990):
        raise BadInput(f"{day}/{month}/{year} is not a real date in the Ethiopian calendar")
    jdn = ethiopian_to_jdn(year, month, day)
    if jdn_to_ethiopian(jdn) != (year, month, day):
        raise BadInput(f"{MONTHS[month - 1]} {day}, {year} does not exist (Pagume has 6 days only in a leap year)")
    try:
        return date.fromordinal(jdn - JDN_OFFSET)
    except ValueError:
        raise BadInput("that date is outside the range this tool handles")


def eth_text(y, m, d):
    return f"{MONTHS[m - 1]} {d}, {y} E.C."


def greg_text(d):
    return f"{WEEKDAYS[d.weekday()]} {d.day} {GREGORIAN_MONTHS[d.month - 1]} {d.year}"


def tool_gregorian_to_ethiopian(args):
    y, m, d = to_int(args, "year"), to_int(args, "month"), to_int(args, "day")
    eth = gregorian_to_ethiopian(y, m, d)  # raises BadInput if it is not a real date
    return f"{greg_text(date(y, m, d))} is {eth_text(*eth)}"


def tool_ethiopian_to_gregorian(args):
    y, m, d = to_int(args, "year"), to_int(args, "month"), to_int(args, "day")
    return f"{eth_text(y, m, d)} is {greg_text(ethiopian_to_gregorian(y, m, d))}"


def tool_today(args):
    today = datetime.now(EAT).date()
    return f"Today is {greg_text(today)}, which is {eth_text(*gregorian_to_ethiopian(today.year, today.month, today.day))}"


INT = {"type": "integer"}
TOOLS = [
    {"name": "gregorian_to_ethiopian", "func": tool_gregorian_to_ethiopian,
     "description": "Convert a date from the Gregorian calendar to the Ethiopian calendar (month names such as Meskerem, Tir, Pagume)",
     "inputSchema": {"type": "object", "required": ["year", "month", "day"], "properties": {
         "year": {**INT, "description": "Gregorian year, for example 2025"},
         "month": {**INT, "description": "Gregorian month, 1 to 12"},
         "day": {**INT, "description": "Day of the month"}}}},
    {"name": "ethiopian_to_gregorian", "func": tool_ethiopian_to_gregorian,
     "description": "Convert a date from the Ethiopian calendar to the Gregorian calendar",
     "inputSchema": {"type": "object", "required": ["year", "month", "day"], "properties": {
         "year": {**INT, "description": "Ethiopian year, for example 2017"},
         "month": {**INT, "description": "Ethiopian month number, 1 (Meskerem) to 13 (Pagume)"},
         "day": {**INT, "description": "Day of the month, 1 to 30 (Pagume has 5, or 6 in a leap year)"}}}},
    {"name": "today", "func": tool_today,
     "description": "Today's date in both calendars, in Ethiopian time",
     "inputSchema": {"type": "object", "properties": {}}},
]
for t in TOOLS:
    t["annotations"] = {"readOnlyHint": True}  # they only read: the gateway will not ask before running them


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def text(t, error=False):
    return {"content": [{"type": "text", "text": t}], "isError": error}


def call(name, args):
    for t in TOOLS:
        if t["name"] == name:
            try:
                return text(t["func"](args))
            except BadInput as e:
                return text(str(e), error=True)
    return None


def main():
    listed = [{k: v for k, v in t.items() if k != "func"} for t in TOOLS]
    for line in sys.stdin:
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        method, rid = msg.get("method"), msg.get("id")
        params = msg.get("params") or {}
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": params.get("protocolVersion") or "2025-06-18",
                "capabilities": {"tools": {}}, "serverInfo": {"name": "ethiopian-calendar", "version": "1"}}})
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": rid, "result": {"tools": listed}})
        elif method == "tools/call":
            out = call(params.get("name"), params.get("arguments") or {})
            if out is None:
                send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "Unknown tool"}})
            else:
                send({"jsonrpc": "2.0", "id": rid, "result": out})
        elif method == "ping":
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})


if __name__ == "__main__":
    main()
