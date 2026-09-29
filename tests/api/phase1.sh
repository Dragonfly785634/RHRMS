#!/usr/bin/env bash
# =============================================================================
# RHRMS - Phase 1 acceptance tests
#
# One section per item in the Phase 1 scope. Each one drives the real server over HTTP, the same
# way the terminal does, against a freshly built database with the sample data loaded.
#
#    1  Animal intake                name, species, breed, approximate age, medical notes, diet
#    2  Status lifecycle             the allowed changes, and the refused ones
#    3  Available listing and search what can be offered to the public, searchable
#    4  Adoption application         an application for a particular animal
#    5  Application decision         approve or deny, with the reason recorded
#    6  Adoption placement           the animal's placement after adoption
#    7  Supply items                 type, unit, quantity
#    8  Stock movements              receiving and dispensing
#    9  Dietary link                 an animal's requirement tied to a supply type
#   10  Inventory view               on hand, against what the animals in care need
#
# Each item is checked both ways round: the main flow works, AND the wrong thing is refused with a
# sentence somebody at the front desk could act on. A feature that cannot say no is not reliable.
#
#   ./tests/api/phase1.sh            all ten
#   ./tests/api/phase1.sh 4 5 6      only those items
# =============================================================================
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_tools

WANTED=("$@")
want() {
  [[ ${#WANTED[@]} -eq 0 ]] && return 0
  local item
  for item in "${WANTED[@]}"; do [[ "$item" == "$1" ]] && return 0; done
  return 1
}

echo
_bold "RHRMS Phase 1 acceptance tests"
_dim  "server $RHRMS_API_URL   database $RHRMS_DB on $PGHOST:$PGPORT"
fresh

MIKE=$(login mike   'Director#2026')
DIANE=$(login diane 'Staff#Diane26')
SAM=$(login sam     'Volunteer#Sam26')
if [[ -z "$MIKE" || -z "$DIANE" || -z "$SAM" ]]; then
  _red "Could not log the three sample accounts in. Is the sample data loaded?"
  exit 2
fi
_dim  "logged in as mike (director), diane (staff), sam (volunteer)"

# =============================================================================
# 1. ANIMAL INTAKE
# =============================================================================
if want 1; then
section "1. Animal intake"

# A partner for the BONDED WITH box. Deliberately NOT one of the sample animals: bonding Rex to
# Otis would put Rex into Otis and Pearl's pair, and the placement tests further down would then
# be trying to rehome three animals, one of them unvetted. Which is correct behaviour, and a
# rotten way to find out about it.
post "$SAM" /api/animals/intake '{"species":"DOG","name":"Bondmate","intakeSource":"STRAY",
  "kennelId":9,"vetStatus":"VETTED","duplicateAcknowledged":true}'
expect_status 201 "an animal for the BONDED WITH box"
BONDMATE=$(field animal.id)

# Every box on paper form RH-1 in one request, by a volunteer, who is who fills the form in.
post "$SAM" /api/animals/intake "{
  \"name\":\"Rex\", \"cameInAs\":\"Rusty\", \"kennelId\":10,
  \"species\":\"DOG\", \"breed\":\"Beagle\", \"colorMarkings\":\"tricolour\", \"sex\":\"M\",
  \"ageYears\":7, \"ageIsExact\":true, \"birthDate\":\"2019-04-01\",
  \"timeIn\":\"09:15\",
  \"intakeSource\":\"OWNER_SURRENDER\",
  \"bondedWithAnimalId\":$BONDMATE,
  \"generalNotes\":\"Limps on the left hind leg; vet looked at it on arrival.\",
  \"takenInBy\":\"Casey\",
  \"foodProductId\":1, \"cupsPerMeal\":2, \"mealsPerDay\":2,
  \"duplicateAcknowledged\":true }"
expect_status 201 "an intake with every box on form RH-1 is recorded"
REX=$(field animal.id)
REX_CODE=$(field animal.animal_code)
expect_in "RH-0" "the animal is given a permanent animal code"
expect_field animal.name "Rex" "the name is stored"
expect_field animal.breed "Beagle" "the breed is stored"
expect_field animal.birth_date_is_estimate false "an owner surrender records an EXACT date of birth"
expect_in "Eats Adult Dog Kibble" "the dietary requirement is recorded with the intake"
expect_in "Came in as Rusty" "CAME IN AS is recorded"
expect_in "Bonded with" "BONDED WITH is recorded"
expect_in "Taken in by Casey" "TAKEN IN BY is recorded"
expect_field formNumber "$REX_CODE" "the reply gives the No. to write on the paper form"

# Read it back: every box on the form has to survive the round trip.
get "$SAM" "/api/animals/$REX"
expect_status 200 "the new animal can be read back"
expect_field animal.species "DOG" "TYPE survived the round trip"
expect_field animal.breed "Beagle" "BREED survived the round trip"
expect_field animal.color_markings "tricolour" "the description survived the round trip"
expect_in "Limps on the left hind leg" "the NOTES box survived the round trip"
expect_field diets.0.food "Adult Dog Kibble" "the dietary requirement is linked to a supply item"
expect_field animal.kennel_id "10" "KENNEL NO. is what it was put in"
expect_field stays.0.intake_source "OWNER_SURRENDER" "WHERE FROM is recorded as a stay"
expect_field stays.0.intake_time "09:15" "TIME IN is recorded alongside DATE IN"
expect_field animal.birth_date_is_estimate false "AGE IS: exact was ticked, so it is not an estimate"
expect_in "Rusty" "CAME IN AS is kept in the animal's name history"
expect_field bondPartners.0.name "Bondmate" "BONDED WITH linked the two animals"

# The form separates who met the animal from who typed it in, and so does the audit trail.
get "$MIKE" "/api/audit?table=animal&rowId=$REX"
expect_field history.0.on_behalf_of "Casey" "TAKEN IN BY is recorded as acting on their behalf"
expect_field history.0.who "Sam (volunteer)" "ENTERED IN SPREADSHEET BY is the logged-in user"

# An old name has to stay findable, which is the whole point of CAME IN AS.
get "$SAM" '/api/animals?q=Rusty'
expect_field animals.0.animal_code "$REX_CODE" "the animal can be found under the name it came in as"

# The boxes that only make sense in one situation.
post "$SAM" /api/animals/intake '{"species":"DOG","name":"Orphan","intakeSource":"STRAY",
  "kennelId":14,"motherAnimalId":5,"duplicateAcknowledged":true}'
expect_refused "a mother cannot be recorded for an animal that was not born here"

# TIME IN is a blank line on the paper form, so it arrives in whatever shape the person writes.
# Refusing all but one of those, at the end of a long form, is how you make people stop filling
# the form in. Every one of these is stored as HH:MM.
time_in_stores() {  # time_in_stores <typed> <expected> 
  post "$SAM" /api/animals/intake "{\"species\":\"DOG\",\"intakeSource\":\"STRAY\",
    \"kennelId\":$TIME_KENNEL,\"timeIn\":\"$1\",\"duplicateAcknowledged\":true}"
  local id; id="$(field animal.id)"
  TIME_KENNEL=$((TIME_KENNEL + 1))
  if [[ -z "$id" ]]; then
    fail "TIME IN '$1' was refused outright"
    return
  fi
  get "$SAM" "/api/animals/$id"
  expect_field stays.0.intake_time "$2" "TIME IN written as '$1' is stored as $2"
}
# Kennels 30 upwards: sections 2, 5, 9 and 10 use 20-25, and an intake into an occupied kennel
# is refused, which would look like a TIME IN failure and is not.
TIME_KENNEL=30
time_in_stores "09:15"    "09:15"
time_in_stores "9:15"     "09:15"
time_in_stores "9:15 AM"  "09:15"
time_in_stores "0915"     "09:15"
time_in_stores "9.15"     "09:15"
time_in_stores "2:30 pm"  "14:30"
time_in_stores "1430"     "14:30"
time_in_stores "12:30 am" "00:30"
time_in_stores "23:40"    "23:40"

# ...but a time nobody could act on is still refused, because a guessed time is worse than none.
post "$SAM" /api/animals/intake '{"species":"DOG","intakeSource":"STRAY","kennelId":40,
  "timeIn":"some time in the morning","duplicateAcknowledged":true}'
