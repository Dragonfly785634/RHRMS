#!/usr/bin/env bash
# Tests for installing RHRMS from nothing and loading the rescue's own spreadsheet.
#
# This file exists because two bugs got through 297 other checks, and both got through for the
# same reason: every other test starts from build_db.sh --sample, which inserts the staff
# accounts with plain SQL. Nothing ever installed the system the way a rescue would.
#
#   1. POST /api/bootstrap/director failed with "A result was returned when none was expected".
#      It used Ctx.update() - executeUpdate() - on a SELECT set_config(...) statement. So RHRMS
#      could not be installed on an empty database at all, and the suite could not see it,
#      because lib.sh's fresh_empty() helper was written for a first-run test that was never
#      written. Section 1 is that test.
#
#   2. The director's PL-3 vetting override had the identical bug on the identical call. Nothing
#      exercised it. Section 4 does.
#
# The rest checks that the import of "MASTER copy FINAL (2).xlsx" says what the spreadsheet
# says - including the parts of it that contradict each other, which must survive rather than
# be quietly tidied away.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_tools

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SHEET="$ROOT/Documents/MASTER copy FINAL (2).xlsx"

if [[ ! -f "$SHEET" ]]; then
  _red "Cannot find $SHEET - skipping the import tests."
  exit 0
fi

# A number straight out of the database, for the facts that have no endpoint of their own.
sql() { as_owner -tAc "SET search_path = rhrms; $1" 2>/dev/null | tr -d '[:space:]'; }

expect_sql() {  # expect_sql <query> <value> <label>
  local got; got="$(sql "$1")"
  if [[ "$got" == "$2" ]]; then pass "$3"; else fail "$3 (got '${got:-none}', wanted '$2')"; fi
}

# ===================================================================== 1
section "1. Installing on an empty database (the first-run screen)"

fresh_empty          # no sample data at all: staff_user is empty, as on a real new install

get "" /api/bootstrap/status
expect_status 200 "an empty installation answers the first-run check"
expect_field needsFirstRun true "and says it needs its first account"
expect_field accounts 0 "with no accounts yet"

# The bug. Before the fix this returned 500 DATABASE_ERROR and the system could not be installed.
post "" /api/bootstrap/director \
  '{"username":"mike","displayName":"Mike (Director)","password":"Director#2026"}'
expect_status 201 "the first director account can actually be created"
expect_field role DIRECTOR "and is a director"
expect_in "Log in as mike" "with a message saying what to do next"

get "" /api/bootstrap/status
expect_field needsFirstRun false "first run is over"
expect_field accounts 1 "one account exists"

TOKEN="$(login mike 'Director#2026')"
[[ -n "$TOKEN" ]] && pass "the new director can log in" || fail "the new director can log in"

# Creating the first account is a change, so the audit log must name who made it - and the
# only person it can name is the account being created. That is the awkward case the handler
# claims to handle, so it is worth checking rather than assuming.
expect_sql "SELECT count(*) FROM audit_log WHERE table_name='staff_user' AND action='INSERT'" \
  1 "the first account is in the audit log"
expect_sql "SELECT (user_id = row_id::bigint)::text FROM audit_log
            WHERE table_name='staff_user' AND action='INSERT'" \
  true "attributed to itself, not to nobody"
expect_sql "SELECT (new_row->>'password_hash' NOT LIKE '\$2%')::text FROM audit_log
            WHERE table_name='staff_user' AND action='INSERT'" \
  true "and the password hash is not copied into the audit log"

post "" /api/bootstrap/director \
  '{"username":"sneaky","displayName":"Second Try","password":"Another#2026"}'
expect_refused "first run cannot be used a second time to make an account"

# ===================================================================== 2
section "2. Loading the rescue's spreadsheet"

before_md5="$(md5sum "$SHEET" | cut -d' ' -f1)"

if "$ROOT/scripts/import_spreadsheet.sh" >/dev/null 2>&1; then
  pass "the import runs"
else
  fail "the import runs"
fi

after_md5="$(md5sum "$SHEET" | cut -d' ' -f1)"
[[ "$before_md5" == "$after_md5" ]] \
  && pass "the spreadsheet was not modified" \
  || fail "the spreadsheet was not modified (md5 changed)"

