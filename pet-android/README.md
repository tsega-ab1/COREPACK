# CorePack Pet (Android)

A pet that floats over other apps; tap it to chat. It talks to the COREPACK gateway on your PC over
the USB cable, and acts as a small agent: it can use tools, remember things about you, and show how
it feels. See `../docs/PHONE_PET.md` for the overview.

Plain Android views, no extra libraries. Package `com.corepack.pet`, minSdk 26, targetSdk 34.

## Build (command line, no Android Studio)

Needs JDK 17, the Android SDK (platform 35, build-tools 36) and Gradle 8.14.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties    # not committed
gradle assembleDebug
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. On the PC: `corepack gateway start` (prints a token, links the phone with `adb reverse`).
2. In the app: paste the token, tap **Save and test connection** (`Connected. Model: ...`).
3. Tap **Allow drawing over other apps**, then **Start the pet**. Drag it; tap it to chat.

## What the agent can do

| Tool | When the model uses it |
|---|---|
| `get_time` | you ask the time or date |
| `get_battery` | you ask about the phone's battery |
| `remember` | you tell it something worth keeping (name, tastes, plans) |
| `forget` | you ask it to forget something |
| `set_pet_mood` | the mood of the conversation changes; the pet's face follows |

Try: "what time is it?", "what's my battery?", "remember that I like jasmine tea",
"I'm so happy today!", then (after closing and reopening the chat) "what do I like to drink?".

Saved facts live on the phone in `facts.txt` (app-private) and go into every prompt. Tap
**What the pet remembers** on the main screen to see them or **Forget everything**.
Small grey notes in the chat ("used get_battery") show when a tool ran.

## Notes

- Gemini 3 and 2.5 require a "thought signature" to be sent back with tool calls. The app keeps it
  (`extra_content` on each tool call) and returns it untouched.
- Chat history for the model is kept in memory only (last 40 messages) and resets when the app
  process dies. Saved facts survive.
- If a tap on the pet does not open the chat, use the notification's action.
- Tests that need no phone: `jvm-tests/run.sh`.