expect_refused "a TIME IN that is not a time at all is refused"
post "$SAM" /api/animals/intake '{"species":"DOG","intakeSource":"STRAY","kennelId":40,
  "timeIn":"25:00","duplicateAcknowledged":true}'
expect_refused "an hour that does not exist is refused"
expect_in "no hour 25" "and says which part is wrong"
post "$SAM" /api/animals/intake '{"species":"DOG","intakeSource":"STRAY","kennelId":40,
  "timeIn":"9:75","duplicateAcknowledged":true}'
expect_refused "a minute that does not exist is refused"

post "$SAM" /api/animals/intake '{"species":"CAT","name":"Ghostbond","intakeSource":"STRAY",
  "kennelId":14,"bondedWithAnimalId":999999,"duplicateAcknowledged":true}'
expect_refused "bonding with an animal that does not exist is refused"

# MOTHER (IF BORN HERE): the animal joins the mother's kennel and her litter. Luna is in
# kennel 3 on her own; Maple's kennel already holds a different litter, and IN-2 rightly refuses
# to mix two litters in one kennel (DECISIONS.md D-12).
post "$SAM" /api/animals/intake '{"species":"CAT","name":"Bornhere","intakeSource":"BORN_IN_CARE",
  "kennelId":3,"motherAnimalId":4,"ageMonths":1,"duplicateAcknowledged":true}'
expect_status 201 "an animal born here can record its mother"
expect_in "Born here to Luna" "and the reply names her"
BORNHERE=$(field animal.id)
get "$SAM" "/api/animals/$BORNHERE"
expect_in '"litter_id":' "it is linked to a litter"
expect_field animal.kennel_id "3" "and is in the mother's kennel"

# Two litters in one kennel is refused, which is what keeps unrelated animals apart.
post "$SAM" /api/animals/intake '{"species":"CAT","name":"Crowded","intakeSource":"BORN_IN_CARE",
  "kennelId":4,"motherAnimalId":4,"ageMonths":1,"duplicateAcknowledged":true}'
expect_refused "a second litter cannot be put in a kennel that already holds another"

# An approximate age, which is what a stray gets.
post "$SAM" /api/animals/intake '{
  "species":"CAT","name":"Smudge","intakeSource":"STRAY","ageMonths":8,"kennelId":11,
  "duplicateAcknowledged":true }'
expect_status 201 "a stray can be recorded with an approximate age instead of a date"
SMUDGE=$(field animal.id)
expect_field animal.birth_date_is_estimate true "an approximate age is marked as an estimate"

# ...and the refusals.
post "$SAM" /api/animals/intake '{"name":"Nameless","intakeSource":"STRAY","kennelId":12}'
expect_refused "an intake with no species is refused"

post "$SAM" /api/animals/intake '{"species":"DOG","intakeSource":"STRAY","kennelId":12,
  "duplicateAcknowledged":true}'
expect_status 201 "an animal with no name yet can still be taken in"
UNNAMED=$(field animal.id)

post "$SAM" /api/animals/intake '{"species":"DOG","name":"Squatter","intakeSource":"STRAY",
  "kennelId":2,"duplicateAcknowledged":true}'
expect_refused "an intake into a kennel that already holds a stranger is refused"
expect_in "occupied" "the refusal says the kennel is occupied and who is in it"

