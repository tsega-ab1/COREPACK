# llama.cpp RPC benchmark: PC-only vs. PC + Android phone

**Report date:** 2026-10-05

## Summary

The measurements provided so far show that RPC offload was slower for the
Qwen3 1.7B Q4_K_M model on this PC-and-phone setup:

| Test | Prompt processing (`pp16`) | Token generation (`tg16`) |
|---|---:|---:|
| PC CPU only | 29.84 tokens/s | 12.54 tokens/s |
| PC + phone over RPC (`-ngl 99`) | 11.27 tokens/s | 5.92 tokens/s |
| RPC throughput relative to CPU-only | 37.8% | 47.2% |
| Throughput reduction with RPC | 62.2% | 52.8% |
| PC-only speed advantage | 2.65× | 2.12× |

For this small model, running entirely on the PC is faster. RPC does work—the
successful RPC run reports the `RPC` backend—but communication and phone-side
processing make this workload slower than keeping the model on the PC. RPC can
still be useful if a model does not fit in the PC's available memory; these
results do not test that use case.

## Recorded benchmark details

The commands and results below are transcribed from the terminal output shared
for this report. Each successful benchmark used one repetition (`-r 1`).

| Run | Command setup | Backend | Layers offloaded | `pp16` | `tg16` | Other recorded timing |
|---|---|---|---:|---:|---:|---|
| PC-only baseline | `-ngl 0 -p 16 -n 16 -r 1` | CPU | 0 | 29.84 tokens/s | 12.54 tokens/s | `real 0m5.184s` for the command |
| Wrong RPC address | `--rpc <INCORRECT_PHONE_IP>:50052 -ngl 0 -p 16 -n 16 -r 1` | CPU | 0 | 29.48 tokens/s | 12.58 tokens/s | `real 2m19.937s`; RPC connection failed |
| Working RPC address | `--rpc <PHONE_USB_IP>:50052 -ngl 99 -p 16 -n 16 -r 1` | RPC | 99 | 11.27 tokens/s | 5.92 tokens/s | Not recorded |

All three outputs identify the model as **Qwen3 1.7B Q4_K_M**, approximately
**1.03 GiB** and **1.72 billion parameters**. The CPU runs report **2
threads**. The RPC run reports `ngl 99`.

### Connection troubleshooting evidence

The PC's route table showed a directly connected USB-tethering subnet, with PC
address `<PC_USB_IP>` and phone gateway `<PHONE_USB_IP>`. Connecting to an
incorrect/stale phone address failed. Connecting to
`<PHONE_USB_IP>:50052` with `nc -vz` succeeded, and the following benchmark
reported backend `RPC`. This confirms that the corrected address reached the
RPC service and that the benchmark used RPC offload.

## Interpretation and limitations

- The fair headline comparison is the first CPU-only run against the successful
  RPC run. Their model, prompt length, generation length, and repetition count
  match as reported.
- The failed-address run is **not** an RPC measurement: it reports backend
  `CPU` and `ngl 0`. Its nearly identical throughput to the CPU-only run
  (`29.48/12.58` vs. `29.84/12.54`) is consistent with CPU execution.
- The wrong-address attempt reported 2m19.937s wall-clock time while the RPC
  connection failed; that elapsed time is not inference throughput and should
  not be compared with the successful benchmark's token rates.
- The tests used just one repetition each. They establish the observed result,
  but do not show run-to-run variability or control for device temperature,
  background load, power mode, or network conditions.
- No PC/phone CPU or GPU specifications, phone temperature, RPC server logs,
  RPC-run wall-clock time, or intermediate layer-offload results were included.
  Collecting these would make a follow-up report more reproducible and help
  explain the performance gap.

## Commands to collect repeatable results

Run these on the PC from the repository checkout, with the phone's RPC server
already running on port `50052`. The commands make five repetitions for each
configuration and save the raw output under `report/runs/`.

```bash
cd ~/COREPACK
mkdir -p report/runs
B=~/llama.cpp/build/bin/llama-bench
M=~/models/Qwen3-1.7B-Q4_K_M.gguf
PHONE="PHONE_USB_IP"  # Replace with the phone's current USB-tethering IP.

# Confirm the route and that the phone's RPC port is reachable.
ip route get "$PHONE"
nc -vz "$PHONE" 50052

# Repeat the PC-only test.
"$B" -m "$M" -ngl 0 -p 16 -n 16 -r 5 \
  | tee report/runs/pc-cpu-pp16-tg16.txt

# Repeat the all-layers RPC test.
"$B" -m "$M" --rpc "$PHONE:50052" -ngl 99 -p 16 -n 16 -r 5 \
  | tee report/runs/rpc-ngl99-pp16-tg16.txt
```

To see whether a partial split performs better, try intermediate `-ngl` values
as separate runs. Keep all other settings the same and save each output:

```bash
"$B" -m "$M" --rpc "$PHONE:50052" -ngl 10 -p 16 -n 16 -r 5 \
  | tee report/runs/rpc-ngl10-pp16-tg16.txt
"$B" -m "$M" --rpc "$PHONE:50052" -ngl 20 -p 16 -n 16 -r 5 \
  | tee report/runs/rpc-ngl20-pp16-tg16.txt
```

`-ngl 99` requests as many layers as possible for offload; the actual split can
depend on the model and backend. Benchmarking more than one prompt/generation
length can also show whether the conclusion changes with workload size. For
example, change `-p 16 -n 16` to `-p 128 -n 128`, label the output files
accordingly, and use those same values for CPU and RPC.

## Commands to collect hardware and build details

Run on the PC:

```bash
{
  echo "=== PC ==="
  date -Is
  uname -a
  lscpu
  free -h
  lspci -nn | grep -Ei 'vga|3d|display' || true
  echo "=== llama.cpp ==="
  ~/llama.cpp/build/bin/llama-bench --version
  git -C ~/llama.cpp rev-parse --short HEAD
  grep -E 'GGML_RPC|CMAKE_BUILD_TYPE' ~/llama.cpp/build/CMakeCache.txt
} | tee report/runs/pc-system.txt
```

Run in Termux on the phone:

```bash
{
  echo "=== Android phone (Termux) ==="
  date
  getprop ro.product.model
  getprop ro.soc.model
  getprop ro.build.version.release
  getprop ro.product.cpu.abi
  uname -a
  nproc
  free -h
  echo "=== llama.cpp ==="
  ~/llama.cpp/build/bin/rpc-server --version
  git -C ~/llama.cpp rev-parse --short HEAD
  grep -E 'GGML_RPC|CMAKE_BUILD_TYPE' ~/llama.cpp/build/CMakeCache.txt
} | tee ~/phone-system.txt
```

If `rpc-server --version` is unsupported by that build, omit that line; the
llama.cpp commit and CMake settings still identify the build. For a useful
follow-up comparison, share the repeat-run benchmark outputs plus
`report/runs/pc-system.txt` and `phone-system.txt`. Also note whether the phone
was cool or warm and whether it was charging during each run.
