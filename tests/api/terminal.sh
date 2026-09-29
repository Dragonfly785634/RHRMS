#!/usr/bin/env bash
# =============================================================================
# RHRMS - terminal client tests
#
# The other suite drives the API with curl. This one drives the actual terminal, through a
# pseudo-terminal, because the client is what the front desk actually touches and it has its own
# failure modes the API tests cannot reach. Two real bugs were found here:
#
#   - the login prompt read through a BufferedReader while the password prompt used
#     System.console(), so the reader could swallow the password (fixed: one input path);
#   - start_server.sh reported success when an OLD server was holding the port, so tests ran
#     against stale code (fixed: it checks its own child is alive first).
#
# Both looked like everything was working, which is the only reason they lasted.
# =============================================================================
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_tools

if ! command -v script >/dev/null; then
  _red "SKIPPED: these tests need 'script' (util-linux) to fake a terminal."
  echo "On Ubuntu or WSL: sudo apt install -y bsdutils" >&2
  exit 0
fi

echo
_bold "RHRMS terminal tests"
_dim  "driving $RHRMS_JAR through a pseudo-terminal"
fresh

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/launch.sh" <<EOS
#!/usr/bin/env bash
exec java -cp "$RHRMS_CLASSPATH" org.rubyhill.rhrms.ClientMain
EOS
chmod +x "$WORK/launch.sh"

# Types the given lines into the terminal with a human-sized pause between them, so the client's
# prompts and the terminal's echo interleave the way they do for a person at the keyboard.
# Leaves everything the terminal printed in $SCREEN.
SCREEN=""
terminal() {
  local input="$WORK/in.txt"
  printf '%s\n' "$@" > "$input"
  ( while IFS= read -r line; do printf '%s\n' "$line"; sleep 0.12; done < "$input"; sleep 0.6 ) \
    | COLUMNS=84 script -qfc "$WORK/launch.sh" /dev/null > "$WORK/out.txt" 2>&1
  SCREEN="$(sed 's/\x1b\[[0-9;]*m//g' "$WORK/out.txt" | tr -d '\r')"
}

# saw <text> <label>  /  not_saw <text> <label>
saw()     { if grep -qiF -- "$1" <<< "$SCREEN"; then pass "$2"; else fail "$2 (no '$1' on screen)"; fi; }
not_saw() { if grep -qiF -- "$1" <<< "$SCREEN"; then fail "$2 (found '$1' on screen)"; else pass "$2"; fi; }

# ---------------------------------------------------------------- logging in
section "Logging in"

terminal "diane" "Staff#Diane26" "quit"
saw "RUBY HILL"            "the login screen shows the title"
saw "R H R M S"            "and the system's name"
saw "online"               "and says the server is reachable"
saw "Diane"                "a correct username and password get in"
saw "STAFF"                "and the header shows the role"

# This is the one that caught the BufferedReader bug: if the password prompt gets nothing, the
# terminal says goodbye instead of logging in.
not_saw "Wrong username or password" "the password prompt actually receives the password"

terminal "diane" "definitely-not-it" "quit"
saw "Wrong username or password" "a wrong password is refused"
not_saw "STAFF"                   "and does not get in"

# ------------------------------------------------------- the intake form RH-1
section "The intake form"

terminal "sam" "Volunteer#Sam26" "intake" ".q" "quit"
saw "ANIMAL INTAKE"            "the form shows the paper form's masthead"
saw "RH-1"                     "and its form number"
saw "ANIMAL NAME"              "and its first box"
saw "Left the form"            ".q leaves the form"
saw "Nothing was saved"        "and says nothing was saved"

terminal "sam" "Volunteer#Sam26" "intake" "Ghost" ".exit" "quit"
saw "Left the form"            ".exit leaves the form part-way through"
get "$(login sam 'Volunteer#Sam26')" '/api/animals?q=Ghost'
expect_field count 0          "and the half-filled animal was never saved"