post "$SAM" /api/animals/intake '{"species":"DOG","name":"Nowhere","intakeSource":"STRAY",
  "duplicateAcknowledged":true}'
expect_refused "an intake with no kennel is refused while kennels are free"

post "$SAM" /api/animals/intake '{"species":"DOG","name":"Timelord","intakeSource":"STRAY",
  "kennelId":13,"birthDate":"2099-01-01","duplicateAcknowledged":true}'
expect_refused "a date of birth in the future is refused"

post "$SAM" /api/animals/intake '{"species":"MONSTER","name":"Grendel","intakeSource":"STRAY",
  "kennelId":13,"duplicateAcknowledged":true}'
expect_refused "a species that is not one of the known ones is refused"

# The duplicate check: a second animal with a name already on file has to be confirmed.
post "$SAM" /api/animals/intake '{"species":"DOG","name":"Rex","intakeSource":"STRAY","kennelId":13}'
expect_status 409 "a second animal with a name already on file is held back for a check"
expect_code POSSIBLE_DUPLICATE "the hold-back says it might be a duplicate"
expect_in "$REX_CODE" "it names the animal it might be a duplicate of"

post "$SAM" /api/animals/intake '{"species":"DOG","name":"Rex","intakeSource":"STRAY","kennelId":13,
  "duplicateAcknowledged":true,"duplicateChoice":"different animal"}'
expect_status 201 "once the check is acknowledged the intake goes through"
fi

# =============================================================================
# 2. STATUS LIFECYCLE
# =============================================================================
if want 2; then
section "2. Status lifecycle"

post "$SAM" /api/animals/intake '{"species":"CAT","name":"Cycle","intakeSource":"STRAY",
  "kennelId":20,"duplicateAcknowledged":true}'
CYCLE=$(field animal.id)
expect_status 201 "an animal to follow through its statuses"
expect_field animal.status "AVAILABLE" "an animal starts AVAILABLE"
expect_field animal.vet_status "NOT_VETTED" "and starts NOT_VETTED"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"NOT_ADOPTABLE",
  "notAdoptableReason":"Needs dental work before being listed","reason":"Vet advice"}'
expect_status 200 "AVAILABLE to NOT_ADOPTABLE, with a reason"
expect_field animal.status "NOT_ADOPTABLE" "the status changed"
expect_in "Needs dental work" "the reason it is not adoptable is stored on the record"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"AVAILABLE","reason":"Dental work done"}'
expect_status 200 "NOT_ADOPTABLE back to AVAILABLE"
expect_field animal.status "AVAILABLE" "the status changed back"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"NOT_ADOPTABLE","reason":"No reason given"}'
expect_refused "NOT_ADOPTABLE with no reason is refused"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"ADOPTED","reason":"Skipping the process"}'
expect_refused "AVAILABLE straight to ADOPTED is refused: it has to go through an adoption"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"MAGNIFICENT","reason":"Testing"}'
expect_refused "a status that does not exist is refused"

post "$SAM" "/api/animals/$CYCLE/status" '{"status":"NOT_ADOPTABLE",
  "notAdoptableReason":"Trying it as a volunteer","reason":"Testing"}'
expect_status 403 "a volunteer cannot change a status"

# DECEASED is the one that cannot be undone, so it asks for the password again.
post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"DECEASED","reason":"Died overnight"}'
expect_code ACTION_DENIED "marking an animal deceased needs the password re-typed"
get "$SAM" "/api/animals/$CYCLE"
expect_field animal.status "AVAILABLE" "the refused change left the animal exactly as it was"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"DECEASED","reason":"Died overnight"}' 'wrong'
expect_code ACTION_DENIED "a wrong password is refused"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"DECEASED","reason":"Died overnight"}' 'Staff#Diane26'
expect_status 200 "with the right password it goes through"
expect_field animal.status "DECEASED" "the animal is recorded as deceased"
expect_field animal.kennel_id "" "and its kennel is freed"

post "$DIANE" "/api/animals/$CYCLE/status" '{"status":"AVAILABLE","reason":"Undo"}'
expect_refused "a deceased animal cannot be brought back to AVAILABLE"
fi

# =============================================================================
# 3. AVAILABLE-ANIMAL LISTING AND SEARCH
# =============================================================================
if want 3; then
section "3. Available-animal listing and search"

get "$SAM" /api/animals/available
expect_status 200 "the available list can be read"
expect_in "Otis"  "an available animal is on the list"
expect_in "Pearl" "so is its bonded partner"
expect_in "Luna"  "and an available cat"
expect_not_in "Maple" "an animal that is not adoptable is NOT on the public list"

# Bella is on hold for an approved applicant, so she is not on offer to a walk-in.
get "$SAM" /api/animals/available
expect_not_in "Bella" "an animal on hold for an approved applicant is NOT on the list"

get "$SAM" '/api/animals/available?q=otis'
expect_field count 1 "search by name finds exactly one"
expect_field available.0.name "Otis" "and finds the right one"

get "$SAM" '/api/animals/available?q=bernard'
expect_field count 2 "search by breed finds both St. Bernards"

get "$SAM" '/api/animals/available?q=princess'
expect_field count 1 "an animal can still be found under a name it used to have"
expect_field available.0.name "Luna" "and comes back under its current name"

get "$SAM" '/api/animals/available?species=CAT'
expect_in "Luna" "the list can be narrowed to one species"
expect_not_in "Otis" "and leaves the other species out"

get "$SAM" '/api/animals/available?q=zzzznothing'
expect_field count 0 "a search that matches nothing says so rather than failing"

# Taking an animal off the list has to actually take it off the list.
post "$DIANE" /api/animals/2/status '{"status":"NOT_ADOPTABLE",
  "notAdoptableReason":"Kennel cough, in isolation","reason":"Vet advice"}'
