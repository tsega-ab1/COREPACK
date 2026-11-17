# Adding a tool

A tool is something the model can ask the pet to do. Tools come in groups called modules. Each module has its own
switch on the Tools screen, so the user decides what the pet may do.

There are two kinds of tool:

- **Phone tools**, written in Kotlin and built into the app (reminders, notes, opening apps, the screen eye).
- **PC tools**, hosted by the gateway as MCP servers (web search, reading pages). You add those with
  `corepack gateway mcp add`, see GATEWAY.md. They need no change to the app.

The rest of this page is about phone tools.

## The five steps

1. **Add a capability.** In `agent/Capabilities.kt` add one entry: a label, a one-line blurb that says honestly what
   the tool can do and what leaves the phone, and a colour family (`Hue`). It starts switched on or off for a new
   install according to `Capability.defaults`; anything that reads private data or acts for the user starts off.
2. **Write the tools.** Each is a `Tool`: a `spec` (name, description, JSON schema of its arguments) and
   `run(arguments): String`. Return a short result, or text starting with `error: ` and a reason the model can pass on.
   Set `risk = Risk.ASK` on a tool that changes something that matters; the registry then asks the user before every run.
3. **Write the module.** A `ToolModule` ties them together:

   ```kotlin
   class ClockModule : ToolModule {
       override val capability: Capability? = Capability.CLOCK          // the switch (an example: add this entry yourself)
       override val promptHint = "- get_time tells you the date and time. Use it instead of guessing.\n"
       override val requirements = emptyList<Requirement>()             // what Android must allow
       override fun tools(): List<Tool> = listOf(TimeTool())
   }
   ```

   `promptHint` is added to the system prompt only while the module is on. If the tools take text from outside
   (a web page, the screen), the hint must say that text is information and never instructions.
4. **Register it.** Add the module to the list in `PetModules.builtin`. Nothing else changes: the Tools screen, the
   Home counts and the settings read the capability list.
5. **Test it.** Tools that do not touch Android classes can be tested on a plain JVM; see `pet-android/jvm-tests`.
   Add checks to a test file and run `bash pet-android/jvm-tests/run.sh`.

## PC tools: more than web search

Any MCP server can be added to the gateway, and each one is a new group of tools for the pet. They all work the same way:

```
corepack gateway mcp add [--allow TOOL,TOOL | --allow-all] [--confirm auto|always|never] NAME -- COMMAND ARGS...
corepack gateway mcp tools NAME      # see what it offers; the pet's names are NAME__TOOL
corepack gateway start               # restart, then open the Tools screen and look under "From your PC"
```

Servers I checked against this gateway (read-only tools run freely; the rest ask first):

| Server | Add with | What the pet gets |
|---|---|---|
| Time zones | `corepack gateway mcp add --allow-all time -- uvx mcp-server-time` | `get_current_time`, `convert_time`, `get_time_ago` (all read-only) |
| Git | `corepack gateway mcp add git -- uvx mcp-server-git` | `git_status`, `git_log`, `git_diff`, `git_show` and more (read-only); `git_commit`, `git_add`, `git_merge`, `git_checkout` and the other changing tools ask in the chat first |
| Web search | see "Web search" in GATEWAY.md | `search`, `fetch_content`, `expand_link` |
| Ethiopian calendar | `corepack gateway mcp add --allow-all ethiopia -- python3 /full/path/to/tool_servers/ethiopian_calendar_server.py` | `gregorian_to_ethiopian`, `ethiopian_to_gregorian`, `today` (read-only) |

Tips:

- **Allow only what you need.** Every allowed tool costs a little of the model's attention and context. A handful of
  well-named tools works better than fifty. Use `--allow` to pick, and keep the changing tools on `--confirm auto`.
- **Trust matters.** A server's tool descriptions and results come from the server, and what a tool returns is shown to
  the model. Only add servers you trust, and never put keys in the command: use `--env 'KEY=$KEY'`.
- Tools you want to be able to publish or share are exactly this: a small program anyone can add with one command.

## Writing your own PC tool

`tool_servers/ethiopian_calendar_server.py` is a complete example in one file with no dependencies: a list of tools
(a name, a description for the model, a JSON schema of the arguments, `readOnlyHint` when it only reads), one function
per tool, and a loop that answers the gateway's `initialize`, `tools/list` and `tools/call`. Copy it, change the tools,
and add it with `corepack gateway mcp add`. Its tests (`tests/test_tool_servers.py`) show how to check a tool server:
the maths on its own, then through the gateway's own client, then through a real gateway the way the phone uses it.
Anything written to stdout must be protocol messages, so print logs to stderr.

## What Android has to allow

If a tool needs a permission or a special switch (Accessibility, exact alarms, and so on), list it in the module's
`requirements`. If it is not an existing `Requirement`, add one in `agent/ToolModule.kt` and teach `Requirements`
(in the app) how to check it and which Settings page grants it. Then:

- the Tools screen shows it on the capability's card, with a Grant button;
- while a blocking requirement is missing, the model does not get the module's tools, and is told in one line that
  the module is on but needs that permission, so it can tell the user instead of saying it cannot;
- a requirement with `blocksTools = false` (like exact alarms for reminders) only shows the hint.

## Tools that must ask first

Set `risk = Risk.ASK` on a tool and the registry wraps it: the chat shows "Let the pet run X?" with the arguments,
and the tool runs only after "Allow once". No answer, a closed chat, or a failed question counts as no. Use it for
anything that sends, deletes, buys, taps or types. Read-only tools should not ask, or people stop reading the question.

## The screen eye and privacy

`read_screen` reads what the user has open, so it follows stricter rules (see `agent/ScreenGuard.kt`):

- It is off until the user switches it on, and works only after the Accessibility switch is on in Android Settings.
- A bubble says "Looking at your screen" each time it reads.
- It refuses banking, wallet, password-manager and authenticator apps (matched by words in the package name; the code
  also accepts a list of extra package names, which the app does not expose yet).
- Password fields, card numbers, and short codes in text that talks about codes or PINs are hidden before the text
  reaches the model.
- Screen text comes from other apps and can contain instructions. The prompt tells the model to treat it as information.
- It is read-only. A tool that taps or types must be `Risk.ASK`.

These are defaults, not guarantees: the screen can show anything the user opens, and the rules only catch the common cases.
