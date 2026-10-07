#!/usr/bin/env bash
# Builds VibeCraft.jar against the Paper server's own libraries (no Gradle/Maven needed).
# Usage: ./build.sh            -> builds out/VibeCraft.jar
#        ./build.sh --deploy   -> also copies it to <root>/server/plugins/VibeCraft.jar
# Env:   OUT_DIR=out-xyz       -> build into another directory (parallel builds)
set -euo pipefail
cd "$(dirname "$0")"

winpath() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }

ROOT="$(winpath ..)"
# In a git worktree, runtime/ and server/ only exist in the main checkout.
if [ ! -d "$ROOT/runtime" ]; then
  ROOT="$(winpath "$(git rev-parse --git-common-dir)/..")"
fi
JDK_BIN="$ROOT/runtime/jdk-25/bin"
SERVER="$ROOT/server"
OUT="${OUT_DIR:-out}"

if [ ! -d "$SERVER/libraries" ]; then
  echo "server/libraries missing: run the Paper server once first" >&2
  exit 1
fi

# Windows java uses ';' as classpath separator.
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
CP="$(find "$SERVER/libraries" "$SERVER/versions" -name '*.jar' | tr '\n' "$SEP")"

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
find src/main/java -name '*.java' > "$OUT/sources.txt"

"$JDK_BIN/javac" -encoding UTF-8 --release 25 -g -parameters -Xlint:all,-serial,-processing,-options,-classfile \
  -d "$OUT/classes" -cp "$CP" "@$OUT/sources.txt"

cp -r src/main/resources/. "$OUT/classes/"
"$JDK_BIN/jar" --create --file "$OUT/VibeCraft.jar" -C "$OUT/classes" .
echo "Built $OUT/VibeCraft.jar"

if [ "${1:-}" = "--deploy" ]; then
  cp "$OUT/VibeCraft.jar" "$SERVER/plugins/VibeCraft.jar"
  echo "Deployed to $SERVER/plugins/VibeCraft.jar"
fi