expect_status 200 "an animal is marked not adoptable"
get "$SAM" /api/animals/available
expect_not_in "Otis" "it disappears from the public list at once"
post "$DIANE" /api/animals/2/status '{"status":"AVAILABLE","reason":"Recovered"}'
get "$SAM" /api/animals/available
expect_in "Otis" "and comes back when it is available again"

# A restriction has to travel with the animal on the list: it decides who may take it.
get "$SAM" '/api/animals?q=Bella'
expect_field animals.0.active_restrictions 1 "the general search shows an animal's restriction count"
fi

# =============================================================================
# 4. ADOPTION APPLICATION
# =============================================================================
if want 4; then
section "4. Adoption application"

post "$DIANE" /api/people '{"firstName":"Ann","lastName":"Applicant","phone":"970-555-0101"}'
expect_status 201 "an applicant can be added"
ANN=$(field person.id)

post "$SAM" /api/applications "{\"personId\":$ANN,\"animalId\":4,
  \"housingType\":\"HOUSE\",\"hasYard\":true,\"yardFenced\":true,\"childrenCount\":0,
  \"elderlyInHome\":false,\"adultsInHome\":\"1 woman\",
  \"reasonForAdopting\":\"Company for an older cat already at home\"}"
expect_status 201 "an application for a particular animal is recorded"
ANN_APP=$(field application.id)
expect_field application.status "SUBMITTED" "a new application starts SUBMITTED"
expect_field application.animal_id 4 "it is tied to the animal applied for"

get "$SAM" "/api/applications/$ANN_APP"
expect_status 200 "the application can be read back"
expect_field application.applicant "Ann Applicant" "with the applicant's name"
expect_in "Company for an older cat" "and the answers from the paper form"

post "$SAM" /api/applications "{\"personId\":$ANN,\"animalId\":4}"
expect_refused "the same person cannot have two open applications for the same animal"

post "$SAM" /api/applications "{\"personId\":$ANN,\"animalId\":5}"
expect_refused "an application for an animal that is not adoptable is refused"
expect_in "not adoptable" "and says why"

post "$SAM" /api/applications "{\"personId\":999999,\"animalId\":4}"
expect_refused "an application for a person who does not exist is refused"

post "$SAM" /api/applications "{\"personId\":$ANN,\"animalId\":999999}"
expect_refused "an application for an animal that does not exist is refused"

post "$SAM" /api/applications "{\"animalId\":4}"
expect_refused "an application with no applicant is refused"

# A bonded pair: one application covers the group, and the desk is told so.
post "$DIANE" /api/people '{"firstName":"Bob","lastName":"Bonded"}'
BOB=$(field person.id)
post "$SAM" /api/applications "{\"personId\":$BOB,\"animalId\":2}"
expect_status 201 "an application for one of a bonded pair is accepted"
expect_in "bonded" "and says the animals are adopted together"

# Two people may apply for the same animal: best fit wins, not first come.
post "$DIANE" /api/people '{"firstName":"Cate","lastName":"Competing"}'
CATE=$(field person.id)
post "$SAM" /api/applications "{\"personId\":$CATE,\"animalId\":4}"
expect_status 201 "a second person may apply for the same animal"
get "$SAM" '/api/applications?animal=4&open=true'
expect_field count 2 "both applications are open at once"
fi

# =============================================================================
# 5. APPLICATION DECISION
# =============================================================================
if want 5; then
section "5. Application decision"

# Build a clean case: an animal with a no-children restriction, and a home with a child in it.
post "$SAM" /api/animals/intake '{"species":"DOG","name":"Nipper","breed":"Terrier",
  "intakeSource":"STRAY","ageYears":4,"kennelId":21,"vetStatus":"VETTED",
  "behaviorNotes":"Snapped at a child at the previous home","duplicateAcknowledged":true}'
NIPPER=$(field animal.id)
expect_status 201 "an animal for the decision tests"

post "$DIANE" "/api/animals/$NIPPER/restrictions" '{"type":"NO_CHILDREN",
  "detail":"Snapped at a child; no homes with children","reason":"Recorded at intake"}'
expect_status 201 "a restriction is recorded against it"

post "$DIANE" /api/people '{"firstName":"Dana","lastName":"Decided"}'
DANA=$(field person.id)
post "$SAM" /api/applications "{\"personId\":$DANA,\"animalId\":$NIPPER,
  \"childrenCount\":1,\"youngestChildAge\":4,\"hasYard\":true,\"yardFenced\":true}"
DANA_APP=$(field application.id)
expect_status 201 "somebody applies for it"

# No visit yet, so an approval is impossible.
post "$MIKE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"APPROVE","rationale":"They seem like a nice family to me."}' 'Director#2026'
expect_refused "an application cannot be approved before a home visit"

post "$SAM" "/api/applications/$DANA_APP/visit" '{"visitorName":"Sam","childrenPresent":true,
  "youngestChildAge":4,"yard":true,"yardFenced":true,"elderlyResidents":false,
  "adultMen":1,"adultWomen":1,"housingConfirmed":true,"recommendation":"UNSURE",
  "notes":"Kind family and a good fenced yard, but there is a four-year-old boy at home."}'
expect_status 201 "a home visit is recorded"
expect_field application.status "UNDER_REVIEW" "and the application moves to UNDER_REVIEW"
expect_in "NO_CHILDREN" "the visit reports the restriction conflict it created"

get "$MIKE" "/api/applications/$DANA_APP/conflicts"
expect_field count 1 "the conflict is there for the director to see"
expect_in "Children in home" "and says plainly what the conflict is"

# Who may decide.
post "$SAM" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"DENY","denialReason":"OTHER","rationale":"I do not think they are suitable."}' 'Volunteer#Sam26'
expect_status 403 "a volunteer cannot decide an application"
post "$DIANE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"DENY","denialReason":"OTHER","rationale":"I do not think they are suitable."}' 'Staff#Diane26'
expect_status 403 "staff cannot decide an application either"

