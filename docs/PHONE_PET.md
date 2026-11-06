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
answers in text (at most 5 rounds). The prompt carries the current date and time, so "remind me at 5"
works. Each group of abilities below can be switched on or off in the app ("What the pet may do"); the
model only sees tools that are on.

| Group | Tools | Needs |
|---|---|---|
| Always | `get_time`, `get_battery`, `remember`, `forget`, `set_pet_mood` | nothing |
| Reminders | `set_reminder`, `list_reminders`, `cancel_reminder` | notification permission. The pet hops, shows a speech bubble and a notification when one is due; reminders are set again after a reboot |
| Tasks and notes | `add_task`, `list_tasks`, `complete_task`, `save_note`, `search_notes` | nothing |
| Phone actions | `set_alarm`, `set_timer`, `open_app`, `open_url`, `open_maps`, `flashlight`, `compose_sms`, `dial` | nothing at runtime. `compose_sms` and `dial` only open the messages app or dialer with the text or number filled in: you press send or call |
| Weather and facts | `get_weather`, `wiki_summary` | internet |
| Nudges | none (the pet speaks up by itself) | currently: a low-battery warning, at most 4 a day, never between 22:00 and 07:00 |

Other apps can hand text to the pet: select text and choose **Ask pet** in the selection menu, or use
**Share > Ask pet**. The chat opens with one-tap actions: Summarize, Translate, Explain, Save as note,
Draft reply.

## Where your data goes

- **To the model (through your gateway, then Google for Gemini):** everything you type, the shared text,
  and everything a tool returns. That includes your task list, notes and reminders when the model reads
  them, the battery level, and the names of apps when it opens one. On Gemini's free tier, prompts may be
  used to improve Google's products, so keep sensitive things out of notes and tasks, or switch the
  groups off.
- **To other services:** a weather question sends the city to Open-Meteo; a lookup sends the topic to
  Wikipedia. Switch off "Weather and facts" to stop that.
- **Stays on the phone:** saved facts, reminders, tasks and notes live in the app's private storage
  (not encrypted, not backed up). "What the pet remembers" and "Reminders, tasks and notes" show and
  clear them.
- **Never done without you:** messages are not sent and calls are not placed; the pet only opens the
  app with a draft.

## What the pet shows

The pet is a small glossy character called Mochi (see `docs/MOCHI_DESIGN.md`). What the agent is doing
picks its look:

| Pet is | Looks like |
|---|---|
| idle | white, breathing, blinking |
| waiting behind the rate limit | amber, sleepy dash eyes, orange dot |
| waiting for the first word | violet, eyes glancing up and aside, three dots |
| running a tool | indigo, eyes scanning left and right, three dots |
| streaming the reply | blue, three pulsing dots |
| reply finished | green, happy eyes, a hop, sparkles |
| error | pink-red, dash eyes, a short shake, red dot |
| gateway unreachable | grey, eyes closed, floating z |
| engine throttling | pink, spiral eyes, wobble |
| a reminder is due, or a nudge | alert: amber, hopping, with a speech bubble |
| overlay just started | waves hello for 2.6 s |

When calm, the mood the model sets changes the idle look: happy (smiling, blush), sad (grey-blue,
drooping), curious (cyan, tilted head, "?"), sleepy (eyes closed, z), excited (dancing). A mood fades
to neutral after 5 minutes.

## Known limits

- The pet is a simple drawn blob. Real artwork comes later.
- On some Android versions tapping the pet may not open the chat; use the notification's chat action.
- The model's conversation (last 40 messages) lives in memory and resets when the app restarts;
  saved facts persist.
- The token is kept in the app's private storage, not the Android Keystore.
- Not done yet: calendar, contacts and notifications (they need sensitive permissions), voice in and out,
  sending a photo, more nudges (a morning summary, a check-in after a long silence).
- Agent tests that need no phone: `pet-android/jvm-tests/run.sh`.
