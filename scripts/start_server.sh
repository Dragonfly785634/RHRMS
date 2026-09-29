#!/usr/bin/env bash
# Starts the local RHRMS API server in the background.
#
#   ./scripts/start_server.sh              start, wait until it answers
#   ./scripts/start_server.sh --foreground run in this window (Ctrl-C stops it)
#
# One server serves every terminal window on this computer. Starting it twice is
# refused, because the second one could not have the port anyway.
set -euo pipefail
source "$(dirname "$0")/config.sh"
require_jar
export_app_env

if [[ -f "$RHRMS_PID_FILE" ]] && kill -0 "$(cat "$RHRMS_PID_FILE")" 2>/dev/null; then
  echo "The server is already running (process $(cat "$RHRMS_PID_FILE")) on $RHRMS_API_URL"
  exit 0
fi
rm -f "$RHRMS_PID_FILE"

# Somebody is answering on our port, but it is not a process this script is tracking: an older
# server that outlived its process file, most likely. Say so and stop, because starting a second
# one would silently leave the OLD code serving every request - including the tests.
if curl -fsS --max-time 2 "$RHRMS_API_URL/api/health" >/dev/null 2>&1; then
  stray="$(rhrms_server_pids | tr '\n' ' ')"
  echo "Something is already answering on $RHRMS_API_URL, and it is not a server this script started." >&2
  if [[ -n "$stray" ]]; then
    echo "It looks like an older RHRMS server: process(es) $stray" >&2
    echo "Stop it with:  kill $stray        (or ./scripts/stop_server.sh --any)" >&2
  else
    echo "Find it with:  ss -ltnp | grep $RHRMS_API_PORT" >&2
  fi
  exit 5
fi

if [[ "${1:-}" == "--foreground" ]]; then
  exec java -cp "$RHRMS_CLASSPATH" org.rubyhill.rhrms.ServerMain
fi

mkdir -p "$(dirname "$RHRMS_LOG_FILE")"
nohup java -cp "$RHRMS_CLASSPATH" org.rubyhill.rhrms.ServerMain >"$RHRMS_LOG_FILE" 2>&1 &
pid=$!
echo "$pid" > "$RHRMS_PID_FILE"

# Wait for it to actually answer, rather than claiming success and leaving the next script to
# fail with a confusing connection error.
#
# The liveness check comes FIRST, on purpose. If our process died on startup - the usual reason
# being that the port is taken - then something else is answering on that port, and asking the
# port "are you up?" would get a cheerful yes from a server we did not start.
for _ in $(seq 1 60); do
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  if curl -fsS --max-time 2 "$RHRMS_API_URL/api/health" >/dev/null 2>&1; then
    echo "RHRMS server running on $RHRMS_API_URL (process $pid)"
    echo "  log: $RHRMS_LOG_FILE"
    echo "Next: ./scripts/rhrms.sh"
    exit 0
  fi
  sleep 0.5
done

echo "The server did not start. The last lines of its log:" >&2
tail -20 "$RHRMS_LOG_FILE" >&2
rm -f "$RHRMS_PID_FILE"
exit 1
