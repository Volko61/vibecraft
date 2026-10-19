#!/usr/bin/env bash
# Installe VibeCraft sur un serveur Linux (Ubuntu / Debian, systemd) et le lance en service.
# Idempotent : on peut le relancer pour mettre à jour (le monde, les modules et les réglages sont gardés).
#
# Usage (en root, depuis le dépôt cloné, par exemple dans /opt/vibecraft) :
#   sudo deploy/linux/install.sh
# Variables (facultatives) :
#   VIBECRAFT_USER=vibecraft     compte système qui fait tourner le serveur
#   VIBECRAFT_MEMORY=4G          mémoire max de la JVM (-Xmx), gardée dans /etc/default/vibecraft
#   PAPER_VERSION=26.3           version de Paper téléchargée
#   UPGRADE_PAPER=1              retélécharger le dernier build de Paper même si server/paper.jar existe
#   SKIP_FIREWALL=1              ne pas toucher au pare-feu (ufw)
#   SKIP_CLAUDE=1                ne pas installer Claude Code (/vibe ai)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
USER_NAME="${VIBECRAFT_USER:-vibecraft}"
PAPER_VERSION="${PAPER_VERSION:-26.3}"
JDK_MAJOR=25
UA="vibecraft-installer (https://github.com/Volko61/vibecraft)"
MC_PORT=25565
PACK_PORT=8766

say() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mErreur : %s\033[0m\n' "$*" >&2; exit 1; }
as_user() { sudo -u "$USER_NAME" -H "$@"; }

[ "$(id -u)" -eq 0 ] || die "lancer en root : sudo $0"
command -v systemctl >/dev/null || die "systemd est nécessaire"

say "Paquets système"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates jq tar git sudo ufw >/dev/null

say "Compte système $USER_NAME"
if ! id "$USER_NAME" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "/var/lib/$USER_NAME" --shell /bin/bash "$USER_NAME"
fi
USER_HOME="$(getent passwd "$USER_NAME" | cut -d: -f6)"
chown -R "$USER_NAME:$USER_NAME" "$ROOT"
# Le serveur et les IA écrivent dans server/ et workspace/ ; git (mises à jour) tourne sous ce compte.
as_user git config --global --add safe.directory "$ROOT" 2>/dev/null || true

case "$(uname -m)" in
  x86_64|amd64) ARCH=x64 ;;
  aarch64|arm64) ARCH=aarch64 ;;
  *) die "architecture non gérée : $(uname -m)" ;;
esac

if [ ! -x "$ROOT/runtime/jdk-$JDK_MAJOR/bin/javac" ]; then
  say "JDK $JDK_MAJOR (Temurin, $ARCH) -> runtime/jdk-$JDK_MAJOR"
  tmp="$(mktemp -d)"
  curl -fSL -A "$UA" -o "$tmp/jdk.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/linux/$ARCH/jdk/hotspot/normal/eclipse"
  mkdir -p "$tmp/jdk"
  tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/jdk" --strip-components=1
  as_user mkdir -p "$ROOT/runtime"
  rm -rf "$ROOT/runtime/jdk-$JDK_MAJOR"
  mv "$tmp/jdk" "$ROOT/runtime/jdk-$JDK_MAJOR"
  chown -R "$USER_NAME:$USER_NAME" "$ROOT/runtime"
  rm -rf "$tmp"
fi
JAVA="$ROOT/runtime/jdk-$JDK_MAJOR/bin/java"
"$JAVA" -version 2>&1 | head -1

as_user mkdir -p "$ROOT/server/plugins"
if [ ! -f "$ROOT/server/paper.jar" ] || [ "${UPGRADE_PAPER:-0}" = 1 ]; then
  say "Paper $PAPER_VERSION (dernier build)"
  builds="$(curl -fsSL -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds")" \
    || die "impossible de lister les builds de Paper $PAPER_VERSION"
  url="$(jq -r '(if type == "array" then . else .builds end) | max_by(.id) | .downloads["server:default"].url' <<<"$builds")"
  sha="$(jq -r '(if type == "array" then . else .builds end) | max_by(.id) | .downloads["server:default"].checksums.sha256' <<<"$builds")"
  [ -n "$url" ] && [ "$url" != null ] || die "aucun build de Paper $PAPER_VERSION trouvé"
  as_user curl -fSL -A "$UA" -o "$ROOT/server/paper.jar.part" "$url"
  echo "$sha  $ROOT/server/paper.jar.part" | sha256sum -c --quiet || die "checksum de paper.jar invalide"
  as_user mv "$ROOT/server/paper.jar.part" "$ROOT/server/paper.jar"
  # Extrait server/libraries et server/versions (nécessaires pour compiler le plugin) sans lancer le serveur.
  (cd "$ROOT/server" && as_user "$JAVA" -Dpaperclip.patchonly=true -jar paper.jar >/dev/null)
