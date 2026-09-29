# Shared helpers for the API test suite.
#
# Every test drives the real server over HTTP with curl, exactly as the terminal does. Nothing
# here reaches into the database to set up a case: if a fixture cannot be built through the API,
# that is itself a bug worth knowing about.
#
# Source this, do not run it.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/config.sh"

PASS=0
FAIL=0
FAILED_LABELS=()

_green() { printf '\033[32m%s\033[0m\n' "$*"; }
_red()   { printf '\033[31m%s\033[0m\n' "$*"; }
_bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
_dim()   { printf '\033[2m%s\033[0m\n' "$*"; }

pass() { PASS=$((PASS + 1)); _green "  PASS  $*"; }
fail() {
  FAIL=$((FAIL + 1))
  FAILED_LABELS+=("$1")
  _red "  FAIL  $*"
  if [[ -n "${BODY:-}" ]]; then
    _dim "        status $STATUS"
    _dim "        $(printf '%s' "$BODY" | head -c 400)"
  fi
}

section() { echo; _bold "$*"; printf '%s\n' "$(printf '%.0s-' $(seq 1 ${#1}))"; }

# ---------------------------------------------------------------- requirements

require_tools() {
  local missing=""
  command -v curl    >/dev/null || missing+=" curl"
  command -v python3 >/dev/null || missing+=" python3"
  command -v psql    >/dev/null || missing+=" psql"
  if [[ -n "$missing" ]]; then
    _red "These tests need:$missing"
    echo "On Ubuntu or WSL: sudo apt install -y curl python3 postgresql-client" >&2
    exit 2
  fi
  require_jar || exit 2
}

# --------------------------------------------------------------- the fixture

# Rebuilds the database with the sample data and restarts the server.
#
# The restart matters: sessions live in the server's memory, so after the database is rebuilt any
# token still held would point at a staff_user row that no longer means the same thing. Throwing
# them away is both correct and how a real restart behaves.
fresh() {
  "$ROOT/scripts/build_db.sh" --sample >/dev/null || { _red "build_db.sh failed"; exit 2; }
  "$ROOT/scripts/stop_server.sh"  >/dev/null 2>&1
  "$ROOT/scripts/start_server.sh" >/dev/null   || { _red "start_server.sh failed"; exit 2; }
}

# Rebuilds with no sample data at all, for the first-run test.
fresh_empty() {
  "$ROOT/scripts/build_db.sh" >/dev/null || { _red "build_db.sh failed"; exit 2; }
  "$ROOT/scripts/stop_server.sh"  >/dev/null 2>&1
  "$ROOT/scripts/start_server.sh" >/dev/null   || { _red "start_server.sh failed"; exit 2; }
}

# ------------------------------------------------------------------- requests

# call TOKEN METHOD PATH [JSON-BODY] [CONFIRM-PASSWORD]
# Leaves the HTTP status in $STATUS and the reply in $BODY.
call() {
  local token="$1" method="$2" path="$3" body="${4-}" confirm="${5-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method" "$RHRMS_API_URL$path"
              -H 'Content-Type: application/json' -H 'X-RHRMS-Client: test-suite')
  [[ -n "$token" ]]   && args+=(-H "Authorization: Bearer $token")
  [[ -n "$confirm" ]] && args+=(-H "X-RHRMS-Confirm: $confirm")
  [[ -n "$body" ]]    && args+=(-d "$body")

  # Two passes rather than one, because the body and the status code are wanted separately and
  # -w writes to the same stream as the body.
  local out
  out="$(mktemp)"
  args[2]="$out"
  STATUS="$(curl "${args[@]}" 2>/dev/null)"
  BODY="$(cat "$out")"
  rm -f "$out"
}

get()  { call "$1" GET    "$2"; }
post() { call "$1" POST   "$2" "${3-}" "${4-}"; }

# Logs in and echoes the token. Empty when the login was refused.
login() {
  call "" POST /api/auth/login "{\"username\":\"$1\",\"password\":$(json_string "$2")}"
  field token
}

# --------------------------------------------------------------- reading JSON

_PICK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pick.py"

# field a.b.0.c   -- echoes the value at that path in $BODY, or nothing
field() { printf '%s' "$BODY" | python3 "$_PICK" "${1-}" 2>/dev/null; }

# json_string 'any text' -- a correctly quoted JSON string, so passwords with # or " are safe
json_string() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

# --------------------------------------------------------------- assertions

# expect_status <code> <label>
expect_status() {
  if [[ "$STATUS" == "$1" ]]; then pass "$2"; else fail "$2 (wanted HTTP $1, got $STATUS)"; fi
}

# expect_code <error-code> <label>   -- the code in the error object, e.g. ACTION_DENIED
expect_code() {
  local got; got="$(field error.code)"
  if [[ "$got" == "$1" ]]; then pass "$2"; else fail "$2 (wanted $1, got '${got:-none}')"; fi
}

# expect_field <path> <value> <label>
expect_field() {
  local got; got="$(field "$1")"
  if [[ "$got" == "$2" ]]; then pass "$3"; else fail "$3 ($1 was '${got}', wanted '$2')"; fi
}

# expect_in <substring> <label>   -- somewhere in the reply
expect_in() {
  if printf '%s' "$BODY" | grep -qiF -- "$1"; then pass "$2"; else fail "$2 (no '$1' in the reply)"; fi
}

# expect_not_in <substring> <label>
expect_not_in() {
  if printf '%s' "$BODY" | grep -qiF -- "$1"; then fail "$2 (found '$1' in the reply)"; else pass "$2"; fi
}

# expect_ok <label>   -- any 2xx
expect_ok() {
  if [[ "$STATUS" =~ ^2 ]]; then pass "$1"; else fail "$1 (HTTP $STATUS)"; fi
}

# expect_refused <label>  -- any 4xx, and the reply must carry a message somebody can act on
expect_refused() {
  if [[ ! "$STATUS" =~ ^4 ]]; then
    fail "$1 (expected a refusal, got HTTP $STATUS)"
    return
  fi
  local message; message="$(field error.message)"
  if [[ ${#message} -lt 15 ]]; then
    fail "$1 (refused, but the message was '$message' - too short to act on)"
  else
    pass "$1"
  fi
}

# Prints the tally and returns non-zero if anything failed.
report() {
  echo
  if [[ $FAIL -eq 0 ]]; then
    _green "ALL $PASS CHECKS PASSED"
  else
    _red "$FAIL FAILED, $PASS passed"
    for label in "${FAILED_LABELS[@]}"; do _red "   - $label"; done
  fi
  return $(( FAIL > 0 ))
}