# 46 data rows, one of which is a second application for a dog already in the file.
expect_sql "SELECT count(*) FROM animal" 45 "45 animals, not 46: the two Barley rows are one dog"
expect_sql "SELECT count(*) FROM stay" 45 "every animal has a stay"
expect_sql "SELECT count(*) FROM person" 15 "15 adopters"
expect_sql "SELECT count(*) FROM application" 15 "15 applications"
expect_sql "SELECT count(*) FROM donation" 9 "9 donations, and not the typed total row"
expect_sql "SELECT count(*) FROM food_product" 4 "4 food products"
expect_sql "SELECT coalesce(sum(sealed_bags),0) FROM stock_level" 24 "24 bags of food on hand"

# Running it twice must not double the data.
if "$ROOT/scripts/import_spreadsheet.sh" >/dev/null 2>&1; then
  fail "a second import is refused"
else
  pass "a second import is refused"
fi
expect_sql "SELECT count(*) FROM animal" 45 "and nothing was loaded twice"
expect_sql "SELECT count(*) FROM rhrms_import.animal_row" 47 \
  "and the refusal did not wipe the transcription on its way out"
expect_sql "SELECT count(*) FROM rhrms_import.animal_link" 46 \
  "nor the record of which row became which animal"

# ===================================================================== 3
section "3. What the spreadsheet said is what the database says"

TOKEN="$(login mike 'Director#2026')"

# Barley: one dog, two adoption attempts, the first denied and the second successful.
expect_sql "SELECT count(*) FROM animal WHERE name='Barley'" 1 "there is one Barley"
expect_sql "SELECT count(*) FROM application ap JOIN animal a ON a.id=ap.animal_id
            WHERE a.name='Barley'" 2 "with two applications against him"
expect_sql "SELECT count(*) FROM decision d JOIN application ap ON ap.id=d.application_id
            JOIN animal a ON a.id=ap.animal_id WHERE a.name='Barley' AND d.outcome='DENY'" \
  1 "one of them denied"

# Luna came in as Princess. A search for the old name has to find her, or the history is
# decoration - this is the whole point of AN-2.
get "$TOKEN" '/api/animals?q=princess'
expect_in '"Luna"' "searching her old name 'Princess' finds Luna"
get "$TOKEN" '/api/animals?q=shep'
expect_in "Shepherd mix" "searching a breed finds the shepherds"
expect_in "German Shepard mix" "including the misspelled ones"

# The two people who share a phone number must both come back from searching it, side by
# side, which is the moment a human can decide whether they are one person.
get "$TOKEN" '/api/people?q=720-555-0198'
expect_in "Kowalczyk" "the shared phone number finds the first spelling"
expect_in "Kowalcyzk" "and the second"

# Contradictions in the source are preserved, not smoothed over.
expect_sql "SELECT (s.ended_on < ap.submitted_at::date)::text
            FROM animal a JOIN stay s ON s.animal_id=a.id
            JOIN application ap ON ap.animal_id=a.id WHERE a.name='Rufus'" \
  true "Rufus is still adopted six days before he was applied for"
expect_sql "SELECT count(*) FROM animal WHERE name IN ('Cooper','Copper')" \
  2 "Cooper and Copper are both kept, not merged"
expect_sql "SELECT status FROM animal WHERE name='Maisie'" \
  NOT_ADOPTABLE "Maisie, who may or may not be in a home, is held back"
expect_sql "SELECT (not_adoptable_reason IS NOT NULL)::text FROM animal WHERE name='Maisie'" \
  true "with the contradiction recorded as the reason"

# The rescue's own total was wrong. The import must not have copied the error.
expect_sql "SELECT sum(amount)::int FROM donation WHERE kind='MONEY'" \
  1075 "the cash donations add up to 1075, not the 1025 typed on the sheet"

# Nothing invented: the columns the spreadsheet does not have stay empty.
expect_sql "SELECT count(*) FROM animal WHERE sex IS NOT NULL" 0 "no sexes were invented"
expect_sql "SELECT count(*) FROM animal WHERE kennel_id IS NOT NULL" 0 "no kennels were invented"
expect_sql "SELECT count(*) FROM home_visit" 0 "no home visits were invented"
expect_sql "SELECT count(*) FROM placement" 0 "no placements were invented"
expect_sql "SELECT count(*) FROM diet" 0 "no feeding portions were invented"
expect_sql "SELECT count(*) FROM decision WHERE outcome='APPROVE'" 0 "no approvals were invented"

# Both sides of a bonded pair know about it, which the spreadsheet could not manage.
expect_sql "SELECT count(*) FROM animal WHERE name='Pearl' AND bond_group_id IS NOT NULL" \
  1 "Pearl knows she is bonded, though only Otis's row said so"

