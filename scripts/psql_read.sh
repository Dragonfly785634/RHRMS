#!/usr/bin/env bash
# Open an interactive psql session as rhrms_readonly: you can look at anything
# (except password hashes) and change nothing. Safe for poking around.
source "$(dirname "$0")/config.sh"
exec psql -X -h "$PGHOST" -p "$PGPORT" -U "$RHRMS_READONLY" -d "$RHRMS_DB" "$@"
