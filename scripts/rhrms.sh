#!/usr/bin/env bash
# Opens an RHRMS terminal. Run it once per person: each window logs in separately,
# so every change is recorded against the person who made it.
set -euo pipefail
source "$(dirname "$0")/config.sh"
require_jar
export_app_env

if ! curl -fsS --max-time 2 "$RHRMS_API_URL/api/health" >/dev/null 2>&1; then
  echo "The RHRMS server is not answering on $RHRMS_API_URL."
  echo "Start it first:  ./scripts/start_server.sh"
  exit 2
fi

exec java -cp "$RHRMS_CLASSPATH" org.rubyhill.rhrms.ClientMain "$@"
