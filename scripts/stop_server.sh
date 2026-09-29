#!/usr/bin/env bash
# Stops the local RHRMS API server. Every open terminal session ends with it.
set -uo pipefail
source "$(dirname "$0")/config.sh"

# ./scripts/stop_server.sh --any  stops every RHRMS server on this machine, process file or not.
# That is the way out of a stale process file left behind by a crash or a killed terminal.
if [[ "${1:-}" == "--any" ]]; then
  stray="$(rhrms_server_pids | tr '\n' ' ')"
  if [[ -z "$stray" ]]; then
    echo "No RHRMS server is running."
  else
    echo "Stopping: $stray"
    # shellcheck disable=SC2086
    kill $stray 2>/dev/null
    sleep 1
    # shellcheck disable=SC2086
    kill -9 $stray 2>/dev/null
    echo "Stopped."
  fi
  rm -f "$RHRMS_PID_FILE"
  exit 0
fi

if [[ ! -f "$RHRMS_PID_FILE" ]]; then
  echo "No server process file."
  if [[ -n "$(rhrms_server_pids)" ]]; then
    echo "But a server IS running. Stop it with:  ./scripts/stop_server.sh --any"
  fi
  exit 0
fi
pid="$(cat "$RHRMS_PID_FILE")"
if ! kill -0 "$pid" 2>/dev/null; then
  echo "The server is not running (stale process file removed)."
  rm -f "$RHRMS_PID_FILE"
  exit 0
fi
kill "$pid"
for _ in $(seq 1 20); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.25
done
if kill -0 "$pid" 2>/dev/null; then
  echo "It did not stop when asked; stopping it firmly."
  kill -9 "$pid" 2>/dev/null
fi
rm -f "$RHRMS_PID_FILE"
echo "Stopped."
