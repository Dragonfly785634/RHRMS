#!/usr/bin/env bash
# One-time setup: creates the PostgreSQL login roles and the empty rhrms database.
# Needs the PostgreSQL superuser (it will ask for your WSL sudo password).
#
# Safe to run again: existing roles keep their identity and have their password reset to
# whatever scripts/config.sh currently says, so config.sh and the database never drift apart.
#
# The roles, and why there are seven of them, are in docs/ROLES_AND_PASSWORDS.md.
set -euo pipefail
source "$(dirname "$0")/config.sh"

echo "Creating roles and database '$RHRMS_DB' on $PGHOST:$PGPORT ..."

# Build the role statements from the one list in config.sh, so adding a role there is
# the only edit needed. Single quotes inside a password are doubled for SQL.
role_sql=""
for pair in "${RHRMS_ROLES[@]}"; do
  user="${pair%%:*}"; pw="${pair#*:}"
  esc_pw="${pw//\'/\'\'}"
  role_sql+="
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$user') THEN
    ALTER ROLE $user LOGIN PASSWORD '$esc_pw';
  ELSE
    CREATE ROLE $user LOGIN PASSWORD '$esc_pw';
  END IF;"
done

search_path_sql=""
for pair in "${RHRMS_ROLES[@]}"; do
  user="${pair%%:*}"
  search_path_sql+="ALTER ROLE $user SET search_path = rhrms, public;
"
done

$PSQL_SUPER -X -q -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN$role_sql
END \$\$;
SELECT 'CREATE DATABASE $RHRMS_DB OWNER $RHRMS_OWNER'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$RHRMS_DB')\gexec
$search_path_sql
-- Only the owner may create objects. Everything else gets exactly the privileges
-- granted in sql/05_db_roles.sql, and nothing by accident.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE $RHRMS_DB FROM PUBLIC;
GRANT CONNECT ON DATABASE $RHRMS_DB TO $RHRMS_OWNER, $RHRMS_APP, $RHRMS_AUTH,
      $RHRMS_VOLUNTEER, $RHRMS_STAFF, $RHRMS_DIRECTOR, $RHRMS_READONLY;

-- The three session roles build on each other, so sql/05_db_roles.sql only has to list what
-- each one ADDS:   rhrms_volunteer  <  rhrms_staff  <  rhrms_director
-- Role membership is cluster-wide and only the superuser can grant it, which is why it is
-- here and not in 05_db_roles.sql with the per-table privileges.
GRANT $RHRMS_VOLUNTEER TO $RHRMS_STAFF;
GRANT $RHRMS_STAFF     TO $RHRMS_DIRECTOR;
SQL

# Save the passwords where psql looks for them, so scripts (and you) are not prompted.
# Lines for this host/port/database are REPLACED, not appended: a stale line earlier in the
# file would otherwise win and you would get "password authentication failed" with no clue why.
touch "$PGPASSFILE" && chmod 600 "$PGPASSFILE"
tmp="$(mktemp)"; chmod 600 "$tmp"
trap 'rm -f "$tmp"' EXIT

# Keep every line that is not one of ours, then append the current seven.
keep_pattern=""
for pair in "${RHRMS_ROLES[@]}"; do
  user="${pair%%:*}"
  keep_pattern+="${keep_pattern:+|}^$PGHOST:$PGPORT:$RHRMS_DB:$user:"
done
grep -Ev "$keep_pattern" "$PGPASSFILE" > "$tmp" || true
for pair in "${RHRMS_ROLES[@]}"; do
  user="${pair%%:*}"; pw="${pair#*:}"
  echo "$PGHOST:$PGPORT:$RHRMS_DB:$user:$pw" >> "$tmp"
done
cat "$tmp" > "$PGPASSFILE"          # write through, so the file keeps its own 600 permissions
chmod 600 "$PGPASSFILE"

echo "Created ${#RHRMS_ROLES[@]} roles; passwords saved in $PGPASSFILE"
echo "Next: ./scripts/build_db.sh --sample"
