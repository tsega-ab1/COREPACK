# Phone AI gateway

A small program on the PC that sits between the phone app and the AI engine.

```
Phone (overlay app)  --USB-->  COREPACK gateway (PC)  -->  Gemini / Grok / local model / (later) big model
      token only                 keys live here
```

- The phone only knows a **token**. API keys never leave the PC.
- It speaks the standard OpenAI chat API, so any engine that does the same can sit behind it.
- It rate-limits, queues, and retries so free API tiers don't fall over.
- It tells the phone what is happening (queued, thinking, throttled) so the pet can react.

## Set up (Linux Mint)

```bash
sudo apt install adb                 # once, for the USB link
cd ~/COREPACK
./bin/corepack gateway init          # pick engine, paste API key (hidden), pick model
./bin/corepack gateway test          # confirms the key and model work
./bin/corepack gateway start         # runs the gateway and wires up the cable
```

On the phone: enable Developer options > USB debugging, plug in, accept the prompt.
`gateway start` runs `adb reverse` for you, so the phone reaches the gateway at
`http://127.0.0.1:8787/v1`. It prints the token; paste it into the phone app.

Config lives in `~/.corepack/gateway.json` (mode 600). `COREPACK_API_KEY` overrides the key.
`corepack gateway token --rotate` makes a new phone token.

## Engines

| Choice | Base URL |
|---|---|
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| Grok | `https://api.x.ai/v1` |
| Local llama.cpp | `http://127.0.0.1:8080/v1` |
| Local Ollama | `http://127.0.0.1:11434/v1` |

`init` asks the engine which models your key can use, so no model names are hard-coded.
Free tiers change; check the provider's current limits and data terms (Gemini's free tier
may use prompts to improve Google products).

## Protocol

`GET /health` (no token) returns `{"ok":true}`.
`GET /v1/models` (token) returns the configured model.
`POST /v1/chat/completions` (token) is OpenAI-compatible. Only `messages`, `temperature`,
`top_p`, `max_tokens`, `max_completion_tokens`, `stop`, `response_format`, `tools`,
`tool_choice` and `seed` are forwarded. The model is always the configured one.

Streaming adds SSE comment lines before and between normal `data:` chunks:

```
: corepack {"event":"queued","wait":12.3}       waiting for a free slot
: corepack {"event":"upstream_start"}           request sent to the engine
: corepack {"event":"throttle","retry_after":5} engine said slow down; retrying
```

Errors during a stream arrive as `data: {"error":{"message":...,"type":...,"code":...}}`
followed by `data: [DONE]`.

## Safety

- Listens on `127.0.0.1` only by default. `--host 0.0.0.0` exposes it to the network; the token is
  still required, but anyone with it can spend your API quota.
- The token is compared in constant time. Request bodies are capped at 1 MB.
- Upstream error text is scrubbed of your API key before it reaches the phone.
