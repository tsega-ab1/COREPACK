# JVM tests for the agent

These test the phone app's agent code without a phone. Run `./run.sh` (see the header of that file
for what it needs). They are plain Kotlin programs that print PASS or FAIL, not Gradle tests.

- `UnitTests.kt`: agent loop, memory, history trimming, tools.
- `E2E.kt` + `fake_gemini.py`: the agent talks to the real COREPACK gateway, which talks to a fake
  Gemini that behaves like the real one in the ways that matter: it rejects follow-up requests whose
  tool calls lost their `thought_signature`, and tool schemas with empty properties.