terminal "sam" "Volunteer#Sam26" "intake" ".notacommand" ".help" ".q" "quit"
saw "is not a system command"  "a dot word that is not a command is refused"
saw "never taken as an answer" "and says a leading dot is never stored"
saw "System commands"          ".help lists the dot commands"
saw "leave this form"          "and says what .q does"

# The whole form, filled in the way the paper one reads.
terminal "sam" "Volunteer#Sam26" "intake" \
  "Scout" "Rusty" "7" \
  "1" "Collie, black and white" \
  "5" "" "2" "today" "09:15" \
  "2" "" \
  "Found loose on Platte River Dr, collar read Rusty." "" \
  "Casey" \
  "1" "2" "2" \
  "quit"
saw "Write RH-"                "a finished form gives the No. for the paper copy"
saw "Came in as Rusty"         "CAME IN AS was recorded"
saw "Taken in by Casey"        "TAKEN IN BY was recorded"

# A bad time has to be caught at the box, while it can still be fixed, not after the notes box
# has been typed. The form below deliberately fumbles TIME IN once and then corrects it.
terminal "sam" "Volunteer#Sam26" "intake" \
  "Tardy" "" "8" \
  "1" "Lurcher" \
  "3" "" "2" "today" "half past nine" "9:15 am" \
  "2" "" \
  "Came in late." "" \
  "" \
  "" \
  "y" \
  "quit"
# The second "y" answers the duplicate check: Scout, just above, is also a dog that arrived
# today, so IN-3 quite rightly asks whether this is the same animal.
saw "is not a time"   "a time that cannot be read is refused at the box"
saw "TIME IN"         "and the same box is asked again"
saw "Write RH-"       "so the form still finishes"

TARDY_TOKEN="$(login sam 'Volunteer#Sam26')"
get "$TARDY_TOKEN" '/api/animals?q=Tardy'
TARDY="$(field animals.0.id)"
get "$TARDY_TOKEN" "/api/animals/$TARDY"
expect_field stays.0.intake_time "09:15" "and the corrected time was stored, tidied up"

TOKEN="$(login sam 'Volunteer#Sam26')"
get "$TOKEN" '/api/animals?q=Scout'
expect_field count 1           "the animal the form created is on file"
SCOUT="$(field animals.0.id)"
get "$TOKEN" "/api/animals/$SCOUT"
expect_in "Collie, black and white" "BREED / DESCRIPTION went in through the terminal"
expect_field animal.kennel_id "7"         "KENNEL NO. went in through the terminal"
expect_field stays.0.intake_time "09:15"  "TIME IN went in through the terminal"
expect_field diets.0.food "Adult Dog Kibble" "the dietary requirement went in through the terminal"
expect_in "Rusty"                         "CAME IN AS is in the name history"

# ------------------------------------------------------------------- clear
section "Clearing the screen"

terminal "diane" "Staff#Diane26" "find bella" "dash" "clear" "quit"
saw "Logged in as"        "clear reprints who is logged in"
saw "What you were doing" "and what you were doing"
saw "dash"                "listing the last command"
saw "find bella"          "and the one before it"

# The "what you were doing" list must not fill up with the housekeeping that produced it.
WORKLIST="$(sed -n '/What you were doing/,/Scroll up/p' <<< "$SCREEN" | grep -E '^ +(last )? +\S')"
if grep -qE '^\s+(last\s+)?clear\s*$' <<< "$WORKLIST"; then
  fail "clear lists itself as something you were doing"
else
  pass "clear does not list itself as work"
fi

# ---------------------------------------------------- permissions are the server's
section "What the terminal offers"

terminal "sam" "Volunteer#Sam26" "help" "quit"
saw "intake"   "a volunteer is offered intake"
not_saw "decide <application>" "and is not offered the director's decide command"

