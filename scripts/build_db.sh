#!/usr/bin/env bash
# (Re)builds the whole RHRMS schema from scratch.  WARNING: erases all data.
#
#   ./scripts/build_db.sh            schema + rules + audit + db roles + seed (empty rescue)
#   ./scripts/build_db.sh --sample   ...plus the Bella / Otis & Pearl sample data and
#                                    the four development logins (mike, diane, jordan, sam)
set -euo pipefail
source "$(dirname "$0")/config.sh"

# sql/05_db_roles.sql grants privileges to roles that setup_db.sh creates. Check first, so a
# missing role produces one clear sentence instead of a wall of "role does not exist".
missing=""
for pair in "${RHRMS_ROLES[@]}"; do
  user="${pair%%:*}"
  found="$(as_owner -tAc "SELECT 1 FROM pg_roles WHERE rolname = '$user'" 2>/dev/null || echo "")"
  [[ "$found" == "1" ]] || missing+=" $user"
done
if [[ -n "$missing" ]]; then
  echo "These PostgreSQL roles do not exist yet:$missing" >&2
  echo "Run ./scripts/setup_db.sh first (it needs the PostgreSQL superuser)." >&2
  exit 1
fi

as_owner -c "DROP SCHEMA IF EXISTS rhrms CASCADE; DROP SCHEMA IF EXISTS rhrms_test CASCADE; CREATE SCHEMA rhrms;"

for f in 01_schema 02_rules 03_audit 04_seed 05_db_roles; do
  as_owner -f "$ROOT/sql/$f.sql"
done

if [[ "${1:-}" == "--sample" ]]; then
  as_owner -f "$ROOT/sql/90_sample_data.sql" >/dev/null
  echo "Built rhrms with sample data."
else
  echo "Built rhrms (empty). Start the server and use the first-run screen to create the director."
fi
