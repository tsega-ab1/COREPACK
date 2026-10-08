# Phone pet

A small animated pet that floats over other apps on an Android phone. Tap it to chat.
The answers come from an AI model, but the phone never holds an API key.
## Parts

| Part | Where | What it does |
|---|---|---|
| Gateway | `corepack/gateway.py` | OpenAI-compatible server on the PC: checks the phone's token, rate-limits, retries, streams replies, and sends status events |
| Pet app | `pet-android/` | Kotlin app: draggable overlay pet plus a chat sheet |
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

## What the pet shows

| Pose | Meaning |
|---|---|
| Breathing, blinking | idle |
| Sways with a dot | queued behind the rate limit |
| Sways with dots | request sent, waiting for the first word |
| Bounces, mouth moving | the reply is streaming in |
| Happy eyes and a `*` | reply finished |
| Shaking with a `?` | the engine returned an error |
| Droops with an `x` | the phone can't reach the gateway |
| Slow, with `zz` | the engine is rate-limiting |

## Known limits

- The pet is a simple drawn blob. Real artwork comes later.
- On some Android versions tapping the pet may not open the chat; use the notification's chat action.
- Chat history lives in memory and resets when the app restarts.
- The token is kept in the app's private storage, not the Android Keystore.
- Planned next: the Muse agent framework (tools, memory, mood).
