# JVM tests

These test the phone app's code without a phone. Run `./run.sh` (see its header for what it needs).
They are plain Kotlin programs that print PASS or FAIL, not Gradle tests.

- `UnitTests.kt`: agent loop, memory, history trimming, tools.
- `E2E.kt` + `fake_gemini.py`: the agent talks to the real COREPACK gateway, which talks to a fake
  Gemini that rejects follow-up requests whose tool calls lost their `thought_signature`, and tool
  schemas with empty properties.
- `AssistantTests.kt`: reminders, tasks, notes (storage, escaping, caps), the tools on top of them, phone-action
  argument checks, weather and Wikipedia parsing against realistic API responses, nudge timing, the prompt.
- `MochiTests.kt`: the character: phase/mood mapping, palette, motion against the measured timings
  (dance beat, still bodies, breathing, blinking), cross-fades, one-off expressions, and a render
  sanity check of every state.
- `Java2DGfx.kt` + `MochiPreview.kt`: render the character on a desktop. `./run.sh preview` writes
  contact sheets (dark and light backgrounds), plus a frame sequence you can
  turn into a video with ffmpeg.
