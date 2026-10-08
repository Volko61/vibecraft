#!/usr/bin/env bash
# Builds VibeReconnect (Velocity plugin) with the portable JDK 25, against proxy/velocity/velocity.jar.
# Usage: proxy/vibe-reconnect/build.sh [--deploy]   (--deploy copies the jar to proxy/velocity/plugins/)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Windows JDK + Git Bash: give the JDK mixed-style paths (G:/...) instead of /g/...
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
PROXY="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$PROXY/.." && pwd)"
JDK="${JDK:-$ROOT/runtime/jdk-25}"
JAVAC="$JDK/bin/javac"; JAR="$JDK/bin/jar"
[ -x "$JAVAC" ] || [ -x "$JAVAC.exe" ] || { echo "javac introuvable dans $JDK" >&2; exit 1; }
VELOCITY_JAR="${VELOCITY_JAR:-$PROXY/velocity/velocity.jar}"
[ -f "$VELOCITY_JAR" ] || { echo "Velocity introuvable: $VELOCITY_JAR (lance proxy/setup-proxy.ps1)" >&2; exit 1; }
OUT="$HERE/build"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
( cd "$HERE" && find src/main/java -name '*.java' ) > "$OUT/sources.txt"
( cd "$HERE" && "$JAVAC" --release 21 -encoding UTF-8 -proc:none -Xlint:none -g     -cp "$(winpath "$VELOCITY_JAR")" -d build/classes @build/sources.txt )
cp -r "$HERE/src/main/resources/." "$OUT/classes/"
rm -f "$OUT/vibe-reconnect.jar"
( cd "$HERE" && "$JAR" --create --file build/vibe-reconnect.jar -C build/classes . )
echo "OK: $OUT/vibe-reconnect.jar"
if [ "${1:-}" = "--deploy" ]; then
  mkdir -p "$PROXY/velocity/plugins"
  cp "$OUT/vibe-reconnect.jar" "$PROXY/velocity/plugins/vibe-reconnect.jar"
  echo "Deployed: $PROXY/velocity/plugins/vibe-reconnect.jar"
fi
