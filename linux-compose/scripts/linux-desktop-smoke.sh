#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOCKET_FILE="/tmp/cmux-linux.sock"
LOG_FILE="/tmp/cmux-linux-smoke.log"
TIMEOUT_SECONDS=30
SESSION_MODE="auto"
SKIP_BUILD=0
JAR_PATH="$ROOT_DIR/build/compose/jars/cmux-linux-x64-0.1.0.jar"

usage() {
  cat <<'EOF'
Usage: scripts/linux-desktop-smoke.sh [options]

Options:
  --mode auto|x11|wayland   Force display backend mode (default: auto)
  --timeout <seconds>       Timeout for startup/socket readiness (default: 30)
  --jar <path>              Jar path (default: build/compose/jars/cmux-linux-x64-0.1.0.jar)
  --skip-build              Do not run Gradle packaging step
  -h, --help                Show this help
EOF
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)
      SESSION_MODE="${2:-}"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SECONDS="${2:-}"
      shift 2
      ;;
    --jar)
      JAR_PATH="${2:-}"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ "$SESSION_MODE" != "auto" && "$SESSION_MODE" != "x11" && "$SESSION_MODE" != "wayland" ]]; then
  echo "Invalid --mode: $SESSION_MODE" >&2
  exit 1
fi

require_cmd java
require_cmd timeout
require_cmd nc
require_cmd ./gradlew

if [[ "$SESSION_MODE" == "auto" ]]; then
  if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
    SESSION_MODE="wayland"
  elif [[ -n "${DISPLAY:-}" ]]; then
    SESSION_MODE="x11"
  else
    echo "No desktop session detected (DISPLAY/WAYLAND_DISPLAY not set)." >&2
    exit 1
  fi
fi

if [[ "$SESSION_MODE" == "x11" && -z "${DISPLAY:-}" ]]; then
  echo "X11 mode selected but DISPLAY is not set." >&2
  exit 1
fi

if [[ "$SESSION_MODE" == "wayland" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  echo "Wayland mode selected but WAYLAND_DISPLAY is not set." >&2
  exit 1
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  (cd "$ROOT_DIR" && ./gradlew clean packageUberJarForCurrentOS)
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

rm -f "$SOCKET_FILE" "$LOG_FILE"

echo "Session mode: $SESSION_MODE"
echo "Jar: $JAR_PATH"
echo "Log: $LOG_FILE"

(
  cd "$ROOT_DIR"
  java -jar "$JAR_PATH" >"$LOG_FILE" 2>&1
) &
APP_PID=$!

cleanup() {
  if kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

deadline=$((SECONDS + TIMEOUT_SECONDS))
PORT=""
while (( SECONDS < deadline )); do
  if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo "cmux exited early. log: $LOG_FILE" >&2
    tail -n 120 "$LOG_FILE" >&2 || true
    exit 1
  fi

  if [[ -f "$SOCKET_FILE" ]]; then
    PORT="$(cat "$SOCKET_FILE" 2>/dev/null || true)"
    if [[ -n "$PORT" ]] && echo "ping" | nc -w 1 127.0.0.1 "$PORT" 2>/dev/null | grep -q '^pong$'; then
      break
    fi
  fi
  sleep 0.25
done

if [[ -z "$PORT" ]]; then
  echo "Socket port not ready in ${TIMEOUT_SECONDS}s. log: $LOG_FILE" >&2
  tail -n 120 "$LOG_FILE" >&2 || true
  exit 1
fi

echo "Socket port: $PORT"

help_out="$(echo "help" | nc -w 1 127.0.0.1 "$PORT")"
if ! grep -q '^tab.new$' <<<"$help_out"; then
  echo "Smoke failed: help output missing tab.new" >&2
  exit 1
fi

current_out="$(echo "tab.current" | nc -w 1 127.0.0.1 "$PORT")"
if [[ "$current_out" == error:* ]]; then
  echo "Smoke failed: tab.current returned error: $current_out" >&2
  exit 1
fi

new_id="$(echo "tab.new" | nc -w 1 127.0.0.1 "$PORT")"
if [[ -z "$new_id" || "$new_id" == error:* ]]; then
  echo "Smoke failed: tab.new returned invalid id: $new_id" >&2
  exit 1
fi

input_out="$(printf 'input echo smoke-ok\r\n' | nc -w 1 127.0.0.1 "$PORT")"
if [[ "$input_out" != "ok" ]]; then
  echo "Smoke failed: input command failed: $input_out" >&2
  exit 1
fi

list_out="$(echo "tab.list" | nc -w 1 127.0.0.1 "$PORT")"
if ! grep -q 'alive' <<<"$list_out"; then
  echo "Smoke failed: tab.list has no alive tab" >&2
  exit 1
fi

echo "Smoke test passed."
echo "tab.current: $current_out"
echo "new tab id: $new_id"
