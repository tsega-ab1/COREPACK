#!/usr/bin/env bash
# Runs the agent tests on a plain JVM (no phone needed):
#   1. unit tests for the agent loop, memory, history trimming and tools
#   2. an end-to-end test: agent -> real COREPACK gateway -> a fake Gemini that rejects requests
#      with a missing thought signature or an empty tool schema
#
# Needs: kotlinc, java 17+, python3, a compiled org.json (the reference implementation from
# github.com/stleary/JSON-java, compiled to a class directory), and the COREPACK repo.
#   KOTLINC=~/tools/kotlinc/bin/kotlinc ORGJSON=~/tools/orgjson/out COREPACK_DIR=~/COREPACK ./run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
: "${ORGJSON:?set ORGJSON to the compiled org.json class directory}"
: "${COREPACK_DIR:?set COREPACK_DIR to your COREPACK checkout}"
AGENT="$HERE/../app/src/main/java/com/corepack/pet/agent"
CP="$ORGJSON:$KLIB"
OUT="$(mktemp -d)"; export COREPACK_HOME="$(mktemp -d)"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

echo "== compile"
"$KOTLINC" -cp "$CP" -jvm-target 17 -d "$OUT/unit" "$AGENT"/*.kt "$HERE/UnitTests.kt" 2>&1 | grep -E "^e:|error:" || true
"$KOTLINC" -cp "$CP" -jvm-target 17 -d "$OUT/e2e" "$AGENT"/*.kt "$HERE/E2E.kt" 2>&1 | grep -E "^e:|error:" || true

echo "== unit tests"
java -cp "$OUT/unit:$CP" UnitTestsKt

echo "== end-to-end"
python3 "$HERE/fake_gemini.py" & PIDS+=($!)
sleep 0.7
cd "$COREPACK_DIR"
FAKEKEY=sk-fake python3 -m corepack gateway init --provider custom --base-url http://127.0.0.1:9921 \
  --api-key-env FAKEKEY --model fake-gemini --rpm 0 >/dev/null
python3 -m corepack gateway start --port 8788 --no-adb >"$OUT/gateway.log" 2>&1 & PIDS+=($!)
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -s -m 1 localhost:8788/health >/dev/null && break; sleep 0.5; done
GW_TOKEN="$(python3 -m corepack gateway token | tr -d ' ')" java -cp "$OUT/e2e:$CP" E2EKt
