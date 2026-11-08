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

## Backup engines

A busy or rate-limited engine does not have to stop the pet. Add a backup and the gateway switches to it by itself:

```
corepack gateway fallback add      # asks for the provider, key and model; repeat for more backups
corepack gateway fallback list
corepack gateway fallback clear
```

- A backup only helps if it is a different model. When Gemini says "high demand" for one model, the same model on
  a second key says the same thing. Pick another model, or another company's engine.
- The backup must support tool calls, because the pet's abilities depend on them.
- After a server error (5xx) or an unreachable engine, the gateway leaves that engine alone for 20 seconds, so
  messages go straight to the backup instead of each paying for a failed try. A rate limit (429) uses the engine's
  own Retry-After instead. With no backup there is nowhere else to go, so the engine is simply tried again.
- A bad request (400) is about that one request, so it never benches an engine.

## Tools (MCP)

The gateway can host MCP servers (the standard way to give an AI model tools such as web fetching or a knowledge
graph) and offer their tools to the phone. The phone never talks to a tool server itself: it asks the gateway for
a list and asks it to run one, so the servers and their API keys stay on this PC.

```
corepack gateway mcp add [options] NAME -- COMMAND [ARGS...]    # options go before the name
corepack gateway mcp tools NAME      # start it and show what it offers (first run may download it)
corepack gateway mcp allow NAME TOOL [TOOL...]     # or --all / --none
corepack gateway mcp list
corepack gateway mcp remove NAME
```

For example, with Node installed:

```
corepack gateway mcp add memory -- npx -y @modelcontextprotocol/server-memory
corepack gateway mcp tools memory
corepack gateway mcp allow memory read_graph search_nodes
```

Servers that need a key take it from your shell, so it is not written into the settings file:
`corepack gateway mcp add --env 'SOME_API_KEY=$SOME_API_KEY' NAME -- COMMAND ...`.

The safety rules:

- A server's tools are hidden until you allow them with `--allow`, `mcp allow` or `--allow-all`.
- Tools that are not marked read-only are flagged `needs_confirm`, so the phone can ask before running them.
  `--confirm always` flags every tool, `--confirm never` none.
- A tool server only sees a short list of ordinary variables (PATH, HOME, proxies) plus the ones you give it with
  `--env`. It never sees the gateway's own API keys.
- Each tool's argument schema is reduced to the common subset every engine accepts (type, description, enum,
  properties, required, items), because engines such as Gemini refuse keywords like `format: uri` or `$ref`.
- Results are cut at `mcp_max_result` characters (default 4000) and calls time out after `mcp_timeout` seconds
  (default 30). Tool descriptions and results come from the server, so only add servers you trust.
- Servers start in the background when the gateway starts, are restarted if they crash, and stop when the gateway
  stops. A server that is still starting (the first run can download it) or that failed to start is left out of the
  list and explained under `errors`; a failed start is not retried more than once a minute. On a slow connection,
  give it longer with `--startup-timeout 180`, or run it once yourself first so it is cached, for example
  `uvx mcp-server-fetch < /dev/null`.

What the phone uses, both behind the same token as chat:

| Request | Answer |
|---|---|
| `GET /v1/tools` | `{"tools": [{"name", "server", "description", "parameters", "needs_confirm"}], "errors": {"server": "why it is not working"}}` |
| `POST /v1/tools/call` with `{"name": "server__tool", "arguments": {...}}` | `{"ok": true, "text": "..."}`; an unknown or not-allowed tool is a 404, a server that failed a 502 |

`corepack gateway doctor` lists each tool server and whether its program is installed, without starting it.

## Safety

- Listens on `127.0.0.1` only by default. `--host 0.0.0.0` exposes it to the network; the token is
  still required, but anyone with it can spend your API quota.
- The token is compared in constant time. Request bodies are capped at 1 MB.
- Upstream error text is scrubbed of your API key before it reaches the phone.

## Code layout

The gateway used to be one file. It is split by job, and `corepack/gateway.py` still re-exports every old
name, so `from corepack import gateway` keeps working. Each module only imports the ones above it.

| Module | Job |
|---|---|
| `providers.py` | The engine presets (Gemini, Grok, local, custom) and listing an engine's models |
| `gateway_config.py` | Defaults, loading, saving and checking `gateway.json` |
| `ratelimit.py` | The per-minute limiter |
| `upstream.py` | Talking to the engine: main plus backups, failover, rate-limit handling |
| `gateway_server.py` | The HTTP handler: token check, chat requests, streaming |
| `mcp_client.py` | A small MCP client for tool servers that run as child processes |
| `mcp_host.py` | Starts the tool servers, applies the allow list, and cleans up names, schemas and results |
| `adb.py` | Reading `adb` output and `adb reverse` for the USB cable |
| `pairing.py` | Pairing codes and the PC's Wi-Fi addresses |
| `gateway_doctor.py` | The checks behind `gateway doctor` |
| `gateway_cli.py` | The `corepack gateway ...` commands |
