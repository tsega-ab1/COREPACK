#!/usr/bin/env bash
# Runs the pet's tools against the REAL engine behind your gateway and prints how well it chooses, chains, recovers
# and reports. The tools are real in name and description but return made-up results, so nothing is changed.
#
#   GATEWAY_TOKEN=$(./bin/corepack gateway token | tr -d ' \n') \
#   KOTLINC=~/tools/kotlinc/bin/kotlinc ORGJSON=~/tools/json.jar ./eval.sh
#   (optional: GATEWAY_URL=http://192.168.1.20:8787/v1  ENGINE=fast  EVAL_RUNS=3)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
: "${ORGJSON:?set ORGJSON to org.json (a class directory or the json jar)}"
: "${GATEWAY_TOKEN:?set GATEWAY_TOKEN (corepack gateway token)}"
AGENT="$HERE/../app/src/main/java/com/corepack/pet/agent"
CP="$ORGJSON:$KLIB"
OUT="$(mktemp -d)"
"$KOTLINC" -cp "$CP" -jvm-target 17 -d "$OUT" "$AGENT"/*.kt "$HERE/LiveEval.kt" 2>&1 | grep -E "^e:|error:" || true
java -cp "$OUT:$CP" LiveEvalKt