# Hiding a command in 'help' is a courtesy. Typing it anyway still reaches the server, which
# refuses it - and that is the point: the terminal is not what is protecting anything.
#
# Set up an application that has a visit and no decision yet, so a volunteer typing "decide" gets
# all the way to the action and is refused there, rather than being stopped early by something
# incidental.
STAFF_TOKEN="$(login diane 'Staff#Diane26')"
VOL_TOKEN="$(login sam 'Volunteer#Sam26')"
post "$STAFF_TOKEN" /api/people '{"firstName":"Undecided","lastName":"Applicant"}'
UNDECIDED_PERSON="$(field person.id)"
post "$VOL_TOKEN" /api/applications "{\"personId\":$UNDECIDED_PERSON,\"animalId\":4,\"childrenCount\":0}"
UNDECIDED_APP="$(field application.id)"
post "$VOL_TOKEN" "/api/applications/$UNDECIDED_APP/visit" '{"visitorName":"Sam",
  "childrenPresent":false,"yard":true,"yardFenced":true,"elderlyResidents":false,
  "adultMen":1,"adultWomen":1,"housingConfirmed":true,"recommendation":"APPROVE",
  "notes":"Quiet flat, one cat already, no children. A good home for an older cat."}'
expect_status 201 "an application is waiting for a decision"

terminal "sam" "Volunteer#Sam26" "decide $UNDECIDED_APP" \
  "1" "No children and they already keep a cat, so she would settle in well." \
  "y" "Volunteer#Sam26" "quit"
saw "may not decide"  "a volunteer who types the hidden command anyway is refused by the server"
saw "VOLUNTEER"       "and the refusal names the role that was refused"

get "$STAFF_TOKEN" "/api/applications/$UNDECIDED_APP"
expect_field application.status "UNDER_REVIEW" "and the application was left exactly as it was"
expect_field decisions "[]"                    "with no decision recorded"

# ------------------------------------------------- Java failures stay out of sight
section "When Java itself fails"

# The reported bug: the jar was rebuilt while a terminal was open, a class it had not loaded yet
# vanished, and the JVM printed a NoClassDefFoundError stack trace at the front desk. Reproduced
# here by replacing the jar mid-session with one that is missing a class.
LIVE="$(mktemp -d)"; STRIP="$(mktemp -d)"
mkdir -p "$LIVE/lib"
cp "$ROOT/app/target/rhrms.jar" "$LIVE/" && cp "$ROOT"/app/target/lib/*.jar "$LIVE/lib/"
( cd "$STRIP" && unzip -qo "$LIVE/rhrms.jar" \
    && find . -name 'Shell$Outcome.class' -delete \
    && jar cf "$LIVE/stripped.jar" . ) >/dev/null 2>&1

( echo diane; echo 'Staff#Diane26'; sleep 2
  cat "$LIVE/stripped.jar" > "$LIVE/rhrms.jar"
  echo quit; sleep 2 ) \
  | COLUMNS=80 java -cp "$LIVE/rhrms.jar:$LIVE/lib/*" org.rubyhill.rhrms.ClientMain \
      > "$LIVE/out.txt" 2>&1
SCREEN="$(sed 's/\x1b\[[0-9;]*m//g' "$LIVE/out.txt")"

not_saw "NoClassDefFoundError"  "a Java error never reaches the screen as a stack trace"
not_saw "at org.rubyhill"       "and neither do stack frames"
not_saw "java.lang."            "nor Java class names"
saw "program files changed"     "it says what actually happened, in words"
saw "start it again"            "and what to do about it"
saw "Nothing half-finished was saved" "and that nothing was left half-done"

if [[ -f "$LIVE/rhrms-terminal.log" ]] && grep -q "NoClassDefFoundError" "$LIVE/rhrms-terminal.log"; then
  pass "the technical detail is kept in a log for whoever maintains it"
else
  fail "the stack trace was not written to a log file"
fi
saw "rhrms-terminal.log"        "and the message says where that log is"
rm -rf "$LIVE" "$STRIP"

report
