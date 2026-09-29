#!/usr/bin/env bash
# Runs the whole RHRMS test suite.
#
#   ./tests/run_tests.sh          everything: database rules, concurrency, and the API
#   ./tests/run_tests.sh sql      only the SQL tests (L1, L2, L3, L4)
#   ./tests/run_tests.sh race     only the two-session concurrency tests (L3)
#   ./tests/run_tests.sh api      only the Phase 1 acceptance tests, through the HTTP API
#   ./tests/run_tests.sh term     only the terminal client tests, through a pseudo-terminal
#   ./tests/run_tests.sh import   only installing from empty and loading the rescue spreadsheet
#
# The database is rebuilt with sample data before each test file. Your data is erased.
# The API tests need the application built first: ./scripts/build_app.sh
set -uo pipefail
source "$(dirname "$0")/../scripts/config.sh"

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
pass()  { PASS=$((PASS+1)); green "  PASS  $*"; }
fail()  { FAIL=$((FAIL+1)); red   "  FAIL  $*"; }

fresh_db() {
  "$ROOT/scripts/build_db.sh" --sample >/dev/null || { red "build failed"; exit 2; }
  as_owner -f "$ROOT/tests/helpers.sql" >/dev/null
}
app() { PGOPTIONS='-c client_min_messages=notice' psql -X -h "$PGHOST" -p "$PGPORT" -U "$RHRMS_APP" -d "$RHRMS_DB" "$@"; }
q()   { app -tAq -c "$1"; }

