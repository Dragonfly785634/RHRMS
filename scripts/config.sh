# Shared settings for every RHRMS script. Override any of these with
# environment variables, e.g.  PGPORT=5433 ./scripts/build_db.sh
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export RHRMS_DB="${RHRMS_DB:-rhrms}"

# ---------------------------------------------------------------------------
# PostgreSQL login roles. Each one is a different set of privileges, granted in
# sql/05_db_roles.sql. The chart of what each may do, and the passwords below,
# are in docs/ROLES_AND_PASSWORDS.md.
# ---------------------------------------------------------------------------
export RHRMS_OWNER="${RHRMS_OWNER:-rhrms_owner}"          # owns the schema; build scripts only
export RHRMS_APP="${RHRMS_APP:-rhrms_app}"                # broad role for the SQL test suite
export RHRMS_AUTH="${RHRMS_AUTH:-rhrms_auth}"             # server: credentials only
export RHRMS_VOLUNTEER="${RHRMS_VOLUNTEER:-rhrms_volunteer}"  # server: VOLUNTEER sessions
export RHRMS_STAFF="${RHRMS_STAFF:-rhrms_staff}"          # server: STAFF sessions
export RHRMS_DIRECTOR="${RHRMS_DIRECTOR:-rhrms_director}" # server: DIRECTOR sessions
export RHRMS_READONLY="${RHRMS_READONLY:-rhrms_readonly}" # reports; cannot change anything

# DEVELOPMENT PASSWORDS ONLY. Change every one of them for the real front-desk
# install (see docs/ROLES_AND_PASSWORDS.md, "Installing for real").
export RHRMS_OWNER_PASSWORD="${RHRMS_OWNER_PASSWORD:-owner_dev_pw}"
export RHRMS_APP_PASSWORD="${RHRMS_APP_PASSWORD:-app_dev_pw}"
export RHRMS_AUTH_PASSWORD="${RHRMS_AUTH_PASSWORD:-auth_dev_pw}"
export RHRMS_VOLUNTEER_PASSWORD="${RHRMS_VOLUNTEER_PASSWORD:-volunteer_dev_pw}"
export RHRMS_STAFF_PASSWORD="${RHRMS_STAFF_PASSWORD:-staff_dev_pw}"
export RHRMS_DIRECTOR_PASSWORD="${RHRMS_DIRECTOR_PASSWORD:-director_dev_pw}"
export RHRMS_READONLY_PASSWORD="${RHRMS_READONLY_PASSWORD:-readonly_dev_pw}"

# Every login role, in the order setup_db.sh creates them: "name:password".
RHRMS_ROLES=(
  "$RHRMS_OWNER:$RHRMS_OWNER_PASSWORD"
  "$RHRMS_APP:$RHRMS_APP_PASSWORD"
  "$RHRMS_AUTH:$RHRMS_AUTH_PASSWORD"
  "$RHRMS_VOLUNTEER:$RHRMS_VOLUNTEER_PASSWORD"
  "$RHRMS_STAFF:$RHRMS_STAFF_PASSWORD"
  "$RHRMS_DIRECTOR:$RHRMS_DIRECTOR_PASSWORD"
  "$RHRMS_READONLY:$RHRMS_READONLY_PASSWORD"
)

# ---------------------------------------------------------------------------
# The local API server (Stage 2). 127.0.0.1 on purpose: the API is for the people
# at this one computer, and nothing on the network can reach the loopback address.
# ---------------------------------------------------------------------------
export RHRMS_API_HOST="${RHRMS_API_HOST:-127.0.0.1}"
export RHRMS_API_PORT="${RHRMS_API_PORT:-8642}"
export RHRMS_API_URL="${RHRMS_API_URL:-http://$RHRMS_API_HOST:$RHRMS_API_PORT}"

# How to run psql as the PostgreSQL superuser (only setup_db.sh needs this).
PSQL_SUPER="${PSQL_SUPER:-sudo -u postgres psql}"

