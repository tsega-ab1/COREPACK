# COREPACK · Phase 0

Prove the COREPACK idea with hardware you already own: a **Linux PC** and an **Android phone
(Termux)** joined by one USB cable, sharing a real workload. Pure Python standard library,
no pip installs, same code on both devices.

```
 Phone (Termux)                     PC (Linux Mint)
 ┌───────────────┐   USB tethering  ┌──────────────────┐
 │ corepack      │◄────────────────►│ corepack bench   │
 │   worker      │   (usb0 / rndis) │ splits the job   │
 └───────────────┘                  └──────────────────┘
   8 phone cores  ──── one shared queue of chunks ────  PC cores
```

## 1. Get it on both devices

```bash
# once, from your PC, in this folder
git init && git add . && git commit -m "COREPACK phase 0"
git remote add origin <your-repo-url> && git push -u origin main

# on BOTH the PC and the phone (Termux)
git clone <your-repo-url> ~/COREPACK
cd ~/COREPACK && ./install.sh
corepack            # opens the menu
```

Update later with `git pull`. Nothing else to reinstall.

## 2. Run the experiment

1. **Phone:** Settings → Connections → Mobile Hotspot and Tethering → **USB tethering ON**
   (cable plugged into the PC). In Termux: `termux-wake-lock`, then `corepack worker`.
2. **PC:** `corepack doctor` → `corepack ip` (shows the phone's address) → `corepack net`
   (link speed) → `corepack bench`.
3. Commit the saved result: `git add results && git commit -m "bench" && git push`.

Success = the **"local + worker"** row is faster than **"local only"**, with identical results.

## Commands

| Command | Run on | What it does |
|---|---|---|
| `corepack` | both | Interactive menu (★ marks the next step for your device) |
| `corepack doctor` | both | Health check with plain-English fixes |
| `corepack ip` | both | Interfaces, USB link, phone address |
| `corepack worker` | phone | Offers its cores; live status line |
| `corepack discover` | PC | Scans the USB subnet for workers, saves the peer |
| `corepack net` | PC | Latency + upload/download Mbit/s over the cable |
| `corepack bench [--task primes\|hashchain\|mandel\|all] [--size small\|medium\|large]` | PC | Local vs local+phone, verifies results match |
| `corepack llm build\|serve\|run` | both | Split an LLM across both devices (llama.cpp RPC) |
| `corepack config [key value]` | both | Settings, e.g. `corepack config peers 192.168.42.129` |

Tip: `--local-cores 2` on the PC makes the phone's contribution easier to see.

## What this proves (and doesn't)

- **Bench = task parallelism.** Independent chunks go to whichever core is free. Speedup is real
  if the phone's cores add throughput. It's the honest test of your Phase 0 success criterion.
- **LLM split = tensor/layer placement.** Lets you run models too big for one device; it will
  usually not be faster than the PC alone for a model that already fits. Don't expect speedup there.
- Ray is not used: it has no Termux (Bionic libc) wheels. Same `@remote`-style idea, built on stdlib.

## Layout

```
bin/corepack        launcher (symlinked onto PATH by install.sh)
corepack/
  cli.py            commands + menu          ui.py        colours, tables, progress
  worker.py         HTTP worker              client.py    talks to a worker
  scheduler.py      work-stealing queue      tasks.py     the compute jobs
  pool.py           local process pool       netinfo.py   USB link + discovery
  bench.py nettest.py llm.py doctor.py config.py peers.py
tests/              python3 -m unittest discover -s tests
docs/PHASE0.md     step-by-step + troubleshooting
results/            benchmark JSON (commit these)
```

## Security note

The worker only runs the fixed tasks in `tasks.py` (no arbitrary code), but it listens on all
interfaces, so on Wi-Fi anyone on the network could use your CPU. For the cable-only test either
use it on a trusted network or set a shared secret on both devices: `corepack config token mysecret`.
