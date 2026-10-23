#!/usr/bin/env bash
# usage: repro.sh <modulesDir> [apiBase]   e.g. repro.sh G:/Aywen/Minecraft/.sandbox-coder/modules http://127.0.0.1:8820
# Simulates the CLAUDE.md dev loop: save the file, then (D seconds later, like an LLM's next tool call) POST /reload.
set -u
M="$1"; API="${2:-http://127.0.0.1:8765}"; D="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$M/dbl-reload" && cp "$D/dbl-reload/DblReloadModule.java" "$M/dbl-reload/"
curl -s -X POST "$API/api/modules/dbl-reload/reload" > /dev/null; sleep 4
gen() { curl -s "$API/api/modules/dbl-reload" | grep -oE '"generation":[0-9]+' | cut -d: -f2; }
for delay in 0 1.0 1.5 2.0 2.5 3.5; do
  g0=$(gen); echo "// save $delay" >> "$M/dbl-reload/DblReloadModule.java"; sleep "$delay"
  r=$(curl -s -X POST "$API/api/modules/dbl-reload/reload" | grep -oE '"generation":[0-9]+' | cut -d: -f2)
  sleep 4; g1=$(gen)
  echo "delay=${delay}s before=v$g0 reload-returned=v$r after=v$g1  -> $((g1 - g0)) generation(s) for ONE save"
done
