# Phone pet

A small animated pet that floats over other apps on an Android phone. Tap it to chat.
It is also a small agent: it can use tools, remember things, and show its mood.
The answers come from an AI model, but the phone never holds an API key.

```
Phone (pet app)  --USB cable-->  COREPACK gateway (PC)  -->  Gemini / Grok / local model
   token only                      API keys live here
```

## Parts

| Part | Where | What it does |
|---|---|---|
| Gateway | `corepack/gateway.py` | OpenAI-compatible server on the PC: checks the phone's token, rate-limits, retries, streams replies, and sends status events |
| Pet app | `pet-android/` | Kotlin app: draggable overlay pet, chat sheet, and the agent |
| Docs | `docs/GATEWAY.md` | Gateway setup, protocol, security notes |

## Run it

```bash
sudo apt install adb
./bin/corepack gateway init     # pick engine, paste API key, pick model
./bin/corepack gateway test     # confirm the key works
./bin/corepack gateway start    # keep running; also runs adb reverse
cd pet-android && gradle assembleDebug
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone: paste the token, tap "Save and test connection", allow drawing over other apps,
tap "Start the pet", then tap the pet to chat. Full steps are in `pet-android/README.md`.

## The agent

Each message runs a loop: ask the model, run any tools it calls, give it the results, repeat until it
answers in text (at most 5 rounds).

| Tool | Does |
|---|---|
| `get_time`, `get_battery` | read the clock and the phone's battery |
| `remember`, `forget` | save and delete short facts about the user; saved facts go into every prompt |
| `set_pet_mood` | change the pet's face; it is fire-and-forget, so it costs no extra model round |

Facts are stored on the phone only (`facts.txt`). The model is told never to save passwords or
payment details.

Gemini 3 and 2.5 reject a follow-up request unless the tool call's `thought_signature` (carried in
`extra_content`) is sent back. The app echoes it untouched, wherever Gemini placed it.

## What the pet shows

| Pose | Meaning |
|---|---|
| Breathing, blinking | idle |
| Sways with a dot | queued behind the rate limit |
| Sways with dots | request sent, waiting for the first word |
| Quick sway with `~` | running a tool |
| Bounces, mouth moving | the reply is streaming in |
| Happy eyes and a `*` | reply finished |
| Shaking with a `?` | the engine returned an error |
| Droops with an `x` | the phone can't reach the gateway |
| Slow, with `zz` | the engine is rate-limiting |

When calm, the face follows the mood the model set: happy and excited smile, sad droops with a frown,
curious tilts with eyes up, sleepy has flat eyes and a `z`. A mood fades to neutral after 5 minutes.

## Known limits

- The pet is a simple drawn blob. Real artwork comes later.
- On some Android versions tapping the pet may not open the chat; use the notification's chat action.
- The model's conversation (last 40 messages) lives in memory and resets when the app restarts;
  saved facts persist.
- The token is kept in the app's private storage, not the Android Keystore.
- Not done yet: the pet speaking first (proactive messages), more tools, a mood-aware long-term memory.
- Agent tests that need no phone: `pet-android/jvm-tests/run.sh`.
