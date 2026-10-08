#!/usr/bin/env bash
# Builds VibeCraft (OUT_DIR, default out-ai) and runs the AI job runner harness (no Minecraft server needed).
# Usage: vibecraft-plugin/dev/run-harness.sh [--fake | --real-negative | --real]
#   --fake           FakeClaude stand-in (default): free, deterministic, tests queue/stop/timeout/resume/parallel
#   --real-negative  real claude.exe, only paths that need no login (auth gate, unknown --resume, stop) - free
#   --real           real claude.exe + haiku (requires "claude auth login" once) - costs a few cents
# The fake VibeCraft API listens on 127.0.0.1:8767.
set -euo pipefail
cd "$(dirname "$0")/.."

winpath() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }
ROOT="$(winpath ..)"
if [ ! -d "$ROOT/runtime" ]; then
  ROOT="$(winpath "$(git rev-parse --git-common-dir)/..")"
fi
JDK_BIN="$ROOT/runtime/jdk-25/bin"
OUT="${OUT_DIR:-out-ai}"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
GSON="$(find "$ROOT/server/libraries" -name 'gson-2*.jar' | head -1)"
if [ -z "$GSON" ]; then
  echo "gson jar not found under $ROOT/server/libraries" >&2
  exit 1
fi
echo "gson: $GSON"

OUT_DIR="$OUT" ./build.sh
rm -rf "$OUT/dev-classes"
mkdir -p "$OUT/dev-classes"
# Absolute Windows paths + no MSYS argument conversion (a "rel;G:/abs" classpath gets mangled otherwise).
OUTW="$(winpath "$OUT")"
export MSYS_NO_PATHCONV=1
"$JDK_BIN/javac" -encoding UTF-8 --release 25 -d "$OUTW/dev-classes" -cp "$OUTW/classes$SEP$GSON" dev/AiRunnerHarness.java dev/FakeClaude.java
exec "$JDK_BIN/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUTW/dev-classes$SEP$OUTW/classes$SEP$GSON" AiRunnerHarness "$@"