fi

say "Réglages du serveur"
as_user sh -c "echo eula=true > '$ROOT/server/eula.txt'"
props="$ROOT/server/server.properties"
if [ ! -f "$props" ]; then
  # Première installation seulement : ensuite, server.properties est à toi.
  # online-mode=true : comptes Minecraft authentifiés par Mojang (sinon n'importe qui peut prendre le pseudo d'un op).
  # enable-rcon=false : RCON n'a rien à faire sur Internet (la console passe par /run/vibecraft.stdin).
  as_user tee "$props" >/dev/null <<PROPS
motd=VibeCraft - vibe-coding en direct
server-port=$MC_PORT
online-mode=true
enable-rcon=false
difficulty=easy
white-list=false
PROPS
fi
cat > /etc/default/vibecraft.new <<DEFAULTS
# Lu par vibecraft.service. Après modification : sudo systemctl restart vibecraft
VIBECRAFT_MEMORY=${VIBECRAFT_MEMORY:-4G}
# Clés facultatives (sinon dans server/plugins/VibeCraft/secrets.yml) :
#OPENROUTER_API_KEY=sk-or-...
#MESHY_API_KEY=msy_...
DEFAULTS
if [ -f /etc/default/vibecraft ] && [ -z "${VIBECRAFT_MEMORY:-}" ]; then
  rm /etc/default/vibecraft.new
else
  mv /etc/default/vibecraft.new /etc/default/vibecraft
fi
chmod 640 /etc/default/vibecraft
chown "root:$USER_NAME" /etc/default/vibecraft

say "Compilation du plugin"
as_user bash "$ROOT/vibecraft-plugin/build.sh"

if [ "${SKIP_CLAUDE:-0}" != 1 ] && [ ! -x "$USER_HOME/.local/bin/claude" ]; then
  say "Claude Code (pour /vibe ai)"
  as_user bash -c 'curl -fsSL https://claude.ai/install.sh | bash' \
    || echo "Claude Code non installé (le serveur marche sans, seul /vibe ai sera indisponible)."
fi

say "Service systemd"
for unit in vibecraft.service vibecraft.socket; do
  sed -e "s|@ROOT@|$ROOT|g" -e "s|@USER@|$USER_NAME|g" -e "s|@HOME@|$USER_HOME|g" \
    "$ROOT/deploy/linux/$unit" > "/etc/systemd/system/$unit"
done
ln -sf "$ROOT/deploy/linux/vibecraft.sh" /usr/local/bin/vibecraft
systemctl daemon-reload
systemctl enable vibecraft.socket vibecraft.service >/dev/null
systemctl restart vibecraft.service

if [ "${SKIP_FIREWALL:-0}" != 1 ]; then
  say "Pare-feu (ufw)"
  ufw allow OpenSSH >/dev/null
  ufw allow "$MC_PORT/tcp" comment 'Minecraft VibeCraft' >/dev/null
  ufw allow "$PACK_PORT/tcp" comment 'VibeCraft resource pack' >/dev/null
  ufw --force enable >/dev/null
  ufw status | sed 's/^/  /'
fi

ip="$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
say "Terminé"
cat <<DONE
  Le serveur démarre (environ 20 s) : sudo vibecraft logs
  Se donner les droits :             sudo vibecraft cmd op <ton pseudo>
  Connexion Minecraft (26.3) :       $ip  (port $MC_PORT)
  Dashboard, depuis ton PC :         ssh -L 8765:127.0.0.1:8765 <user>@$ip  puis http://127.0.0.1:8765/
  Brancher Claude Code (/vibe ai) :  sudo vibecraft claude-login
  Mise à jour du jeu :               sudo vibecraft update
DONE