# The director, and the password.
post "$MIKE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"DENY","denialReason":"CHILDREN_UNSUITABLE","rationale":"Nipper snapped at a child before."}'
expect_code ACTION_DENIED "a decision with no password is denied"
post "$MIKE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"DENY","denialReason":"CHILDREN_UNSUITABLE","rationale":"Nipper snapped at a child before."}' 'wrong'
expect_code ACTION_DENIED "a decision with the wrong password is denied"

get "$MIKE" "/api/applications/$DANA_APP"
expect_field application.status "UNDER_REVIEW" "neither refusal changed the application"
expect_field decisions "[]" "and no decision was recorded"

# Approving over a conflict needs it written down.
post "$MIKE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"APPROVE","rationale":"They have a lovely fenced yard and seem very keen."}' 'Director#2026'
expect_refused "approving over a restriction conflict needs a written acknowledgement"
expect_in "conflict" "and the refusal says which conflict"

# A denial, which is the right answer here.
post "$MIKE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"DENY","denialReason":"CHILDREN_UNSUITABLE",
    "rationale":"Nipper snapped at a child at his last home and there is a four-year-old here."}' 'Director#2026'
expect_status 201 "the director denies it, with a reason"
get "$MIKE" "/api/applications/$DANA_APP"
expect_field application.status "DENIED" "the application is denied"
expect_field decisions.0.outcome "DENY" "the decision is on the record"
expect_field decisions.0.denial_reason "CHILDREN_UNSUITABLE" "with its reason category"
expect_in "snapped at a child at his last home" "and the director's own words"
expect_in "NO_CHILDREN" "and the conflicts exactly as they were at the time"

post "$MIKE" "/api/applications/$DANA_APP/decision" \
  '{"outcome":"DENY","denialReason":"OTHER","rationale":"x"}' 'Director#2026'
expect_refused "a rationale of one character is refused"

# An approval that is right: no children this time.
post "$DIANE" /api/people '{"firstName":"Evan","lastName":"Empty-nest"}'
EVAN=$(field person.id)
post "$SAM" /api/applications "{\"personId\":$EVAN,\"animalId\":$NIPPER,\"childrenCount\":0}"
EVAN_APP=$(field application.id)
post "$SAM" "/api/applications/$EVAN_APP/visit" '{"visitorName":"Sam","childrenPresent":false,
  "yard":true,"yardFenced":true,"elderlyResidents":false,"adultMen":1,"adultWomen":1,
  "housingConfirmed":true,"recommendation":"APPROVE",
  "notes":"Two quiet adults, fenced acreage, no children at all. A good fit for a snappy terrier."}'
expect_status 201 "a second applicant is visited"
get "$MIKE" "/api/applications/$EVAN_APP/conflicts"
expect_field count 0 "this home creates no conflict"

post "$MIKE" "/api/applications/$EVAN_APP/decision" \
  '{"outcome":"APPROVE","rationale":"No children, fenced acreage, and the visitor recommended it."}' 'Director#2026'
expect_status 201 "the director approves the applicant who fits"
expect_in "PENDING_ADOPTION" "and the animal goes on hold"
get "$SAM" "/api/animals/$NIPPER"
expect_field animal.status "PENDING_ADOPTION" "the animal is on hold for them"

# The denied applicant is still on file, with the reason, for next time.
get "$DIANE" "/api/people/$DANA"
expect_in "CHILDREN_UNSUITABLE" "the denied applicant's record still shows why"
expect_in "may apply again" "and says whether they may apply again"
fi

# =============================================================================
# 6. ADOPTION PLACEMENT
# =============================================================================
if want 6; then
section "6. Adoption placement"

# Bella comes with the sample data already approved for Jamie Lee, and has a third person
# waiting, so the placement has something to close.
post "$DIANE" /api/people '{"firstName":"Frank","lastName":"Waiting"}'
FRANK=$(field person.id)
post "$SAM" /api/applications "{\"personId\":$FRANK,\"animalId\":1}"
FRANK_APP=$(field application.id)
expect_status 201 "a third person is still waiting for Bella"

get "$DIANE" '/api/applications?animal=1&status=APPROVED'
BELLA_APP=$(field applications.0.id)
expect_field count 1 "Bella has exactly one approved application"

post "$SAM" "/api/applications/$BELLA_APP/place" '{"paymentMethod":"CASH"}' 'Volunteer#Sam26'
expect_status 403 "a volunteer cannot record an adoption"

post "$DIANE" "/api/applications/$FRANK_APP/place" '{"paymentMethod":"CASH"}' 'Staff#Diane26'
expect_refused "an adoption cannot be recorded against an application that was never approved"

post "$DIANE" "/api/applications/$BELLA_APP/place" '{"paymentMethod":"CASH","feeCharged":100}' 'Staff#Diane26'
expect_refused "a fee different from the baseline needs a reason"
expect_in "250.00" "and the refusal says what the baseline is"

post "$DIANE" "/api/applications/$BELLA_APP/place" '{"paymentMethod":"WAIVED","feeCharged":100,
  "feeReason":"Testing"}' 'Staff#Diane26'
expect_refused "a waived fee that is not zero is refused"

post "$DIANE" "/api/applications/$BELLA_APP/place" \
  '{"paymentMethod":"CASH","feeCharged":250}'
expect_code ACTION_DENIED "recording an adoption needs the password re-typed"

post "$DIANE" "/api/applications/$BELLA_APP/place" \
  '{"paymentMethod":"CASH","feeCharged":250}' 'wrong'
expect_code ACTION_DENIED "a wrong password is denied"

get "$SAM" /api/animals/1
expect_field animal.status "PENDING_ADOPTION" "none of that changed the animal"

