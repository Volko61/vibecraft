#!/usr/bin/env bash
# Commande d'administration installée en /usr/local/bin/vibecraft par deploy/linux/install.sh.
#   vibecraft status | start | stop | restart   état / pilotage du service
#   vibecraft logs                              console du serveur en direct (Ctrl+C pour quitter)
#   vibecraft cmd <commande...>                 commande console, ex. : vibecraft cmd op MonPseudo
#   vibecraft update                            git pull + recompilation du plugin + redémarrage
#   vibecraft ai-login                          connecter l'IA (Claude Code) à son compte, une fois (alias : claude-login)
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
  *) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
