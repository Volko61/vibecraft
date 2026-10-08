#!/usr/bin/env bash
# Private Paper 26.3 sandbox servers for the e2e harness. They never touch server/ or workspace/.
#
#   tests/e2e/tools/sandbox.sh create   # copy the Paper runtime from ../../server, install ViaVersion+ViaBackwards
#   tests/e2e/tools/sandbox.sh start    # (re)apply ports, start in background, wait for "Done (", print the PID
#   tests/e2e/tools/sandbox.sh stop     # graceful RCON stop, then kill OUR java pid if still alive
#   tests/e2e/tools/sandbox.sh status
#
# Defaults = the harness-validation sandbox WITHOUT VibeCraft: tests/e2e/.sandbox, game 25570, RCON 25571.
# The integration sandbox (with a VibeCraft snapshot) is driven by tools/integ.sh, which calls this script with
# SANDBOX_DIR=.sandbox-integ SANDBOX_PORT=25572 SANDBOX_RCON_PORT=25573 SANDBOX_XMX=2G.
#
# Env: SANDBOX_DIR (default tests/e2e/.sandbox), SANDBOX_PORT (25570), SANDBOX_RCON_PORT (SANDBOX_PORT+1),
#      SANDBOX_XMX (1536M), JAVA (runtime/jdk-25/bin/java).
# Each sandbox JVM carries -Dvibecraft.e2e.sandbox=<dir name>: stop/status only ever touch THAT process.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
E2E="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$E2E/../.." && pwd)"
SB="${SANDBOX_DIR:-$E2E/.sandbox}"
case "$SB" in /*|?:*) ;; *) SB="$E2E/$SB" ;; esac
PORT="${SANDBOX_PORT:-25570}"
RCON_PORT="${SANDBOX_RCON_PORT:-$((PORT + 1))}"
XMX="${SANDBOX_XMX:-1536M}"
JAVA="${JAVA:-$ROOT/runtime/jdk-25/bin/java}"
NAME="$(basename "$SB")"
MARKER="-Dvibecraft.e2e.sandbox=$NAME"
PIDFILE="$SB/sandbox.pid"

find_pid() {
  # PID of the java process that carries our marker (never touches other servers).
  powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*vibecraft.e2e.sandbox=$NAME *' } | Select-Object -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r' | head -1
}

apply_ports() {
  sed -i -e "s/^server-port=.*/server-port=$PORT/" -e "s/^rcon.port=.*/rcon.port=$RCON_PORT/" \
         -e "s/^query.port=.*/query.port=$PORT/" -e 's/^enable-rcon=.*/enable-rcon=true/' \
         -e 's/^online-mode=.*/online-mode=false/' "$SB/server.properties"
}

case "${1:-}" in
  create)
    mkdir -p "$SB/plugins"
    for f in paper.jar eula.txt server.properties; do cp "$ROOT/server/$f" "$SB/"; done
    for d in libraries versions cache; do cp -r "$ROOT/server/$d" "$SB/"; done
    sed -i -e 's/^view-distance=.*/view-distance=4/' -e 's/^simulation-distance=.*/simulation-distance=4/' \
           -e "s/^motd=.*/motd=VibeCraft e2e sandbox ($NAME)/" "$SB/server.properties"
    apply_ports
    bash "$E2E/setup-via.sh" "$SB/plugins"
    echo "sandbox created in $SB (game $PORT, RCON $RCON_PORT)"
    ;;
  start)
    [ -f "$SB/paper.jar" ] || { echo "no sandbox in $SB: run '$0 create' first" >&2; exit 1; }
    pid="$(find_pid)"
    if [ -n "$pid" ]; then echo "already running (pid $pid)"; exit 0; fi
    apply_ports
    cd "$SB"
    rm -f sandbox.log
    nohup "$JAVA" "$MARKER" -Xms512M "-Xmx$XMX" -jar paper.jar --nogui > sandbox.log 2>&1 < /dev/null &
    for i in $(seq 1 150); do
      if grep -q 'Done (' sandbox.log 2>/dev/null; then break; fi
      if [ -z "$(find_pid)" ] && [ "$i" -gt 5 ]; then echo "sandbox JVM exited during startup, see $SB/sandbox.log" >&2; tail -20 sandbox.log >&2; exit 1; fi
      sleep 1
    done
    pid="$(find_pid)"
    echo "$pid" > "$PIDFILE"
    if grep -q 'Done (' sandbox.log; then echo "sandbox $NAME up (pid $pid, game $PORT, RCON $RCON_PORT) after ${i}s"; else echo "sandbox did not report Done within 150 s (pid $pid)"; exit 1; fi
    ;;
  stop)
    pid="$(find_pid)"
    if [ -z "$pid" ]; then echo "not running"; rm -f "$PIDFILE"; exit 0; fi
    node "$ROOT/tests/tools/rcon.mjs" --dir "$SB" stop || true
    for i in $(seq 1 45); do
      [ -z "$(find_pid)" ] && { echo "stopped $NAME (pid $pid)"; rm -f "$PIDFILE"; exit 0; }
      sleep 1
    done
    echo "graceful stop timed out, killing pid $pid"
    taskkill //PID "$pid" //F || kill -9 "$pid" || true
    rm -f "$PIDFILE"
    ;;
  status)
    pid="$(find_pid)"
    if [ -n "$pid" ]; then echo "running (pid $pid)"; else echo "not running"; fi
    ;;
  *)
    echo "usage: $0 create|start|stop|status" >&2; exit 2 ;;
esac
