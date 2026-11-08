"""A tiny MCP server over stdio, for the gateway's tests. Flags: --page-size=N --version=V --noisy --ping"""
import json
import os
import sys
import time

FLAGS = dict(a.lstrip("-").split("=", 1) if "=" in a else (a.lstrip("-"), "1") for a in sys.argv[1:])
VERSION = FLAGS.get("version", "2025-11-25")
PAGE = int(FLAGS.get("page-size", "0"))

OBJ = {"type": "object", "properties": {}}
TOOLS = [
    {"name": "echo", "description": "Echo the text back", "annotations": {"readOnlyHint": True},
     "inputSchema": {"type": "object", "$schema": "http://json-schema.org/draft-07/schema#", "additionalProperties": False,
                     "properties": {"text": {"type": "string", "description": "what to say"},
                                    "title": {"type": "object", "additionalProperties": False, "properties": {"x": {"type": "number"}}}},
                     "required": ["text"]}},
    {"name": "delete_everything", "description": "Pretend to delete things", "inputSchema": OBJ},
    {"name": "slow", "description": "Sleep for a while", "annotations": {"readOnlyHint": True},
     "inputSchema": {"type": "object", "properties": {"seconds": {"type": "number"}}}},
    {"name": "big", "description": "Return a lot of text", "annotations": {"readOnlyHint": True}, "inputSchema": OBJ},
    {"name": "fail", "description": "Report an error", "annotations": {"readOnlyHint": True}, "inputSchema": OBJ},
    {"name": "crash", "description": "Exit abruptly", "annotations": {"readOnlyHint": True}, "inputSchema": OBJ},
    {"name": "image", "description": "Return a picture", "annotations": {"readOnlyHint": True}, "inputSchema": OBJ},
    {"name": "structured", "description": "Return structured data only", "annotations": {"readOnlyHint": True}, "inputSchema": OBJ},
    {"name": "getenv", "description": "Read an environment variable", "annotations": {"readOnlyHint": True},
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}}}},
    {"name": "weird name!", "description": "A tool whose name needs cleaning", "annotations": {"readOnlyHint": True}, "inputSchema": OBJ},
]


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def text(t, error=False):
    return {"content": [{"type": "text", "text": t}], "isError": error}


def call(name, args):
    if name == "echo":
        return text("echo: " + str(args.get("text", "")))
    if name == "delete_everything":
        return text("deleted (not really)")
    if name == "slow":
        time.sleep(float(args.get("seconds", 1)))
        return text("done sleeping")
    if name == "big":
        return text("x" * 20000)
    if name == "fail":
        return text("it broke", error=True)
    if name == "crash":
        os._exit(3)
    if name == "image":
        return {"content": [{"type": "image", "data": "AAAA", "mimeType": "image/png"}]}
    if name == "structured":
        return {"content": [], "structuredContent": {"answer": 42}}
    if name == "getenv":
        return text(os.environ.get(str(args.get("name")), "<unset>"))
    if name == "weird name!":
        return text("weird ok")
    return None


if "noisy" in FLAGS:
    print("starting up... (not JSON)", flush=True)
    sys.stderr.write("some log line on stderr\n")
    sys.stderr.flush()

for line in sys.stdin:
    try:
        msg = json.loads(line)
    except ValueError:
        continue
    method, rid = msg.get("method"), msg.get("id")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": rid, "result": {"protocolVersion": VERSION, "capabilities": {"tools": {"listChanged": True}},
                                                     "serverInfo": {"name": "fake", "version": "1"}}})
    elif method == "tools/list":
        start = int((msg.get("params") or {}).get("cursor") or 0)
        chunk = TOOLS[start:start + PAGE] if PAGE else TOOLS
        result = {"tools": chunk}
        if PAGE and start + PAGE < len(TOOLS):
            result["nextCursor"] = str(start + PAGE)
        send({"jsonrpc": "2.0", "id": rid, "result": result})
    elif method == "tools/call":
        if "ping" in FLAGS:                      # the client has to answer this before we answer it
            send({"jsonrpc": "2.0", "id": 9001, "method": "ping"})
            for reply in sys.stdin:
                r = json.loads(reply)
                if r.get("id") == 9001 and "result" in r:
                    break
        params = msg.get("params") or {}
        out = call(params.get("name"), params.get("arguments") or {})
        if out is None:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "Unknown tool"}})
        else:
            send({"jsonrpc": "2.0", "id": rid, "result": out})
    elif method == "ping":
        send({"jsonrpc": "2.0", "id": rid, "result": {}})
    elif rid is not None:
        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})
