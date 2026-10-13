import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = []

def sse(handler, chunks):
    handler.send_response(200)
    handler.send_header("Content-Type", "text/event-stream")
    handler.end_headers()
    for c in chunks:
        handler.wfile.write(("data: " + json.dumps({"choices": [{"delta": c}]}) + "\n\n").encode()); handler.wfile.flush()
    handler.wfile.write(b"data: [DONE]\n\n")

def err(handler, code, msg):
    out = json.dumps({"error": {"message": msg, "code": code}}).encode()
    handler.send_response(code); handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(out))); handler.end_headers(); handler.wfile.write(out)

def sig(sig_value):
    return {"google": {"thought_signature": sig_value}}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        out = json.dumps(LOG).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        LOG.append(body)
        msgs, tools = body["messages"], body.get("tools") or []
        # Gemini quirk: an OBJECT schema without properties is rejected
        for t in tools:
            p = t["function"].get("parameters")
            if p is not None and p.get("type") == "object" and not p.get("properties"):
                return err(self, 400, "parameters.properties: should be non-empty for OBJECT type")
        last = msgs[-1]
        if last["role"] == "tool":
            asst = [m for m in msgs if m["role"] == "assistant" and m.get("tool_calls")][-1]
            first = asst["tool_calls"][0]
            if not ((first.get("extra_content") or {}).get("google", {}).get("thought_signature")):
                return err(self, 400, "Function call is missing a thought_signature in functionCall parts.")
            ids = {c["id"] for c in asst["tool_calls"]}
            answered = {m["tool_call_id"] for m in msgs[msgs.index(asst) + 1:] if m["role"] == "tool"}
            if ids != answered:
                return err(self, 400, "tool results do not match the function calls")
            return sse(self, [{"role": "assistant", "content": "Result: "}, {"content": last["content"]}])
        text = last["content"].lower()
        if "remember" in text:      # OpenAI-style fragments with index; signature on the tool call
            return sse(self, [
                {"role": "assistant", "tool_calls": [{"index": 0, "id": "function-call-A1", "type": "function",
                    "function": {"name": "remember", "arguments": "{\"fa"}, "extra_content": sig("SIG_A")}]},
                {"tool_calls": [{"index": 0, "function": {"arguments": "ct\":\"likes jasmine tea\"}"}}]}])
        if "happy" in text:         # text + fire-and-forget tool; signature on the TEXT delta, none on the call
            return sse(self, [
                {"role": "assistant", "content": "That's wonderful! ", "extra_content": sig("SIG_B")},
                {"tool_calls": [{"id": "function-call-B1", "type": "function",
                    "function": {"name": "set_pet_mood", "arguments": "{\"mood\":\"happy\"}"}}]}])
        if "time" in text:          # no index, empty arguments, signature on the call
            return sse(self, [{"role": "assistant", "tool_calls": [{"id": "function-call-C1", "type": "function",
                    "function": {"name": "get_time", "arguments": ""}, "extra_content": sig("SIG_C")}]}])
        if "nosig" in text:         # a call with no signature at all: the follow-up must be rejected
            return sse(self, [{"role": "assistant", "tool_calls": [{"id": "function-call-D1", "type": "function",
                    "function": {"name": "get_time", "arguments": "{}"}}]}])
        return sse(self, [{"role": "assistant", "content": "Hello "}, {"content": "there."}])

ThreadingHTTPServer(("127.0.0.1", 9921), H).serve_forever()
