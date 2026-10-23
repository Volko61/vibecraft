#!/usr/bin/env bash
# Commande d'administration installée en /usr/local/bin/vibecraft par deploy/linux/install.sh.
#   vibecraft status | start | stop | restart   état / pilotage du service
#   vibecraft logs                              console du serveur en direct (Ctrl+C pour quitter)
#   vibecraft cmd <commande...>                 commande console, ex. : vibecraft cmd op MonPseudo
#   vibecraft update                            git pull + recompilation du plugin + redémarrage
#   vibecraft ai-login                          connecter l'IA (Claude Code) à son compte, une fois (alias : claude-login)
#   vibecraft dashboard <mot-de-passe> | off    ouvrir le dashboard web sur Internet avec ce mot de passe / le refermer
set -euo pipefail
ROOT="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
USER_NAME="$(stat -c %U "$ROOT")"
FIFO=/run/vibecraft.stdin

case "${1:-}" in
  status|start|stop|restart) exec systemctl "$1" vibecraft.service ;;
  logs) exec journalctl -u vibecraft.service -f -n 100 -o cat ;;
  cmd)
    shift
    [ $# -gt 0 ] || { echo "usage : vibecraft cmd <commande>" >&2; exit 2; }
    [ -p "$FIFO" ] || { echo "le serveur ne tourne pas ($FIFO absent)" >&2; exit 1; }
    printf '%s\n' "$*" > "$FIFO"
    sleep 1
    journalctl -u vibecraft.service -n 5 -o cat --no-pager
    ;;
  update)
    sudo -u "$USER_NAME" -H git -C "$ROOT" pull --ff-only
    sudo -u "$USER_NAME" -H bash "$ROOT/vibecraft-plugin/build.sh"
    exec systemctl restart vibecraft.service
    ;;
  ai-login|claude-login) exec sudo -u "$USER_NAME" -H bash -lc 'PATH="$HOME/.local/bin:$PATH" claude auth login' ;;
  dashboard)
    shift
    [ $# -eq 1 ] || { echo "usage : vibecraft dashboard <mot-de-passe> | off" >&2; exit 2; }
    cfg="$ROOT/server/plugins/VibeCraft/config.yml"
    [ -f "$cfg" ] || { echo "$cfg absent : démarre le serveur une fois" >&2; exit 1; }
    port="$(awk '/^api:/{a=1;next} a&&/^[^ #]/{a=0} a&&/^  port:/{print $2; exit}' "$cfg")"; port="${port:-8765}"
    if [ "$1" = off ]; then pub=false; tok=""
    else
      case "$1" in *'"'*|*\\*) echo "le mot de passe ne doit contenir ni \" ni \\" >&2; exit 2 ;; esac
      [ ${#1} -ge 6 ] || { echo "mot de passe trop court (6 caractères minimum)" >&2; exit 2; }
      pub=true; tok="$1"
    fi
    # remplace api.token / api.public (ajoutés sous "api:" s'ils manquent)
    TOK="$tok" PUB="$pub" awk '
      /^api:/ { print; print "  token: \"" ENVIRON["TOK"] "\""; print "  public: " ENVIRON["PUB"]; a=1; next }
      a && /^[^ #]/ { a=0 }
      a && (/^  token:/ || /^  public:/) { next }
      { print }' "$cfg" > "$cfg.tmp" && chown "$USER_NAME:" "$cfg.tmp" && chmod 600 "$cfg.tmp" && mv "$cfg.tmp" "$cfg"
    if command -v ufw >/dev/null; then
      if [ "$pub" = true ]; then ufw allow "$port/tcp" comment 'VibeCraft dashboard' >/dev/null
      else ufw delete allow "$port/tcp" >/dev/null 2>&1 || true; fi
    fi
    systemctl restart vibecraft.service
    if [ "$pub" = true ]; then
      ip="$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
      echo "Dashboard : http://$ip:$port/  (mot de passe demandé à la première visite ; serveur en cours de redémarrage)"
    else
      echo "Dashboard refermé (accessible seulement depuis le serveur)."
    fi
    ;;
  *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
