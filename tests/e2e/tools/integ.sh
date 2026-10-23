#!/usr/bin/env bash
# Integration sandbox: a PRIVATE Paper 26.3 server with ViaVersion + ViaBackwards and a SNAPSHOT of VibeCraft,
# used to run the real e2e suite without touching the main server (server/) or the real workspace/.
#
#   game 25572, RCON 25573, VibeCraft API http://127.0.0.1:8772, -Xmx2G
#   modules-dir / data-dir: tests/e2e/.sandbox-integ/workspace/{modules,data}
#
#   tests/e2e/tools/integ.sh setup           # build a snapshot (OUT_DIR=out-tests), create the sandbox if
#                                            # missing, install VibeCraft.jar + plugins/VibeCraft/config.yml
#   tests/e2e/tools/integ.sh start|stop|status
#   tests/e2e/tools/integ.sh run [opts...]   # node run-e2e.mjs against the sandbox (extra run-e2e options)
#   tests/e2e/tools/integ.sh all [opts...]   # stop + setup + start + run + stop; exit code = the run's
#   tests/e2e/tools/integ.sh restart-check   # stops/starts the sandbox: startup loading, .vibe/disabled, store across restart
#
# AI jobs: by default the sandbox's ai.claude-path is tools/fake-claude.mjs (a scripted stand-in that never calls a
# model) so the ai-jobs scenario can exercise the whole job pipeline; INTEG_FAKE_CLAUDE=0 keeps the real CLI.
#
# Env: INTEG_DIR (default tests/e2e/.sandbox-integ), INTEG_PORT (25572), INTEG_RCON_PORT (25573),
#      INTEG_API_PORT (8772), INTEG_XMX (2G), INTEG_SKIP_BUILD=1 (reuse vibecraft-plugin/out-tests/VibeCraft.jar),
#      INTEG_FAKE_CLAUDE (default 1), INTEG_API_TOKEN (sets api.token and passes --token; needs setup + restart).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
E2E="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$E2E/../.." && pwd)"
winpath() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }

export SANDBOX_DIR="${INTEG_DIR:-$E2E/.sandbox-integ}"
export SANDBOX_PORT="${INTEG_PORT:-25572}"
export SANDBOX_RCON_PORT="${INTEG_RCON_PORT:-25573}"
export SANDBOX_XMX="${INTEG_XMX:-2G}"
API_PORT="${INTEG_API_PORT:-8772}"
FAKE_CLAUDE="${INTEG_FAKE_CLAUDE:-1}"
API_TOKEN="${INTEG_API_TOKEN:-}"
SB="$SANDBOX_DIR"
FAKE_LOG_NAME="fake-claude.log"

setup() {
  if [ "${INTEG_SKIP_BUILD:-}" != "1" ]; then
    # Snapshot build: never --deploy (that would overwrite server/plugins/VibeCraft.jar).
    (cd "$ROOT" && OUT_DIR=out-tests ./vibecraft-plugin/build.sh)
  fi
  local jar="$ROOT/vibecraft-plugin/out-tests/VibeCraft.jar"
  [ -f "$jar" ] || { echo "missing $jar" >&2; exit 1; }
  [ -f "$SB/paper.jar" ] || bash "$HERE/sandbox.sh" create
  mkdir -p "$SB/plugins/VibeCraft" "$SB/workspace/modules" "$SB/workspace/data"
  cp "$jar" "$SB/plugins/VibeCraft.jar"
  local ws; ws="$(winpath "$SB/workspace")"
  # Fresh config from the plugin's defaults, only paths and API port changed.
  sed -e "s#^modules-dir:.*#modules-dir: $ws/modules#" -e "s#^data-dir:.*#data-dir: $ws/data#" \
      -e "/^api:/,/^[a-z]/ s/^  port: .*/  port: $API_PORT/" \
      "$ROOT/vibecraft-plugin/src/main/resources/config.yml" > "$SB/plugins/VibeCraft/config.yml"
  grep -q "port: $API_PORT" "$SB/plugins/VibeCraft/config.yml" || { echo "failed to set api.port" >&2; exit 1; }
  if [ -n "$API_TOKEN" ]; then
    sed -i -e "/^api:/,/^[a-z]/ s/^  token: .*/  token: \"$API_TOKEN\"/" "$SB/plugins/VibeCraft/config.yml"
    grep -q "token: \"$API_TOKEN\"" "$SB/plugins/VibeCraft/config.yml" || { echo "failed to set api.token" >&2; exit 1; }
  fi
  if [ "$FAKE_CLAUDE" = "1" ]; then
    local fake; fake="$(winpath "$HERE")/fake-claude.mjs"
    sed -i -e "s#^  claude-path: .*#  claude-path: $fake#" \
           -e "s#^ai:\$#ai:\n  env:\n    FAKE_CLAUDE_LOG: $(winpath "$SB")/$FAKE_LOG_NAME#" "$SB/plugins/VibeCraft/config.yml"
    grep -q "fake-claude.mjs" "$SB/plugins/VibeCraft/config.yml" || { echo "failed to set ai.claude-path" >&2; exit 1; }
    rm -f "$SB/plugins/VibeCraft/ai-sessions.yml" "$SB/$FAKE_LOG_NAME"
  fi
  echo "integration sandbox ready: $(sha1sum "$SB/plugins/VibeCraft.jar" | cut -c1-12) VibeCraft.jar, API port $API_PORT, modules $ws/modules"
}

run() {
  cd "$E2E"
  local fake=()
  [ "$FAKE_CLAUDE" = "1" ] && fake=(--fake-claude "$(winpath "$SB")/$FAKE_LOG_NAME")
  [ -n "$API_TOKEN" ] && fake+=(--token "$API_TOKEN")
  node run-e2e.mjs --mc-port "$SANDBOX_PORT" --api "http://127.0.0.1:$API_PORT" \
    --modules-dir "$(winpath "$SB/workspace/modules")" --data-dir "$(winpath "$SB/workspace/data")" \
    --rcon-dir "$(winpath "$SB")" "${fake[@]}" "$@"
}

restart_check() {
  local a=(--api "http://127.0.0.1:$API_PORT" --modules-dir "$(winpath "$SB/workspace/modules")" --data-dir "$(winpath "$SB/workspace/data")")
  node "$HERE/restart-check.mjs" --phase prepare "${a[@]}"
  bash "$HERE/sandbox.sh" stop
  bash "$HERE/sandbox.sh" start
  node "$HERE/restart-check.mjs" --phase verify "${a[@]}"
}

case "${1:-}" in
  setup) setup ;;
  start|stop|status) bash "$HERE/sandbox.sh" "$1" ;;
  run) shift; run "$@" ;;
  restart-check) restart_check ;;
  all)
    shift
    bash "$HERE/sandbox.sh" stop
    setup
    bash "$HERE/sandbox.sh" start
    code=0
    run "$@" || code=$?
    bash "$HERE/sandbox.sh" stop || true
    exit "$code"
    ;;
  *) echo "usage: $0 setup|start|stop|status|run [run-e2e options]|all [run-e2e options]|restart-check" >&2; exit 2 ;;
esac
