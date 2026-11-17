# Hosting the gateway for testers

Testers install the APK, paste an invite code, and chat. Their messages go phone -> your hosted gateway -> Gemini or Grok.
Tool servers (MCP) are never started on a hosted gateway, and testers cannot reach your named engines.

## What a tester can do

- **Use your shared key.** Limited per invite (`COREPACK_INVITE_RPM` requests/min, `COREPACK_INVITE_DAILY` messages/day).
  When the day's allowance is used, the app shows a message telling them to add their own key or come back tomorrow.
- **Bring their own key** (Gemini or Grok) in the app. It is sent with each of their messages, used for that one request,
  never stored or logged on the server, and scrubbed from error text. It does pass through your server on the way to the
  engine, so say so to testers. They can pick only `gemini` or `xai` by name; they can never give an address.

## Settings (environment variables or Space secrets)

| Name | Meaning |
|---|---|
| `COREPACK_TOKEN` | Your owner token. Long and random. Required. |
| `COREPACK_PROVIDER` | `gemini` or `xai`. |
| `COREPACK_MODEL` | Model name for the shared key. |
| `COREPACK_API_KEY` | Your shared key. Leave unset to make testers bring their own. |
| `COREPACK_INVITES` | Output of `corepack gateway invite export`. Plain codes also work: `{"alice":"cpt_..."}`. |
| `COREPACK_INVITE_RPM`, `COREPACK_INVITE_DAILY` | Default limits per invite (0 = unlimited). |
| `COREPACK_RPM` | Total requests per minute on your shared key, across everyone. Set it below your engine's limit. |
| `COREPACK_MAX_TOKENS` | Cap on one reply's length (default 1024). |
| `COREPACK_BYO_MODELS` | Optional JSON, model to use with a tester's key, e.g. `{"gemini":"..."}`. |

## 1. Make invite codes (on your PC)

    corepack gateway invite add alice --daily 30
    corepack gateway invite add bob
    corepack gateway invite export        # copy this line into COREPACK_INVITES

A code is shown once; only its hash is saved. Revoke one with `corepack gateway invite revoke alice`
(then update `COREPACK_INVITES` on the host).

## 2. Pick where it runs

The app needs **HTTPS**, and the gateway streams replies (server-sent events), so the host must allow that.

- **Hugging Face Space (free, Docker).** Create a Docker Space. Put `Dockerfile`, the `corepack/` folder and
  `deploy/hf-space/README.md` (as `README.md`) in the Space's git repo. Add the settings above under
  Settings -> Variables and secrets. The Space is served at `https://<you>-<space>.hf.space`, so the app URL is
  `https://<you>-<space>.hf.space/v1`. Free Spaces go to sleep when idle (the first message after a pause is slow) and
  have no persistent disk, which is why invites live in `COREPACK_INVITES`. A public Space shows your code, not your secrets.
- **A small VPS.** `cd deploy`, copy `.env.example` to `.env`, fill it in, point your domain at the server, then
  `docker compose up -d`. Caddy gets the HTTPS certificate automatically. Always on, and you control it.
- **Your own PC with a named Cloudflare Tunnel.** Needs a free Cloudflare account and a domain on it. Do not use a
  quick tunnel (`trycloudflare.com`): Cloudflare documents that quick tunnels do not support server-sent events,
  so replies would not stream. The PC must stay on.

Check it: `curl https://YOUR-URL/health` should print `{"ok": true, ...}`, and
`curl -H "Authorization: Bearer cpt_..." https://YOUR-URL/v1/models` should list the model.

## 3. Build the APK

On your PC (Android Studio's SDK installed):

    cd pet-android
    ./gradlew assembleDebug -PgatewayUrl=https://YOUR-URL/v1
    # optional: bake one shared invite in so testers type nothing
    ./gradlew assembleDebug -PgatewayUrl=https://YOUR-URL/v1 -PinviteCode=cpt_xxxx

The file is `app/build/outputs/apk/debug/app-debug.apk`. A baked-in invite can be pulled out of the APK by anyone who has
it, so give that one low limits and revoke it when the test ends. Per-person invites are safer.

## 4. What to tell testers

- Install the APK (Android asks to allow installs from this source).
- Open the app, paste the invite code, tap **Save and test connection**, then allow drawing over other apps and start the pet.
- Their messages are processed by your server and by Google or xAI. On a free Gemini key, check Google's terms for how
  prompts may be used. Do not type anything private.
- Some phones need "Allow restricted settings" (App info -> three dots) before the screen-reading accessibility feature can be switched on.

## Costs and abuse

Your shared key is the only thing at risk. The limits above cap each invite, `COREPACK_RPM` caps the total, and revoking
an invite stops that tester. Watch your engine's usage page during the test.
