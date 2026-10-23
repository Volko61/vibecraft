#!/usr/bin/env bash
# usage: scen.sh <scenario.mjs> [timeoutSec]   (botd.mjs must be running from this directory)
# Copies the scenario into ./queue, waits for botd.mjs to run it, prints the JSON result.
D="$(cd "$(dirname "$0")" && pwd)"
n="$(basename "$1" .mjs)-$(date +%s%N)"
mkdir -p "$D/queue"
cp "$1" "$D/queue/$n.mjs"
t=${2:-60}
for i in $(seq 1 $((t*5))); do
  if [ -f "$D/queue/$n.out.json" ]; then cat "$D/queue/$n.out.json"; exit 0; fi
  timeout 0.2 tail -0f /dev/null 2>/dev/null
done
echo "TIMEOUT waiting for $n"; exit 1
