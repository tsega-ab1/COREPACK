# Phase 0 · step by step

## Before you start
- Data-capable USB cable (many are charge-only). Phone and PC both have `corepack` installed.
- Termux: `termux-wake-lock` so Android doesn't throttle the worker when the screen sleeps.

## Run order
| # | Device | Command | Expect |
|---|---|---|---|
| 1 | phone | enable **USB tethering** | PC gets a new interface |
| 2 | PC | `corepack ip` | row marked **USB link** and a phone address (usually 192.168.42.129) |
| 3 | phone | `corepack worker` | "Ready: 8 cores ..." |
| 4 | PC | `corepack discover` | finds the phone; answer **y** to save |
| 5 | PC | `corepack net` | 200-400 Mbit/s, ~1 ms latency |
| 6 | PC | `corepack bench --size medium` | speedup > 1.00x, "results identical" |
| 7 | PC | `corepack bench --task all --local-cores 2` | phone clearly helps |

## Results log (fill in, then commit)
| Date | Link Mbit/s | Task | PC alone | PC+phone | Speedup |
|---|---|---|---|---|---|
| | | | | | |

## Troubleshooting
- **No USB link in `corepack ip`:** tethering toggled off after replugging; turn it on again. Try another port/cable.
- **Termux `ip` permission denied:** expected on newer Android; corepack falls back to `ifconfig` / a socket probe automatically.
- **Discover finds nothing:** check the worker is running; try `corepack config peers 192.168.42.129`.
- **Phone IP differs:** Samsung sometimes uses another 192.168.x subnet; use the address shown by `corepack ip`.
- **Speedup < 1x:** job too small. Use `--size large`; USB/HTTP overhead is fixed per chunk.
- **Worker pool says "thread":** processes unavailable on that device; speed on the phone will be poor (one core). Tell me and we'll adapt.
- **Worker slows after a minute:** thermal throttling or battery saver. Check the dashboard `busy` count and keep the phone cool.

## LLM split (optional)
1. Both devices: `corepack llm build` (phone build is slow).
2. Phone: `corepack llm serve`. PC: `corepack llm run --model <file>.gguf`.