# The verbatim spreadsheet is still there to be compared against.
expect_sql "SELECT count(*) FROM rhrms_import.animal_row" 47 "the original rows are kept verbatim"
expect_sql "SELECT count(*) FROM rhrms_import.food_row WHERE how_many='?'" \
  1 "including the quantity somebody typed as a question mark"
expect_sql "SELECT count(*) FROM rhrms_import.decision_log WHERE ask_director" \
  20 "and 20 questions are queued for the director"

# ===================================================================== 4
section "4. The director's vetting override (PL-3)"

# The second executeUpdate-on-a-SELECT bug lived here. Nothing in the suite had ever asked a
# director to place an unvetted animal, so nothing had ever run the line.
#
# Every animal in the spreadsheet is NOT_VETTED, because vetting was never written down. So
# this is not a contrived case: it is what happens the first time somebody adopts one of the
# 32 animals this import just loaded.
CODE="$(sql "SELECT animal_code FROM animal WHERE status='AVAILABLE' AND vet_status='NOT_VETTED'
             ORDER BY id LIMIT 1")"
ANIMAL="$(sql "SELECT id FROM animal WHERE animal_code='$CODE'")"
[[ -n "$ANIMAL" ]] && pass "there is an unvetted animal to try this with ($CODE)" \
                   || fail "there is an unvetted animal to try this with"

post "$TOKEN" /api/people '{"firstName":"Test","lastName":"Adopter","phone":"303-555-0100"}'
expect_status 201 "an applicant can be added"
PERSON="$(field person.id)"

post "$TOKEN" /api/applications "{\"personId\":$PERSON,\"animalId\":$ANIMAL,
  \"housingType\":\"HOUSE\",\"hasYard\":true,\"yardFenced\":true,\"childrenCount\":0,
  \"landlordAllowsPets\":true,\"elderlyInHome\":false,\"adultsInHome\":\"one adult\",
  \"reasonForAdopting\":\"Wants a companion and has kept dogs before.\"}"
expect_status 201 "an application can be submitted"
APP="$(field application.id)"

post "$TOKEN" "/api/applications/$APP/visit" '{"visitorName":"Diane",
  "childrenPresent":false,"yard":true,"yardFenced":true,"elderlyResidents":false,
  "adultMen":1,"adultWomen":1,"housingConfirmed":true,"recommendation":"APPROVE",
  "notes":"Fenced garden, gate bolted, no children, previous dog lived to fourteen."}'
expect_status 201 "a home visit can be recorded"

post "$TOKEN" "/api/applications/$APP/decision" '{"outcome":"APPROVE",
  "rationale":"Good fences and experience with dogs; no concerns from the visit."}' 'Director#2026'
expect_ok "the director can approve it"

# Without the override, PL-3 must refuse: the animal has not been vetted.
post "$TOKEN" "/api/applications/$APP/place" \
  '{"paymentMethod":"CASH","feeCharged":250}' 'Director#2026'
expect_refused "placing an unvetted animal is refused"
expect_in "not been vetted" "and says so in words"

# An override with no explanation is refused too: the whole point of stepping past a safety
# rule is that the reason ends up in the audit log.
post "$TOKEN" "/api/applications/$APP/place" \
  '{"paymentMethod":"CASH","feeCharged":250,"overrideVetting":true}' 'Director#2026'
expect_refused "an override with no reason is refused"

# With both, the director may proceed - and this is the line that used to throw.
post "$TOKEN" "/api/applications/$APP/place" \
  '{"paymentMethod":"CASH","feeCharged":250,"overrideVetting":true,
    "overrideReason":"Vetting was never recorded on the old spreadsheet; vet appointment booked."}' \
  'Director#2026'
expect_status 201 "the director's override goes through"
expect_sql "SELECT status FROM animal WHERE id=$ANIMAL" ADOPTED "and the animal is adopted"
# The reason lands on every row the placement touched - the placement, the animal, its stay
# and the other applications that closed - which is the point of putting it on the
# transaction rather than on one record. Checking the placement row specifically.
expect_sql "SELECT count(*) FROM audit_log
            WHERE table_name='placement' AND reason ILIKE '%vetting block overridden%'" \
  1 "with the override's reason on the placement record in the audit log"
expect_sql "SELECT (count(*) > 1)::text FROM audit_log
            WHERE reason ILIKE '%never recorded on the old%'" \
  true "and on every other row that one transaction changed"

report