# The real thing, with a reduced fee and a bag of prescription food going home.
post "$DIANE" "/api/applications/$BELLA_APP/place" \
  '{"paymentMethod":"CASH","feeCharged":150,
    "feeReason":"Senior adopter discount agreed with the director",
    "vetClinicTold":"Ruby Hill Veterinary",
    "prescriptionFood":[{"animalId":1,"foodProductId":3,"locationId":1,"bags":1}]}' 'Staff#Diane26'
expect_status 201 "the adoption is recorded"
expect_field animalsPlaced 1 "one animal was placed"
expect_field placements.0.fee_charged "150.00" "the fee actually charged is stored"
expect_in "Senior adopter discount" "with the reason it differed from the baseline"
expect_in "Ruby Hill Veterinary" "and the vet clinic the adopter was told about"

get "$SAM" /api/animals/1
expect_field animal.status "ADOPTED" "the animal is now adopted"
expect_field animal.kennel_id "" "its kennel is freed"
expect_field stays.0.end_reason "PLACED" "its stay is ended as placed"
expect_field placements.0.adopter "Jamie Lee" "the placement names the adopter"
# An animal that has gone home must stop drawing on the rescue's supplies (feeds item 10).
DIET_ENDED=$(printf '%s' "$BODY" | python3 -c '
import sys, json
d = json.load(sys.stdin, parse_float=str)
print(sum(1 for x in d["diets"] if x["valid_to"] is None))')
if [[ "$DIET_ENDED" == "0" ]]; then
  pass "its dietary requirement is ended, so it stops counting against supplies"
else
  fail "the adopted animal still has $DIET_ENDED current dietary requirement(s)"
fi

get "$DIANE" "/api/applications/$FRANK_APP"
expect_field application.status "CLOSED" "the applicant who was still waiting is closed"
expect_in "placed with another applicant" "with a reason that explains why"

post "$DIANE" "/api/applications/$BELLA_APP/place" \
  '{"paymentMethod":"CASH","feeCharged":250}' 'Staff#Diane26'
expect_refused "the same adoption cannot be recorded twice"

# A bonded pair goes as a unit, on one application and one fee.
post "$DIANE" /api/people '{"firstName":"Gita","lastName":"Bothdogs"}'
GITA=$(field person.id)
post "$SAM" /api/applications "{\"personId\":$GITA,\"animalId\":2,\"childrenCount\":0}"
GITA_APP=$(field application.id)
post "$SAM" "/api/applications/$GITA_APP/visit" '{"visitorName":"Sam","childrenPresent":false,
  "yard":true,"yardFenced":true,"elderlyResidents":false,"adultMen":1,"adultWomen":1,
  "housingConfirmed":true,"recommendation":"APPROVE",
  "notes":"Big fenced yard and room for two very large dogs. Happy to take both."}'
post "$MIKE" "/api/applications/$GITA_APP/decision" \
  '{"outcome":"APPROVE","rationale":"Plenty of space for a bonded pair of St. Bernards."}' 'Director#2026'
expect_status 201 "a bonded pair is approved on one application"
post "$DIANE" "/api/applications/$GITA_APP/place" '{"paymentMethod":"CHECK","feeCharged":250}' 'Staff#Diane26'
expect_status 201 "and placed on one application"
expect_field animalsPlaced 2 "both animals of the pair are placed together"
expect_in "one fee covers the bonded pair" "with a single fee between them"
get "$SAM" /api/animals/3
expect_field animal.status "ADOPTED" "the partner is adopted too, without its own application"
fi

# =============================================================================
# 7. SUPPLY ITEMS
# =============================================================================
if want 7; then
section "7. Supply items"

get "$SAM" /api/food/products
expect_status 200 "the supply items can be listed"
expect_in "Adult Dog Kibble" "the list has the items from the sample data"
expect_field products.0.unit "bag" "each item records the unit it is counted in"
expect_in "quantity_on_hand" "each item reports a quantity on hand"

post "$DIANE" /api/food/products '{"name":"Puppy Kibble","kind":"REGULAR","species":"DOG",
  "unit":"sack","bagLbs":30,"lbsPerCup":0.28,"leadTimeDays":5,"reason":"New product"}'
expect_status 201 "a supply item can be added with its own unit"
PUPPY=$(field product.id)
expect_field product.kind "REGULAR" "its type is stored"
expect_field product.unit "sack" "its unit is stored"
expect_field product.bag_lbs "30.0" "and how much one unit weighs"
expect_in "counted in sacks of 30 lb" "the confirmation says how it will be counted"

post "$DIANE" /api/food/products '{"name":"Cat Tins","kind":"REGULAR","unit":"case",
  "bagLbs":12,"leadTimeDays":3,"reason":"New product"}'
expect_status 201 "a second item with a different unit"
TINS=$(field product.id)

post "$DIANE" /api/food/products '{"name":"Puppy Kibble","kind":"REGULAR","leadTimeDays":4,
  "reason":"Duplicate"}'
expect_refused "two supply items cannot share a name"

post "$DIANE" /api/food/products '{"name":"Weightless","kind":"REGULAR","bagLbs":0,
  "leadTimeDays":4,"reason":"Testing"}'
expect_refused "a unit that weighs nothing is refused"

post "$DIANE" /api/food/products '{"name":"Negative","kind":"REGULAR","leadTimeDays":-5,
  "reason":"Testing"}'
expect_refused "a negative lead time is refused"

post "$DIANE" /api/food/products '{"name":"Mystery","kind":"MAGIC","leadTimeDays":4,
  "reason":"Testing"}'
expect_refused "a type that is not regular or prescription is refused"

post "$SAM" /api/food/products '{"name":"Volunteer Kibble","kind":"REGULAR","leadTimeDays":4,
  "reason":"Testing"}'
expect_status 403 "a volunteer cannot add a supply item"

