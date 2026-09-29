#!/usr/bin/env bash
# Open an interactive psql session as the application user (same rights as the UI).
source "$(dirname "$0")/config.sh"
exec psql -X -h "$PGHOST" -p "$PGPORT" -U "$RHRMS_APP" -d "$RHRMS_DB" "$@"
