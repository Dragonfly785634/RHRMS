#!/usr/bin/env bash
# Compiles the server and the terminal client into app/target/rhrms.jar.
#   ./scripts/build_app.sh            build
#   ./scripts/build_app.sh --clean    build from scratch
set -euo pipefail
source "$(dirname "$0")/config.sh"

args=(-B package)
[[ "${1:-}" == "--clean" ]] && args=(-B clean package)

cd "$ROOT/app"
if ! mvn "${args[@]}"; then
  echo >&2
  echo "The build failed. If the message mentions downloading, this machine needs to reach" >&2
  echo "repo.maven.apache.org once to fetch the five libraries listed in docs/LIBRARIES.md." >&2
  exit 1
fi
echo
echo "Built $RHRMS_JAR"
echo "Next: ./scripts/start_server.sh  then  ./scripts/rhrms.sh"