# Hide routine NOTICE chatter from scripts (tests still see their own PASS/FAIL notices)
export PGOPTIONS="${PGOPTIONS:--c client_min_messages=warning}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Where psql looks for saved passwords. Overridable so a test run never rewrites
# the operator's own ~/.pgpass.
export PGPASSFILE="${PGPASSFILE:-$HOME/.pgpass}"

_psql() { psql -X -q -v ON_ERROR_STOP=1 -h "$PGHOST" -p "$PGPORT" "$@"; }
as_owner()     { _psql -U "$RHRMS_OWNER"     -d "$RHRMS_DB" "$@"; }
as_app()       { _psql -U "$RHRMS_APP"       -d "$RHRMS_DB" "$@"; }
as_auth()      { _psql -U "$RHRMS_AUTH"      -d "$RHRMS_DB" "$@"; }
as_volunteer() { _psql -U "$RHRMS_VOLUNTEER" -d "$RHRMS_DB" "$@"; }
as_staff()     { _psql -U "$RHRMS_STAFF"     -d "$RHRMS_DB" "$@"; }
as_director()  { _psql -U "$RHRMS_DIRECTOR"  -d "$RHRMS_DB" "$@"; }
as_readonly()  { _psql -U "$RHRMS_READONLY"  -d "$RHRMS_DB" "$@"; }

# ---------------------------------------------------------------------------
# Hand the same settings to the Java server and client.
#
# config.sh is the single source of truth. AppConfig.java reads any setting from
# an environment variable named RHRMS_ + the key upper-cased with dots as
# underscores, so api.port becomes RHRMS_API_PORT. This function does that
# mapping, which means nobody has to keep rhrms.properties and config.sh in step.
# ---------------------------------------------------------------------------
export_app_env() {
  export RHRMS_DB_HOST="$PGHOST"
  export RHRMS_DB_PORT="$PGPORT"
  export RHRMS_DB_NAME="$RHRMS_DB"
  export RHRMS_DB_AUTH_USER="$RHRMS_AUTH"
  export RHRMS_DB_AUTH_PASSWORD="$RHRMS_AUTH_PASSWORD"
  export RHRMS_DB_VOLUNTEER_USER="$RHRMS_VOLUNTEER"
  export RHRMS_DB_VOLUNTEER_PASSWORD="$RHRMS_VOLUNTEER_PASSWORD"
  export RHRMS_DB_STAFF_USER="$RHRMS_STAFF"
  export RHRMS_DB_STAFF_PASSWORD="$RHRMS_STAFF_PASSWORD"
  export RHRMS_DB_DIRECTOR_USER="$RHRMS_DIRECTOR"
  export RHRMS_DB_DIRECTOR_PASSWORD="$RHRMS_DIRECTOR_PASSWORD"
  export RHRMS_API_HOST RHRMS_API_PORT
  export RHRMS_CLIENT_BASE_URL="$RHRMS_API_URL"
}

# Where the built jar and its dependencies live.
RHRMS_JAR="$ROOT/app/target/rhrms.jar"
RHRMS_CLASSPATH="$RHRMS_JAR:$ROOT/app/target/lib/*"
RHRMS_PID_FILE="${RHRMS_PID_FILE:-$ROOT/app/target/rhrms-server.pid}"
RHRMS_LOG_FILE="${RHRMS_LOG_FILE:-$ROOT/app/target/rhrms-server.log}"

# Every running RHRMS server on this machine, one pid per line.
#
# The comm check matters: pgrep -f matches on the whole command line, so the very shell running
# this function matches its own pattern. Filtering to processes whose executable is java is what
# stops "stop the server" from killing the terminal that asked.
rhrms_server_pids() {
  local pid
  for pid in $(pgrep -f 'org\.rubyhill\.rhrms\.ServerMain' 2>/dev/null); do
    [[ "$pid" == "$$" ]] && continue
    [[ "$(ps -o comm= -p "$pid" 2>/dev/null)" == "java" ]] && echo "$pid"
  done
}

# Fails with one clear sentence if the jar is not built yet.
require_jar() {
  if [[ ! -f "$RHRMS_JAR" ]]; then
    echo "The application is not built yet. Run ./scripts/build_app.sh" >&2
    return 1
  fi
}