get "$DIANE" /api/food/products
PUPPY_VERSION=$(field products.0.version)
post "$DIANE" "/api/food/products/$PUPPY" "{\"unit\":\"bag\",\"version\":0,
  \"reason\":\"They come in bags after all\"}"
call "$DIANE" PATCH "/api/food/products/$PUPPY" '{"unit":"bag","version":0,
  "reason":"They come in bags after all"}'
expect_status 200 "the unit can be corrected"
expect_field product.unit "bag" "and the correction stuck"
fi

# =============================================================================
# 8. STOCK MOVEMENTS
# =============================================================================
if want 8; then
section "8. Stock movements"

# Receiving.
get "$SAM" /api/inventory
BEFORE=$(field supplies.0.quantity_on_hand)
post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"RECEIVE","bags":4,
  "reason":"Delivery arrived"}'
expect_status 201 "supplies can be received"
expect_in "sealedBagsLeftHere" "and the reply says how many are there now"
RECEIVED=$(field sealedBagsLeftHere)

post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"RECEIVE","bags":0,
  "reason":"Nothing"}'
expect_refused "receiving zero is refused"

post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"RECEIVE","bags":-3,
  "reason":"Negative"}'
expect_refused "receiving a negative number is refused"

post "$DIANE" /api/food/movements '{"foodProductId":999999,"locationId":1,"type":"RECEIVE","bags":1,
  "reason":"Nonexistent"}'
expect_refused "receiving against a supply item that does not exist is refused"

# Dispensing: opening a bag takes one out of sealed stock.
post "$SAM" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"OPEN","bags":1}'
expect_status 201 "a volunteer can open a bag, which dispenses it from sealed stock"
AFTER_OPEN=$(field sealedBagsLeftHere)
if [[ $((RECEIVED - 1)) -eq $AFTER_OPEN ]]; then
  pass "opening a bag reduced the sealed count by exactly one"
else
  fail "opening a bag: expected $((RECEIVED - 1)), got $AFTER_OPEN"
fi

# The guard that matters: you cannot dispense what is not there.
post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"WRITE_OFF","bags":999,
  "movementReason":"Trying to take more than exists"}' 'Staff#Diane26'
expect_refused "dispensing more than is on hand is refused"
expect_in "Not enough" "and the refusal says how many there really are"

get "$SAM" /api/food/products
STILL=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for p in json.load(sys.stdin)["products"]:
    if p["id"] == 2: print(p["quantity_on_hand"])')
if [[ "$STILL" == "$AFTER_OPEN" ]]; then
  pass "the refused dispense changed nothing at all"
else
  fail "the refused dispense altered the count ($AFTER_OPEN -> $STILL)"
fi

# Writing off needs a reason and the password: it is how stock disappears without leaving.
post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"WRITE_OFF","bags":1}' 'Staff#Diane26'
expect_refused "writing stock off with no reason is refused"
post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"WRITE_OFF","bags":1,
  "movementReason":"Mice got into it in the garage"}'
expect_code ACTION_DENIED "writing stock off needs the password re-typed"
post "$SAM" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"WRITE_OFF","bags":1,
  "movementReason":"Mice got into it in the garage"}' 'Volunteer#Sam26'
expect_status 403 "a volunteer cannot write stock off"
post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"WRITE_OFF","bags":1,
  "movementReason":"Mice got into it in the garage"}' 'Staff#Diane26'
expect_status 201 "with a reason and a password, a write-off goes through"

# A count correction records the difference rather than overwriting quietly.
post "$DIANE" /api/food/movements '{"foodProductId":2,"locationId":1,"type":"COUNT_ADJUST",
  "countedBags":7,"movementReason":"Monthly shelf count"}' 'Staff#Diane26'
expect_status 200 "a stock count can be corrected"
expect_field sealedBags 7 "to the number actually counted"
expect_in "difference of" "and the difference is recorded, not hidden"

# Every movement is on a ledger that can be read back.
get "$DIANE" '/api/food/movements?product=2'
expect_status 200 "the stock ledger can be read"
expect_in "RECEIVE" "receiving is on it"
expect_in "OPEN" "dispensing is on it"
expect_in "WRITE_OFF" "the write-off is on it"
expect_in "Mice got into it" "with the reason that was given"

# Prescription food can only be dispensed to an animal that is prescribed it.
post "$DIANE" /api/food/movements '{"foodProductId":3,"locationId":1,"type":"SEND_WITH_ANIMAL",
  "bags":1,"animalId":4,"reason":"Wrong animal"}'
expect_refused "prescription food cannot be dispensed to an animal it was not prescribed for"
fi

# =============================================================================
# 9. DIETARY LINK
# =============================================================================
if want 9; then
section "9. Dietary link"

post "$SAM" /api/animals/intake '{"species":"CAT","name":"Eater","intakeSource":"STRAY",
  "kennelId":22,"duplicateAcknowledged":true}'
EATER=$(field animal.id)
expect_status 201 "an animal with no dietary requirement yet"

get "$SAM" "/api/animals/$EATER"
expect_field diets "[]" "it starts with no dietary requirement"

post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":2,
  \"cupsPerMeal\":0.5,\"mealsPerDay\":2,\"reason\":\"Vet advice\"}"
expect_status 201 "an animal's dietary requirement is linked to a supply type"
expect_in "counted in the daily use" "and the reply says it now counts towards what is needed"

get "$SAM" "/api/animals/$EATER"
expect_field diets.0.food "Adult Cat Food" "the link shows on the animal's record"
expect_field diets.0.cups_per_meal "0.50" "with how much per meal"
expect_field diets.0.meals_per_day "2" "and how many meals a day"
expect_field diets.0.lbs_per_day "0.25" "which the system turns into pounds a day"

post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":999999,\"cupsPerMeal\":1}"
expect_refused "a dietary requirement cannot point at a supply item that does not exist"

