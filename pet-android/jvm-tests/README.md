# JVM tests

These test the phone app's code without a phone. Run `./run.sh` (see its header for what it needs).
They are plain Kotlin programs that print PASS or FAIL, not Gradle tests.

- `UnitTests.kt`: agent loop, memory, history trimming, tools.
- `E2E.kt` + `fake_gemini.py`: the agent talks to the real COREPACK gateway, which talks to a fake
  Gemini that rejects follow-up requests whose tool calls lost their `thought_signature`, and tool
  schemas with empty properties.
- `AssistantTests.kt`: reminders, tasks, notes (storage, escaping, caps), the tools on top of them, phone-action
  argument checks, weather and Wikipedia parsing against realistic API responses, nudge timing, the prompt.
- `ModuleTests.kt`: the tool registry (order, switches, lazy building) and a parity check that the prompt built from
  modules is word for word what the old hand-written builder produced, for all 32 combinations of switches.
- `SessionTests.kt`: `AgentSession`, the conversation itself: history carry-over, reminders folded in between
  messages, cleanup after a failed turn, trimming, cancelling, and a tool round trip, with a scripted fake model.
- `PacedTests.kt`: `PacedSink`, which releases a reply in readable slices: order, Stop, pace, end-of-reply hook.
- `ToolArchTests.kt`: what Android must allow before a module's tools appear, tools that must ask before they run, and
  what the screen eye keeps private (sensitive apps, card numbers, codes).
- `ConfirmTextTests.kt`: the words of the approval question for tools that are not read-only: arguments shown line by line,
  long, cut-short and nested values.
- `RemoteToolTests.kt`: tools that run on the PC through the gateway: the HTTP client (against a fake gateway running inside the test),
  approval, caching, the on/off switch and a whole turn that uses one.
- `DelegationTests.kt`: handing a task to another model by engine name, what the specialist is (and is not) given, reading
  resources from the PC, and Stop reaching whatever is running. Uses a small fake gateway inside the test.
- `BehaviorTests.kt`: chaining several actions, recovering when one fails, one retry for read-only online tools, telling the user how each
  action went, which tools must ask first, and remembering the conversation across a restart. Scripted models, no real engine.
- `LiveEval.kt` + `eval.sh`: not a test. Runs the real tools (made-up results) against the REAL engine behind your gateway and prints how well it
  chooses tools, chains steps, recovers, reports and respects a refusal. `GATEWAY_TOKEN=... ./eval.sh`
- `NudgeTests.kt`: where nudges come from and the rules shared by every source: night, daily limit, cooldown, order.
- `PetLookTests.kt`: the character: phase/mood mapping, palette, motion against the measured timings
  (dance beat, still bodies, breathing, blinking), cross-fades, one-off expressions, and a render
  sanity check of every state.
- `Java2DGfx.kt` + `PetPreview.kt`: render the character on a desktop. `./run.sh preview` writes
  contact sheets (dark and light backgrounds), plus a frame sequence you can
  turn into a video with ffmpeg.
