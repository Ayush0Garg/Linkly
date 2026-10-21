#!/usr/bin/env bash
# A local Redis without Docker or root: installs redis-server from conda-forge with micromamba, into a folder of its own.
#
#   scripts/dev-redis.sh install   download micromamba and install redis-server (once)
#   scripts/dev-redis.sh start     start Redis in the background on 127.0.0.1:6379 (installs first if needed)
#   scripts/dev-redis.sh stop
#   scripts/dev-redis.sh status
#   scripts/dev-redis.sh cli       open redis-cli
#   scripts/dev-redis.sh path      print the redis-server binary (use it as REDIS_SERVER_BIN for the tests)
#
# Env: REDIS_PORT (default 6379), LINKLY_HOME (default ~/.local/share/linkly)
set -euo pipefail

HOME_DIR="${LINKLY_HOME:-$HOME/.local/share/linkly}"
PORT="${REDIS_PORT:-6379}"
ENV_DIR="$HOME_DIR/redis-env"
BIN="$ENV_DIR/bin/redis-server"
CLI="$ENV_DIR/bin/redis-cli"
DATA_DIR="$HOME_DIR/redis-data"
PID_FILE="$HOME_DIR/redis-$PORT.pid"

platform() {
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) echo linux-64 ;;
    Linux-aarch64) echo linux-aarch64 ;;
    Darwin-arm64) echo osx-arm64 ;;
    Darwin-x86_64) echo osx-64 ;;
    *) echo "unsupported platform: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
  esac
}

install() {
  if [ -x "$BIN" ]; then echo "redis-server already installed: $BIN"; return; fi
  mkdir -p "$HOME_DIR/micromamba-download" && cd "$HOME_DIR/micromamba-download"
  echo "downloading micromamba ($(platform))..."
  curl -fsSL "https://micro.mamba.pm/api/micromamba/$(platform)/latest" -o micromamba.tar.bz2
  tar -xjf micromamba.tar.bz2 bin/micromamba
  echo "installing redis-server from conda-forge (this takes a minute)..."
  MAMBA_ROOT_PREFIX="$HOME_DIR/micromamba-root" ./bin/micromamba create -y -q -p "$ENV_DIR" -c conda-forge redis-server
  echo "installed: $("$BIN" --version)"
}

running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

case "${1:-}" in
  install) install ;;
  start)
    install
    if running; then echo "Redis already running on port $PORT (pid $(cat "$PID_FILE"))"; exit 0; fi
    mkdir -p "$DATA_DIR"
    # appendonly: the click stream (a later feature) must survive a restart. Bound to localhost only.
    "$BIN" --port "$PORT" --bind 127.0.0.1 --dir "$DATA_DIR" --appendonly yes --appendfsync everysec \
      --daemonize yes --pidfile "$PID_FILE" --logfile "$DATA_DIR/redis-$PORT.log"
    sleep 1
    if running; then echo "Redis running on redis://localhost:$PORT (pid $(cat "$PID_FILE"))"; else echo "Redis failed to start, see $DATA_DIR/redis-$PORT.log" >&2; exit 1; fi
    ;;
  stop)
    if running; then
      pid="$(cat "$PID_FILE")"; kill "$pid"
      for _ in $(seq 1 100); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done   # wait: a quick restart must not find it half-dead
      echo "Redis on port $PORT stopped"
    else echo "Redis on port $PORT is not running"; fi
    ;;
  status)
    if running; then echo "running on redis://localhost:$PORT (pid $(cat "$PID_FILE")): $("$CLI" -p "$PORT" ping)"; else echo "not running"; exit 1; fi
    ;;
  cli) exec "$CLI" -p "$PORT" ;;
  path) echo "$BIN" ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