post "$DIANE" /api/food/diets "{\"animalId\":999999,\"foodProductId\":2,\"cupsPerMeal\":1}"
expect_refused "nor at an animal that does not exist"

post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":2,\"cupsPerMeal\":0}"
expect_refused "a portion of zero is refused"

post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":2,\"cupsPerMeal\":40}"
expect_refused "an implausible portion is refused"

# Prescription food needs a prescription behind the link.
post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":3,\"cupsPerMeal\":1}"
expect_refused "prescription food cannot be linked to an animal without a prescription"

post "$DIANE" /api/food/prescriptions "{\"animalId\":$EATER,\"foodProductId\":3,
  \"reason\":\"Vet prescribed it\"}"
expect_status 201 "a vet prescription is recorded"
post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":3,\"cupsPerMeal\":1,
  \"reason\":\"Vet advice\"}"
expect_status 201 "and then the prescription food can be linked"

# Replacing a portion ends the old one rather than editing history.
post "$DIANE" /api/food/diets "{\"animalId\":$EATER,\"foodProductId\":2,\"cupsPerMeal\":1,
  \"reason\":\"Eating more now\"}"
expect_status 201 "a portion can be changed"
get "$SAM" "/api/animals/$EATER"
CURRENT=$(printf '%s' "$BODY" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print(sum(1 for x in d["diets"] if x["food_product_id"] == 2 and x["valid_to"] is None))')
if [[ "$CURRENT" == "1" ]]; then
  pass "only one portion of that food is current at a time"
else
  fail "expected 1 current portion of that food, found $CURRENT"
fi
fi

# =============================================================================
# 10. INVENTORY VIEW
# =============================================================================
if want 10; then
section "10. Inventory view"

get "$SAM" /api/inventory
expect_status 200 "the inventory view can be read"
expect_in "quantity_on_hand"   "it reports supplies on hand"
expect_in "lbs_needed_per_day" "and supplies needed by the animals in care"
expect_in "animalsInCare"      "and how many animals it is counting"

# The need has to come from the animals actually here, by name.
DOGFOOD=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["name"] == "Adult Dog Kibble":
        print("%s|%s|%s" % (s["quantity_on_hand"], s["lbs_needed_per_day"], s["eaten_by"] or ""))')
IFS='|' read -r DF_QTY DF_NEED DF_WHO <<< "$DOGFOOD"
if [[ -n "$DF_QTY" && -n "$DF_NEED" ]]; then
  pass "an item reports both what is on hand ($DF_QTY) and what is needed ($DF_NEED lb/day)"
else
  fail "the inventory view did not report on hand and needed for Adult Dog Kibble"
fi

get "$SAM" /api/inventory
expect_in "animalsWithNoDietaryRequirement" "it says how many animals have no requirement recorded"

# Adding a requirement must move the needed figure up.
NEED_BEFORE=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["id"] == 1: print(s["lbs_needed_per_day"])')
post "$SAM" /api/animals/intake '{"species":"DOG","name":"Hungry","intakeSource":"STRAY",
  "kennelId":25,"foodProductId":1,"cupsPerMeal":2,"mealsPerDay":2,"duplicateAcknowledged":true}'
expect_status 201 "a new animal arrives with a dietary requirement"
get "$SAM" /api/inventory
NEED_AFTER=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["id"] == 1: print(s["lbs_needed_per_day"])')
if python3 -c "import sys; sys.exit(0 if float('$NEED_AFTER') > float('$NEED_BEFORE') else 1)"; then
  pass "the needed figure went up ($NEED_BEFORE -> $NEED_AFTER lb/day)"
else
  fail "the needed figure did not go up ($NEED_BEFORE -> $NEED_AFTER)"
fi

# Dispensing must move the on-hand figure down.
QTY_BEFORE=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["id"] == 1: print(s["quantity_on_hand"])')
post "$SAM" /api/food/movements '{"foodProductId":1,"locationId":1,"type":"OPEN","bags":1}'
expect_status 201 "a bag is dispensed"
get "$SAM" /api/inventory
QTY_AFTER=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["id"] == 1: print(s["quantity_on_hand"])')
if [[ $((QTY_BEFORE - 1)) -eq $QTY_AFTER ]]; then
  pass "the on-hand figure went down by one ($QTY_BEFORE -> $QTY_AFTER)"
else
  fail "the on-hand figure is wrong after dispensing ($QTY_BEFORE -> $QTY_AFTER)"
fi

# An animal that leaves must stop counting towards what is needed.
get "$SAM" /api/inventory
CAT_NEED_BEFORE=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["id"] == 2: print(s["lbs_needed_per_day"])')
post "$DIANE" /api/animals/4/status '{"status":"DECEASED","reason":"Died of old age"}' 'Staff#Diane26'
expect_status 200 "an animal leaves care"
get "$SAM" /api/inventory
CAT_NEED_AFTER=$(printf '%s' "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin)["supplies"]:
    if s["id"] == 2: print(s["lbs_needed_per_day"])')
if python3 -c "import sys; sys.exit(0 if float('$CAT_NEED_AFTER') < float('$CAT_NEED_BEFORE') else 1)"; then
  pass "it stops counting towards what is needed ($CAT_NEED_BEFORE -> $CAT_NEED_AFTER lb/day)"
else
  fail "an animal that left is still counted ($CAT_NEED_BEFORE -> $CAT_NEED_AFTER)"
fi

# And the view has to be readable by everybody who might need to order food.
get "$SAM"   /api/inventory; expect_status 200 "a volunteer can see the inventory"
get "$DIANE" /api/inventory; expect_status 200 "staff can see the inventory"
get "$MIKE"  /api/inventory; expect_status 200 "the director can see the inventory"
get ""       /api/inventory; expect_status 401 "nobody can see it without logging in"
fi

report
