#!/usr/bin/env bash
# Démarre le serveur Paper 26.3 avec le JDK 25 portable du projet (Linux / macOS).
# Usage : ./scripts/start-server.sh            (mémoire max : $VIBECRAFT_MEMORY, 4G par défaut)
# Équivalent de start-server.ps1 ; c'est aussi ce que lance le service systemd (deploy/linux/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="$ROOT/runtime/jdk-25/bin/java"
SERVER="$ROOT/server"
MEMORY="${VIBECRAFT_MEMORY:-4G}"

[ -x "$JAVA" ] || { echo "JDK 25 introuvable : $JAVA (lancer deploy/linux/install.sh)" >&2; exit 1; }
[ -f "$SERVER/paper.jar" ] || { echo "paper.jar introuvable dans $SERVER" >&2; exit 1; }

# Installe la dernière version compilée du plugin (vibecraft-plugin/out) si elle est plus récente.
BUILT="$ROOT/vibecraft-plugin/out/VibeCraft.jar"
DEPLOYED="$SERVER/plugins/VibeCraft.jar"
if [ -f "$BUILT" ] && { [ ! -f "$DEPLOYED" ] || [ "$BUILT" -nt "$DEPLOYED" ]; }; then
  mkdir -p "$SERVER/plugins"
  cp "$BUILT" "$DEPLOYED"
  echo "  Plugin VibeCraft mis a jour."
fi

echo ""
echo "  VibeCraft - serveur Minecraft live-coding"
echo "  Connexion Minecraft : <adresse du serveur> (port 25565)"
echo "  Dashboard           : http://127.0.0.1:8765/ (tunnel SSH depuis un autre poste)"
echo ""

cd "$SERVER"
# -Dterminal.jline=false : la console lit stdin ligne par ligne (stdin = FIFO du service systemd, pas un terminal).
exec "$JAVA" -Xms1G "-Xmx$MEMORY" -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
  -Dterminal.jline=false -Dterminal.ansi=false \
  -jar paper.jar --nogui
