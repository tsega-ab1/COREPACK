# CorePack Pet (Android)

A pet (a small glossy character called Mochi) that floats over other apps; tap it to chat. It talks to the COREPACK gateway on your PC over
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

## What the pet can do

It is an assistant for the phone, not only a chat. Each group can be switched off in the app
(**What the pet may do**). Details, permissions and what leaves the phone: `../docs/PHONE_PET.md`.

Try, one at a time:

| Say or do | What should happen |
|---|---|
| "remind me in 2 minutes to drink water" | after 2 minutes the pet turns amber, hops, shows a speech bubble and a notification |
| "what's on my reminders?" / "cancel the water reminder" | lists / cancels it |
| "add buy milk to my to-do list", then "what's on my list?", "I bought the milk" | a to-do list kept on the phone |
| "save a note: the lab code is on the blue shelf", later "what did I note about the lab?" | a notebook you can search |
| "set an alarm for 6:30" / "timer for 10 minutes" | the clock app gets it |
| "open Telegram" / "show me the nearest pharmacy on the map" | the app opens |
| "turn the flashlight on" | torch on |
| "draft a text to 0911000000 saying I'm running late" | the messages app opens with the draft; you press send |
| "what's the weather in Addis Ababa?" / "tell me about injera" | weather and a short Wikipedia summary |
| select text in another app, choose **Ask pet**, tap **Summarize** | the text is summarized in the chat |
| (battery nudge) `adb shell dumpsys battery unplug; adb shell dumpsys battery set level 14` | a bubble: "Your battery is at 14%". Undo with `adb shell dumpsys battery reset` |

The main screen has **Reminders, tasks and notes** and **What the pet remembers** to see and clear what
is stored. Small grey notes in the chat ("used set_reminder") show when a tool ran.

## The character

Drawn in code from measurements of a design reference: see `../docs/MOCHI_DESIGN.md` for the analysis and
the attribution note (read it before publishing). The look lives in `app/src/main/java/com/corepack/pet/mochi/`.

## Notes

- Gemini 3 and 2.5 require a "thought signature" to be sent back with tool calls. The app keeps it
  (`extra_content` on each tool call) and returns it untouched.
- Chat history for the model is kept in memory only (last 40 messages) and resets when the app
  process dies. Saved facts survive.
- If a tap on the pet does not open the chat, use the notification's action.
- Tests that need no phone: `jvm-tests/run.sh`.