# ---------------------------------------------------------------- SQL tests
run_sql_tests() {
  for f in "$ROOT"/tests/sql/*.sql; do
    echo; echo "== $(basename "$f")"
    fresh_db
    out="$(app -q -v ON_ERROR_STOP=1 -f "$f" 2>&1)"; rc=$?
    while IFS= read -r line; do
      case "$line" in
        *"NOTICE:  PASS: "*) pass "${line#*PASS: }" ;;
        *"FAIL: "*)          fail "${line#*FAIL: }" ;;
      esac
    done <<< "$out"
    if [[ $rc -ne 0 ]] && ! grep -q "FAIL: " <<< "$out"; then
      fail "$(basename "$f") stopped with an unexpected error:"
      grep -E "ERROR|LINE|DETAIL" <<< "$out" | head -5
    fi
  done
}

# ---------------------------------------------------------- race tests (L3)
# Two sessions run the same risky action at the same moment. Each holds its
# transaction open for a second so the other is forced to wait and re-check.
race() {  # race <label> <sqlA> <sqlB> <delayB>
  local a b
  a="$(mktemp)"; b="$(mktemp)"
  app -v ON_ERROR_STOP=1 -c "$2" >"$a" 2>&1 &
  sleep "${4:-0.2}"
  app -v ON_ERROR_STOP=1 -c "$3" >"$b" 2>&1 &
  wait
  RACE_A="$(cat "$a")"; RACE_B="$(cat "$b")"; rm -f "$a" "$b"
}
wins() { ! grep -q "ERROR" <<< "$1"; }   # the session finished without an error

run_race_tests() {
  echo; echo "== concurrency (two real sessions at once)"

  # C1: the last bag of Bella's prescription food
  fresh_db
  S="BEGIN; SET LOCAL rhrms.user_id='2'; SELECT rhrms.move_stock(3,1::smallint,'OPEN',1,2); SELECT pg_sleep(1); COMMIT;"
  race c1 "$S" "$S" 0.2
  n_ok=0; wins "$RACE_A" && n_ok=$((n_ok+1)); wins "$RACE_B" && n_ok=$((n_ok+1))
  bags="$(q "SELECT sealed_bags FROM rhrms.stock_level WHERE food_product_id=3 AND location_id=1")"
  opens="$(q "SELECT count(*) FROM rhrms.stock_movement WHERE food_product_id=3 AND type='OPEN'")"
  if [[ $n_ok -eq 1 && "$bags" == "0" && "$opens" == "1" ]]; then
    pass "C1: two staff open the last prescription bag at once -> exactly one succeeds, stock is 0"
  else fail "C1: last bag (successes=$n_ok, bags=$bags, opens=$opens)"; fi

  # C2: two approvals for the same animal
  fresh_db
  q "BEGIN; SET LOCAL rhrms.user_id='1';
     SELECT rhrms_test.ready_to_approve('Ann','One','Otis');
     SELECT rhrms_test.ready_to_approve('Ben','Two','Otis'); COMMIT;" >/dev/null
  A="BEGIN; SET LOCAL rhrms.user_id='1'; UPDATE rhrms.application SET status='APPROVED' WHERE id=rhrms_test.app_of('One','Otis'); SELECT pg_sleep(1); COMMIT;"
  B="BEGIN; SET LOCAL rhrms.user_id='1'; UPDATE rhrms.application SET status='APPROVED' WHERE id=rhrms_test.app_of('Two','Otis'); SELECT pg_sleep(1); COMMIT;"
  race c2 "$A" "$B" 0.2
  approved="$(q "SELECT count(*) FROM rhrms.application WHERE status='APPROVED' AND animal_id=rhrms_test.animal_id('Otis')")"
  if [[ "$approved" == "1" ]] && wins "$RACE_A" && ! wins "$RACE_B"; then
    pass "C2: two approvals for Otis at once -> exactly one is approved"
  else fail "C2: double approval (approved=$approved)"; echo "$RACE_B" | grep ERROR; fi

  # C3: two staff record Bella's adoption at once
  fresh_db
  S="BEGIN; SET LOCAL rhrms.user_id='2'; SELECT rhrms.place_animal(rhrms_test.app_of('Lee','Bella'),2,250,'CASH'); SELECT pg_sleep(1); COMMIT;"
  race c3 "$S" "$S" 0.2
  placements="$(q "SELECT count(*) FROM rhrms.placement WHERE animal_id=rhrms_test.animal_id('Bella')")"
  if [[ "$placements" == "1" ]] && grep -q "RULE PL-1\|not approved" <<< "$RACE_B"; then
    pass "C3: two staff record Bella's adoption at once -> one placement, the other is told it is already done"
  else fail "C3: double placement (placements=$placements)"; echo "$RACE_B" | grep ERROR; fi

  # C4: an application is typed in while Bella is being adopted
  fresh_db
  A="BEGIN; SET LOCAL rhrms.user_id='2'; SELECT rhrms.place_animal(rhrms_test.app_of('Lee','Bella'),2,250,'CASH'); SELECT pg_sleep(1.5); COMMIT;"
  B="BEGIN; SET LOCAL rhrms.user_id='4';
     INSERT INTO rhrms.person(first_name,last_name) VALUES ('Late','Comer');
     INSERT INTO rhrms.application(person_id,animal_id,received_by)
       VALUES ((SELECT id FROM rhrms.person WHERE last_name='Comer'), rhrms_test.animal_id('Bella'), 4); COMMIT;"
  race c4 "$A" "$B" 0.4
  if wins "$RACE_A" && grep -q "is adopted and cannot take new applications" <<< "$RACE_B"; then
    pass "C4: application saved while Bella is being adopted -> waits, then is refused (\"adopted an hour ago\" case)"
  else fail "C4: stale application"; echo "$RACE_B" | grep ERROR; fi

  # C5: two people edit Luna from the same screen state (lost update)
  fresh_db
  v="$(q "SELECT version FROM rhrms.animal WHERE name='Luna'")"
  A="BEGIN; SET LOCAL rhrms.user_id='2'; UPDATE rhrms.animal SET general_notes='edit A', version=version+1 WHERE name='Luna' AND version=$v; SELECT pg_sleep(1); COMMIT;"
  B="BEGIN; SET LOCAL rhrms.user_id='3'; UPDATE rhrms.animal SET general_notes='edit B', version=version+1 WHERE name='Luna' AND version=$v; COMMIT;"
  race c5 "$A" "$B" 0.3
  notes="$(q "SELECT general_notes FROM rhrms.animal WHERE name='Luna'")"
  if grep -q "UPDATE 1" <<< "$RACE_A" && grep -q "UPDATE 0" <<< "$RACE_B" && [[ "$notes" == "edit A" ]]; then
    pass "C5: two edits of Luna from the same version -> second is detected (UPDATE 0), first is kept"
  else fail "C5: lost update (notes=$notes)"; fi
}

# --------------------------------------------------------- Phase 1 via the API
# A separate script, because it talks HTTP rather than SQL and keeps its own tally. It covers the
# ten Phase 1 features end to end: tests/api/phase1.sh
API_RESULT=0
run_api_tests() {
  echo
  echo "== Phase 1 acceptance tests (through the HTTP API)"
  if [[ ! -f "$ROOT/app/target/rhrms.jar" ]]; then
    red "  SKIPPED: the application is not built. Run ./scripts/build_app.sh first."
    API_RESULT=2
    return
  fi
  "$ROOT/tests/api/phase1.sh" || API_RESULT=1
}

# ------------------------------------- installing from nothing, and the rescue's spreadsheet
# These start from an EMPTY database rather than the sample data, which is why they are separate:
# they are the only tests that install RHRMS the way a rescue would, and two bugs hid behind that
# gap. See the header of tests/api/import.sh.
IMPORT_RESULT=0
run_import_tests() {
  echo
  echo "== First run, and loading the rescue's spreadsheet"
  if [[ ! -f "$ROOT/app/target/rhrms.jar" ]]; then
    red "  SKIPPED: the application is not built. Run ./scripts/build_app.sh first."
    IMPORT_RESULT=2
    return
  fi
  "$ROOT/tests/api/import.sh" || IMPORT_RESULT=1
}

# --------------------------------------------------- the terminal client itself
# The API tests use curl, so they never touch the client. These drive the real terminal through
# a pseudo-terminal, which is the only way to catch the client's own failures.
TERM_RESULT=0
run_terminal_tests() {
  echo
  echo "== Terminal client tests (through a pseudo-terminal)"
  if [[ ! -f "$ROOT/app/target/rhrms.jar" ]]; then
    red "  SKIPPED: the application is not built. Run ./scripts/build_app.sh first."
    TERM_RESULT=2
    return
  fi
  "$ROOT/tests/api/terminal.sh" || TERM_RESULT=1
}

case "${1:-all}" in
  sql)  run_sql_tests ;;
  race) run_race_tests ;;
  api)  run_api_tests ;;
  term)   run_terminal_tests ;;
  import) run_import_tests ;;
  *)      run_sql_tests; run_race_tests; run_api_tests; run_terminal_tests; run_import_tests ;;
esac

echo
if [[ $PASS -gt 0 || $FAIL -gt 0 ]]; then
  if [[ $FAIL -eq 0 ]]; then green "DATABASE TESTS: ALL $PASS PASSED"
  else red "DATABASE TESTS: $FAIL FAILED, $PASS passed"; fi
fi
case "${1:-all}" in
  sql|race|term|import) ;;
  *) case $API_RESULT in
       0) green "PHASE 1 TESTS:  PASSED" ;;
       1) red   "PHASE 1 TESTS:  FAILED" ;;
       2) red   "PHASE 1 TESTS:  SKIPPED (application not built)" ;;
     esac ;;
esac
case "${1:-all}" in
  sql|race|api|import) ;;
  *) case $TERM_RESULT in
       0) green "TERMINAL TESTS: PASSED" ;;
       1) red   "TERMINAL TESTS: FAILED" ;;
       2) red   "TERMINAL TESTS: SKIPPED (application not built)" ;;
     esac ;;
esac
case "${1:-all}" in
  sql|race|api|term) ;;
  *) case $IMPORT_RESULT in
       0) green "IMPORT TESTS:   PASSED" ;;
       1) red   "IMPORT TESTS:   FAILED" ;;
       2) red   "IMPORT TESTS:   SKIPPED (application not built)" ;;
     esac ;;
esac

"$ROOT/scripts/build_db.sh" --sample >/dev/null   # leave a clean sample database behind
exit $(( FAIL > 0 || API_RESULT > 0 || TERM_RESULT > 0 || IMPORT_RESULT > 0 ))
