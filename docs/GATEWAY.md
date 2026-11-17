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

## Named engines

Besides the main engine and its backups, you can give engines names. A request that puts a name in its `model`
field is answered by that engine first, with the main chain behind it as a fallback. This is what lets the pet
hand a task to a different model: a fast one for quick things, a careful one for hard ones, a local one for
private ones.

```
corepack gateway engine add fast --provider gemini --model gemini-2.5-flash --description "Quick answers and summaries"
corepack gateway engine add deep --provider gemini --model gemini-2.5-pro --description "Careful reasoning and long answers"
corepack gateway engine list
corepack gateway engine remove NAME
```

- Names may use letters, digits and dashes. The description is what the model reads to decide when to use one, so
  say what it is good for.
- `GET /v1/engines` lists the names and descriptions for the phone. Keys, addresses and model names are never
  sent to it, and keys are hidden in error messages.
- An engine that fails sits out for 20 seconds, like the others, and never affects the main engine's cooldown.
- `corepack gateway doctor` checks each named engine; one that fails is a warning, not a blocker.

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

### Web search and page reading, with no API key

The DuckDuckGo tool server is a ready-made way to give the pet web search. It needs [uv](https://docs.astral.sh/uv/)
installed on the PC (it provides `uvx`):

```
corepack gateway mcp add --confirm never --allow search,fetch_content,expand_link web -- uvx duckduckgo-mcp-server
corepack gateway mcp tools web       # first run downloads it; you should see search, fetch_content, expand_link
corepack gateway start               # restart so the phone sees the change
```

- Its tools are not marked read-only, so with the default `--confirm auto` the phone asks in the chat before every
  search. `--confirm never` trusts this one server and skips the question.
- The names above were checked against `duckduckgo-mcp-server` 0.7.0. If a later version renames them, `mcp tools web`
  shows the current names.
- Searches go to DuckDuckGo, and pages are fetched from this PC's connection. Page text comes from the internet, so
  the pet is told to treat it as information and never as instructions.
- On the phone: open Tools, switch on "Online tools", and ask for something that needs the web.

### Resources and prompts

MCP servers can offer two more things besides tools. Both are off for every server until you switch them on, and
only what the server itself lists can be used.

- **Resources** are data to read: files, records, notes. Switch them on with `mcp add --resources ...`
  (it replaces the entry, so repeat the other options). `GET /v1/resources` lists them and
  `POST /v1/resources/read` with `{"server": "...", "uri": "..."}` returns `{"ok": true, "text": "..."}`.
  Binary content is left out, and the text is cut at `mcp_max_result` like tool results.
- **Prompts** are ready-made request templates. Switch them on with `--prompts`. `GET /v1/prompts` lists them and
  `POST /v1/prompts/get` with `{"server": "...", "name": "...", "arguments": {...}}` returns the filled-in text.
- `corepack gateway mcp resources NAME` and `mcp prompts NAME` start a server and show what it has.

## Private tool servers and local engines

Some tool servers hold data that should never leave this PC, for example one that searches your screen history.
Mark such a server private and the gateway keeps its data local:

```
corepack gateway mcp add --private --confirm always NAME -- COMMAND ARGS...
```

- Once a private server is used (a tool call, a resource read or a prompt, even one that fails), only an engine that
  runs on this PC may answer, for `private_window` seconds (default 600), and for as long as the conversation still
  contains that server's tool calls. Any other engine, including a named one the model asks for, is skipped.
- An engine counts as local if it is the `local` preset, or its address is this machine (`127.x`, `localhost`).
  Set `"local": true` or `false` on an engine in `gateway.json` to override that, for example for a model on
  another PC you trust.
- If there is no local engine, the question is refused with a message that says how to add one. It is never sent
  to a cloud engine instead. `corepack gateway doctor` warns about this before it happens.
- This protects the data the gateway hands out. A cloud model that decides to call the tool still sees the
  question that led to it, and anything you type yourself still goes to whichever engine answers.

### Example: ScreenMind (local screen memory)

[ScreenMind](https://github.com/ayushh0110/ScreenMind) captures your screen, analyses it with a local Gemma model
and offers its history over MCP. Check its README for current install steps; the gateway side looks like this:

```
# 1. Run ScreenMind. It starts its own llama.cpp server. Find its port:
ss -ltnp | grep llama-server

# 2. Add that server as a local engine, so private questions have somewhere to go:
corepack gateway engine add local --provider local --base-url http://127.0.0.1:PORT/v1

# 3. Add ScreenMind's MCP server as a private tool server, using ScreenMind's own Python:
corepack gateway mcp add --private --confirm always screenmind -- /path/to/screenmind/venv/bin/python -m screenmind.mcp_server
corepack gateway mcp tools screenmind
corepack gateway mcp allow screenmind search_screen get_recent_activity get_daily_summary
```

Tool names come from ScreenMind's documentation, so check them with `mcp tools screenmind` before allowing any.
Leave out tools that act (such as taking a screenshot on demand) unless you want the pet to be able to do that.
The local engine answers the follow-up turns, so it needs a build that accepts tool calls (llama.cpp's server
needs `--jinja` for that); if its replies fail, check that first.

## Wi-Fi

By default the gateway only listens on this PC (USB mode). `corepack gateway start --lan` also listens on the Wi-Fi
address, `corepack gateway pair` prints a pairing code that carries that address and the token, and the token is
still required for everything except `/health`. Plain HTTP is used, so only do this on a network you trust, and
rotate the token (`corepack gateway token --rotate`) before you do.

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
| `upstream.py` | Talking to the engine: main, named engines and backups, failover, rate-limit handling |
| `gateway_server.py` | The HTTP handler: token check, chat requests, streaming |
| `mcp_client.py` | A small MCP client for tool servers that run as child processes |
| `mcp_host.py` | Starts the tool servers, applies the allow list, and cleans up names, schemas and results |
| `adb.py` | Reading `adb` output and `adb reverse` for the USB cable |
| `pairing.py` | Pairing codes and the PC's Wi-Fi addresses |
| `gateway_doctor.py` | The checks behind `gateway doctor` |
| `gateway_cli.py` | The `corepack gateway ...` commands |
