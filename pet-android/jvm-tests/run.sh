#!/usr/bin/env bash
# Tests for the phone app's code, on a plain JVM (no phone needed):
#   1. agent unit tests: loop, memory, history trimming, tools
#   2. assistant tests: reminders, tasks, notes, phone-action checks, weather and Wikipedia parsing, nudges, prompt
#   3. Mochi character tests: states, motion timings, cross-fades, renderer sanity
#   4. end-to-end: agent -> real COREPACK gateway -> a fake Gemini that rejects requests with a
#      missing thought signature or an empty tool schema
#
#   ./run.sh            run all four
#   ./run.sh preview    render the character (contact sheets + frames) and stop
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
PET="$HERE/../app/src/main/java/com/corepack/pet"
AGENT="$PET/agent"
CP="$ORGJSON:$KLIB"
OUT="$(mktemp -d)"; export COREPACK_HOME="$(mktemp -d)"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

compile() { local dest="$1"; shift; "$KOTLINC" -cp "$CP" -jvm-target 17 -d "$OUT/$dest" "$@" 2>&1 | grep -E "^e:|error:" || true; }

if [ "${1:-}" = "preview" ]; then
  echo "== preview"
  compile preview "$PET/Phase.kt" "$AGENT/Types.kt" "$PET"/mochi/*.kt "$HERE/Java2DGfx.kt" "$HERE/MochiPreview.kt"
  DEST="${PREVIEW_DIR:-$OUT/preview-out}"
  java -Djava.awt.headless=true -cp "$OUT/preview:$CP" MochiPreviewKt "$DEST" frames
  echo "written to $DEST (video: ffmpeg -framerate 30 -i $DEST/frames/f%05d.png -pix_fmt yuv420p mochi.mp4)"
  exit 0
fi

echo "== compile"
compile unit "$AGENT"/*.kt "$HERE/UnitTests.kt"
compile assist "$AGENT"/*.kt "$HERE/AssistantTests.kt"
compile mochi "$PET/Phase.kt" "$AGENT/Types.kt" "$PET"/mochi/*.kt "$HERE/MochiTests.kt"
compile e2e "$AGENT"/*.kt "$HERE/E2E.kt"

echo "== agent unit tests"
java -cp "$OUT/unit:$CP" UnitTestsKt

echo "== assistant tests"
java -cp "$OUT/assist:$CP" AssistantTestsKt

echo "== mochi character tests"
java -cp "$OUT/mochi:$CP" MochiTestsKt

echo "== end-to-end"
python3 "$HERE/fake_gemini.py" & PIDS+=($!)
sleep 0.7
cd "$COREPACK_DIR"
FAKEKEY=sk-fake python3 -m corepack gateway init --provider custom --base-url http://127.0.0.1:9921 \
  --api-key-env FAKEKEY --model fake-gemini --rpm 0 >/dev/null
python3 -m corepack gateway start --port 8788 --no-adb >"$OUT/gateway.log" 2>&1 & PIDS+=($!)
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -s -m 1 localhost:8788/health >/dev/null && break; sleep 0.5; done
GW_TOKEN="$(python3 -m corepack gateway token | tr -d ' ')" java -cp "$OUT/e2e:$CP" E2EKt
