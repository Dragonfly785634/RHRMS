# Decisions

Choices made where the interviews, the SRS or the build spec were silent or contradicted each
other. Build spec rule 3: where something is not covered, choose the simplest option, record it
here, and keep going.

Format: date, the question, what was built, and why.

---

## Stage 2 architecture

### D-01 — A terminal, not a JavaFX window
*2026-09-27.* The build spec names JavaFX. The client asked for a terminal-based system instead.

**Built:** a command-line terminal (`scripts/rhrms.sh`). The spec's screen list in section 8 is
kept as the command list, so the same work is on offer, and the UI section of the spec is the only
part that changed. Java and PostgreSQL, which the course mandates, are unchanged.

**Why:** the client's instruction beats the spec's default. Everything else in the spec — the
rules, the audit trail, the permission table — is about what happens behind the screen, so a
terminal costs nothing.

### D-02 — A local HTTP API between the terminal and the database
*2026-09-27.* There is one front-desk computer, but the interviews say more than one person edits
data at the same time.

**Built:** a small server on `127.0.0.1:8642`. Every terminal window is a separate client, logs in
separately, and reaches the data only through the API. The terminal holds no database password and
enforces no rule.

**Why:** three reasons, in order of importance.
1. **Attribution.** Each window logs in as a person, so every change is recorded against whoever
   actually made it. That is the whole basis of the audit trail the director asked for. One shared
   program with one connection could not do it.
2. **Concurrency.** Two people editing at once meet the database's locking and version rules,
   not each other's half-finished work.
3. **Replaceability.** The terminal can be swapped for a web page or a JavaFX window later without
   a single rule moving, because nothing that matters lives on the client side.

**Cost:** the server has to be running. `scripts/start_server.sh` starts it, and the terminal says
so plainly when it is not.

### D-03 — One PostgreSQL login role per application role
*2026-09-27.* How should "elevated permissions" be enforced?

**Built:** four login roles the server uses (`rhrms_auth`, `rhrms_volunteer`, `rhrms_staff`,
`rhrms_director`), each with only the privileges that application role needs. The server borrows a
connection from the pool that matches whoever is logged in. See `sql/05_db_roles.sql` and
`docs/ROLES_AND_PASSWORDS.md`.

**Why:** the client was explicit that a permission must not be merely hidden on the front end. So
it is checked in the API, which produces a message somebody can act on, and then again by
PostgreSQL, which is the last word. A mistake in the API cannot turn into a volunteer approving an
adoption, because `rhrms_volunteer` has no `INSERT` on `decision`.

**Cost:** more roles to set up, and a grant file to keep in step with the permission table. Worth
it: the property being protected is the director's liability record.

### D-04 — A password is re-typed before a critical action
*2026-09-27.* The client asked for UNIX-style confirmation on dangerous actions.

**Built:** critical endpoints require the logged-in person's own password again, in the
`X-RHRMS-Confirm` header. Wrong or missing password gives `403 ACTION_DENIED` and the action never
starts. Every attempt, successful or not, is written to `auth_event`.

The critical actions are: record a decision; override a decision; record an adoption; record a
return; withdraw or close an application; withdraw a restriction; mark an animal deceased; write
off stock; correct a stock count; change a setting; create or change a user account; reset a
password.

**Why:** each of those either cannot be undone or changes who may take an animal home. A front desk
is not a personal laptop — anybody might sit down at a terminal somebody walked away from.

**Not cached** the way `sudo` caches for fifteen minutes. The point of the check is that it happens
at the moment of the action, and a fifteen-minute window is most of an unattended lunch break.

### D-05 — Sessions live in the server's memory, not the database
*2026-09-27.*

**Built:** a token is 256 random bits from `SecureRandom`, held in a map in the one server process.
Stopping the server ends every session.

**Why:** that is the behaviour you want on a shared front-desk computer at the end of the day, and
it means a stolen token cannot outlive the machine being turned off. The cost — a restart signs
everybody out — is a feature here, not a problem.

### D-06 — Five wrong passwords, then a five-minute pause
*2026-09-27.* Nothing in the interviews covers this.

**Built:** settings `auth.max_failed_logins` (5) and `auth.lockout_minutes` (5). Counted from
`auth_event`, so rebuilding or restoring the database resets it, and a successful login or a
password reset wipes the slate.

A wrong password on a *step-up confirmation* does **not** count towards it. Otherwise anybody who
could reach the port could lock Diane out of the front desk all afternoon.

**Why:** enough to stop somebody trying passwords at the keyboard, not enough to lock out a member
of staff who had caps lock on. Both numbers are settings because they are guesses.

### D-07 — JSON is hand-written, not a library
*2026-09-27.*

**Built:** `json/Json.java`, about 300 lines, reader and writer.

**Why:** build spec rule 6 says every library has to be justified to the instructor. The whole API
speaks flat objects, arrays, strings, numbers and booleans. Three hundred lines that can be read
in one sitting are easier to defend than a dependency. The five libraries that *are* used are the
ones the spec already named.

### D-08 — `maven.compiler.source/target` instead of `release`
*2026-09-27.* `--release 21` needs `lib/ct.sym`, which the JRE on this machine does not ship.

**Built:** `source`/`target` 21 in `app/pom.xml`.

**Why:** it builds on the machine the project has to build on. `release` is better practice — it
checks you have not used a newer API — so switch to it if a full JDK is installed.

---

## Stage 2 rules, where the spec left a gap

### D-09 — A director may record an arrival with no kennel when all 40 are full
*2026-09-27.* IN-2 says the director may override the capacity limit "with a reason (logged)", but
the kennel-sharing trigger refuses two strangers in one kennel, so an override cannot mean that.

**Built:** a kennel is required for everybody. A DIRECTOR, and only a DIRECTOR, may record an
arrival with **no** kennel by sending `noKennelReason`, and only while no kennel is free.

**Why:** it is the only reading that does not break the rule the override is supposed to bend. An
animal in the office with a logged reason is honest; two strangers in one kennel is a welfare
problem the trigger is right to refuse.

**Ask the director** whether that is what he meant.

### D-10 — A director may place an animal that has not been vetted
*2026-09-27.* Open question Q3.

**Built:** blocked by default, as the setting `placement.require_vetted` says. A DIRECTOR can go
ahead with a written reason, which is announced to `place_animal()` for that one transaction and
appears on the audit row for the placement.

**Why:** PL-3 and section 7 both say the director may override it, and a vet appointment booked for
Thursday is a real situation. The reason is the record.

### D-11 — `STAFF` may be added to the deciders, `VOLUNTEER` may not
*2026-09-28.* Open question Q4: may anybody stand in when the director is away?

**Built:** `decision.allowed_roles` (default `DIRECTOR`) genuinely controls it, enforced in three
places at once — the API, the `check_decision()` trigger, and the privilege on the `decision`
table. `VOLUNTEER` is refused whichever way you try.

**Why:** an earlier version hard-coded `DIRECTOR` in the privileges while the trigger read the
setting, so the setting looked as though it worked and did nothing. Now the layers agree. The floor
is deliberate: separating a volunteer's *recommendation* from the director's *decision* is the
clearest change between Interview 1 and Interview 3, so it is not something a setting should be
able to undo.

### D-12 — A second litter for one mother needs a different kennel
*2026-09-27.*

**Built:** nothing. The kennel trigger treats a second litter in the same kennel as strangers, so
the intake is refused and staff pick another kennel.

**Why:** loosening a rule that keeps unrelated animals apart, for a case that has not come up, is
the wrong trade. The refusal says what is wrong and a free kennel fixes it.

### D-13 — Failed logins are recorded in their own transaction
*2026-09-28.*

**Built:** every method in `AuthService` decides, then records, then throws, in that order, with
the `auth_event` write in a transaction of its own.

**Why:** this was a real bug found in testing. The event was being written inside the transaction
that then raised the refusal, so PostgreSQL rolled the row back along with it — and the login trail
silently lost every failed login and every failed confirmation. A trail that loses exactly the
entries you would want is worse than no trail, because it looks complete.

### D-14 — The audit log never holds password material
*2026-09-27.*

**Built:** `audit_trigger()` replaces `password_hash` with a marker before writing, so an entry
records *that* a password was set or changed, never what to.

**Why:** `audit_log` is readable by every application role, for the History screen. A hash copied
in there would undo the point of keeping `staff_user.password_hash` unreadable.

### D-15 — Two `SECURITY DEFINER` helpers, for row locks only
*2026-09-27.* PostgreSQL requires `UPDATE` privilege to take *any* row lock, `FOR SHARE` included.
Two rules need a lock on a table the acting role must not be able to change: IN-2 locks the kennel
during an intake, and AP-1 waits on the animal while an application is saved.

**Built:** `lock_kennel()` and `lock_animal_shared()`, one statement each, which take the lock as
the table owner.

**Why:** the alternative was giving volunteers `UPDATE` on `animal` and `kennel`, which is exactly
what section 7 says they must not have. Each helper reads and locks one row and returns it; neither
can change anything.

---

## Phase 1 scope

### D-16 — The dietary requirement is part of the intake form
*2026-09-28.* Phase 1 item 1 lists a dietary requirement among the things an intake records, and
item 9 makes it a *link* to a supply type rather than a note.

**Built:** `POST /api/animals/intake` optionally takes `foodProductId`, `cupsPerMeal` and
`mealsPerDay` and creates the link in the same transaction. `rhrms_volunteer` was granted `INSERT`
on `diet` — insert only; changing or ending a portion afterwards is still a staff correction.

**Why:** item 10 asks for supplies needed by the animals currently in care. That number is only
true if the requirement is recorded when the animal arrives, by whoever is at the desk. Making it a
separate staff-only step afterwards would guarantee the inventory view understates the need.

**Note:** section 7 of the build spec does not list diets among a volunteer's tasks. The Phase 1
checklist is more recent and more specific, so it wins. Worth confirming.

### D-17 — A supply item records the unit it is counted in
*2026-09-28.* Phase 1 item 7: type, unit and quantity.

**Built:** a `unit` column on `food_product`, default `'bag'`. Type is `kind`
(`REGULAR`/`PRESCRIPTION`), quantity is the count of units in `stock_level`, and `bag_lbs` is how
much one unit weighs.

**Why:** "bag" was baked into the column names, which is fine for kibble and wrong for tinned food
by the case. One column makes the unit explicit and displayable without disturbing the forecast
arithmetic, which works in pounds either way.

### D-18 — A separate endpoint for the public list, and for the inventory view
*2026-09-28.* Items 3 and 10 were reachable by filtering the general search and reading the food
forecast, but neither was the thing the checklist actually names.

**Built:** `GET /api/animals/available` and `GET /api/inventory`.

- The available list is `AVAILABLE` **and** still in care, which is not the same as filtering on
  status alone: it excludes an animal whose stay has ended. It carries the animal's restrictions,
  because they decide who may take it.
- The inventory view puts on-hand next to needed-per-day for every supply item, names the animals
  driving the need, and says how many animals in care have no dietary requirement recorded — so the
  number is never quietly wrong.

**Why:** a feature that exists as a query parameter somebody has to know about is not reliably
delivered. Both now have one address, a fixed shape, and tests against them.

---

## Matching paper form RH-1

The rescue's printed intake form, `Animal_Intake_Form.pdf`, RH-1 (rev. 3/24). The
terminal intake screen now asks for the same boxes, in the same order, under the same headings.

### D-19 — AGE and "AGE IS" are asked the way the form asks them
*2026-09-28.* The form has an AGE box and a separate tick: exact or estimated. The database
stores a birth DATE plus `birth_date_is_estimate`.

**Built:** AGE in years and months, then the tick, in that order. The tick sets
`birth_date_is_estimate` directly. When "exact" is ticked, a date of birth is offered as well,
because a date is the only thing that is exact to the day; if it is given it is used as-is.

**Why:** an age of "5 years" worked back from today fixes the year and not the day, so ticking
"exact" against an age is only as good as the form. That is the form's claim to make. Forcing a
date of birth before anyone may tick "exact" would mean staff stop ticking it, and then the field
tells nobody anything.

### D-20 — TIME IN got a column
*2026-09-28.* `stay.intake_time TIME`, nullable.

**Why:** it is a box on the form and there was nowhere to put it. Nullable because the form is
often written up later in the day with that box left blank, and a guessed time is worse than
none. Whether an animal arrived at 09:15 or 23:40 matters when somebody asks who was on.

### D-21 — "TAKEN IN BY" is the on-behalf-of field
*2026-09-28.* The form has both "TAKEN IN BY (VOLUNTEER)" and "ENTERED IN SPREADSHEET BY".

**Built:** the logged-in user is `stay.received_by`; the name in TAKEN IN BY, when it is somebody
else, goes to `rhrms.on_behalf_of` and lands on every audit row for that intake.

**Why:** the distinction already existed for home visits entered for a volunteer with no login
(HV-2), and it is the same situation: the person who met the animal is not always the person at
the keyboard. Both names end up on the record, which is what the form is asking for.

### D-22 — "BONDED WITH" at intake, and the privilege that needs
*2026-09-28.* The form has the box, and a volunteer fills the form in.

**Built:** intake takes `bondedWithAnimalId`; it joins the partner's existing group or starts one
and puts the partner in it. `rhrms_volunteer` was granted `INSERT` on `bond_group` and
`UPDATE (bond_group_id)` on `animal` - one column, nothing else.

**Why:** Interview 4 says a bonded pair must be adopted together, so the link has to exist from
the moment both animals are on file, not from whenever a staff member gets to it. This widens
section 7, where bond editing is staff-only; the narrowness of the column grant is the trade. A
volunteer can say "these two came in together" and still cannot change anything else about
either animal.

### D-23 — One NOTES box, as printed
*2026-09-28.* The form has a single box: "MEDICAL, BEHAVIOUR, ANYTHING THE NEXT PERSON NEEDS TO
KNOW". The schema has three separate note fields.

**Built:** one box on the screen, stored in `general_notes`. The `edit` command can still split
things into the health and behaviour fields later.

**Why:** guessing which sentence is medical and which is behavioural would be inventing structure
the person writing did not intend. The form deliberately asks one question; the screen asks the
same one.

### D-24 — TYPE is dog / cat / other, with one follow-up
*2026-09-28.* The form prints three tick boxes. The database enum has five values.

**Built:** the three boxes as printed. Ticking "other" asks one more question - ferret, small
mammal, or other - which is what the person would have written in the description anyway.

**Why:** matching the form without throwing away what the animal actually is.

### D-25 — A leading dot means a system command
*2026-09-28.* Forms need a way out that cannot be confused with an answer.

**Built:** at every prompt, `.q` `.quit` `.e` `.exit` leave the form having saved nothing, and
`.help` lists them. Anything else starting with a dot is refused with that list and the question
is asked again. A leading dot is **never** stored as a field value.

**Not honoured at a password prompt**, on purpose: a password is allowed to start with a dot, and
quietly treating somebody's password as a command would be worse than useless. There is always an
"Are you sure?" before a password prompt, and `.q` works there.

**Why:** "exit" is a perfectly ordinary thing to write in a notes box about an animal that got
out. The dot is what makes the escape hatch unambiguous.

### D-26 — start_server.sh will not hand over to a server it did not start
*2026-09-28.* Found while testing the form, and worth recording because it wasted an hour.

An orphaned server was holding the port. The new process exited with "port in use", and the
start script then polled `/api/health`, got a cheerful answer **from the orphan**, and reported
success. Every test after that ran the old code against a new database.

**Built:** the start script checks the port before starting and refuses if something is already
answering that it did not start; and its wait loop now checks that its own child is alive
**before** asking the port whether anything is up. `stop_server.sh --any` clears a stale process
file. The process lookup filters to real `java` processes, so stopping the server no longer kills
the shell that asked.

**Why:** a health check that cannot tell whose health it is checking is not a health check. This
is the same class of bug as D-13: something that looked like it was working.

### D-27 — TIME IN takes whatever people write, and is checked at the box
*2026-09-28.* Reported from real use: a whole intake form was filled in, and only on saving did
it come back with "Write the time in as HH:MM on a 24-hour clock". The form was lost.

Two separate mistakes.

**It was too strict.** The original check accepted `HH:MM` on a 24-hour clock and nothing else,
so `9:15 AM`, `0915`, `9.15` and `2:30 pm` were all refused. TIME IN is a blank line on the paper
form, not a set of boxes, so what gets written in it is whatever the person had in their head.
`util/ClockTime` now reads all of those and stores one shape, `HH:MM`. It still refuses "morning"
and "half past nine": a guessed time is worse than an empty box, and the box may be left blank.

**It was checked in the wrong place.** The check ran on the server, at save time, at the end of a
long form. Format checks that can be made at the box are now made at the box - the terminal asks
again, immediately, with the answer still in mind. The server still checks, because a different
frontend must not be able to store something else; it is just no longer the first thing to
notice.

And a backstop for the rest: **any** refusal on saving now prints back every answer the form
collected, under the form's own labels. Ten minutes of somebody's work should not disappear
because of two characters in one box, and "nothing was saved" on its own is the message that makes
people stop using a system and go back to the spreadsheet.

**Why it matters beyond the time box:** validation that only happens on save turns every small
typo into a lost form. The rule now is that anything checkable at the box is checked at the box.

### D-28 — A Java failure never reaches the screen as a stack trace
*2026-09-28.* Reported from real use. The program was rebuilt while a terminal was still open, a
class it had not loaded yet disappeared, and the JVM printed a `NoClassDefFoundError` trace at
the front desk.

**Why it got out:** `Terminal.run` caught `RuntimeException`. A `NoClassDefFoundError` is an
`Error`, not an `Exception`, so it walked straight past and out to the JVM's default handler.

**Built:** `ui/Crash`, and `Throwable` caught at all three levels - the command loop, the session
loop, and `ClientMain`. The person gets a sentence they can act on; the trace goes to
`rhrms-terminal.log` beside the program, and the message says where. A broken classpath and an
exhausted heap are named specifically, because "the program was rebuilt, close and reopen" is an
answer and "unexpected error" is not.

Two details that are easy to miss:

- **The handler is loaded at start-up** (`Crash.warmUp`). The failure it exists to report is
  exactly the failure that would stop it loading at the moment it is wanted.
- **There is a fallback below the handler**, printing to `System.err` with nothing but already
  loaded classes, for when the classpath is too broken even for that.

An ordinary bug in one command no longer ends the session: the loop reports it and carries on.
A broken classpath does close the terminal, because nothing else it does would be trustworthy.

### D-29 — A half-filled form survives whatever interrupted it
*2026-09-28.* Same report: a session timed out part-way through an intake, and the boxes already
filled in went with it.

**Built:** the intake screen catches a failure anywhere in the form, shows what went wrong, and
then prints every answer collected so far under the form's own labels. The session-expiry path
still returns to the login screen; the answers are on the screen above it.

**Why:** this is D-27 again in a different disguise. It does not matter whether the interruption
is a bad time, a timed-out session or a stopped server - the person typed it, so it should still
be in front of them. Retyping from the screen takes seconds; retyping from memory takes the form.

---

## Cutting the terminal back to the scope

### D-30 — Forty-eight commands became nineteen
*2026-09-28.*

The terminal had grown to 48 commands. A director's `help` ran to 54 lines; a volunteer's to 33.
The Phase 1 scope is **ten features**. That gap was not capability - it was noise. Several
commands were a second route to somewhere you could already get (`apps`, `app`, `board` were all
reachable from the animal record), several were beyond the scope entirely, and seven separate
commands existed for things a director does a few times a year.

Build spec rule 8: *"The client values simplicity above everything. When in doubt, build fewer
screens and fewer fields."*

**Kept (19):** `help clear dash find animal person` · `intake status` · `apply visit decide place
return` · `food supplies move diet` · `passwd admin`

**Folded rather than dropped:**

| Was | Now |
|---|---|
| `find` + `people` + `lookup` | one `find` (see D-31) |
| `users newuser user settings set sessions logins` | one `admin` menu |
| `newfood` + listing | one `supplies` screen |
| `prescribe` | inside `diet`, offered at the moment FD-7 refuses the portion |
| `orders ordermore donations donate` | off the terminal; the API keeps them |
| `who` | `clear` already shows who you are; `help` already lists what you may do |

**Dropped from the terminal only:** `kennels service edit kennel restrict bond vet litter close
history recent apps app board newperson`.

**The API was not touched.** Every endpoint behind those commands still exists, still enforces
its rules, and is still covered by the 245 Phase 1 checks. This was a client-side cut: a later
phase can put a screen back on top of an endpoint that already works and is already tested. The
unreachable screen methods were deleted rather than left in place, because dead code that still
compiles rots quietly and is worse than no code.

**Also removed:** the help footer that listed the commands you were *not* allowed to use. Telling
a volunteer the names of eleven things they will be refused is noise dressed as helpfulness.

### D-31 — One search box, as LK-1 asks
*2026-09-28.* There were three searches: `find` for animals, `people` for people, `lookup` for
past decisions. LK-1 says "One search box: applicant name, animal name (current or former) or
animal code."

**Built:** `find` asks all three and shows whatever came back, leaving out the sections that found
nothing. Searching "morgan" returns the person and the decision about Bella, with the director's
rationale underneath.

**Why:** at a desk with somebody in front of you holding a lead, you often do not know which kind
of thing you are looking for. "Morgan" might be the dog or the person who brought it in. Having
to choose the right command first is a question the system should not be asking.

### D-32 — The intake form asks only what is printed on RH-1
*2026-09-28.* The screen was asking three questions the paper form does not have: COLOUR /
MARKINGS, SEX, and "is it ready to be listed for adoption?".

**Built:** colour folded into the one box the form actually prints, "BREED / **DESCRIPTION**".
Sex and adoptability dropped from intake; both are editable later, and neither is on the sheet in
the volunteer's hand. Thirteen questions down to ten.

**Why:** the instruction was to match the form. A screen that asks more than the paper does is
not matching it, and every extra question at intake is asked while an animal is still standing in
the doorway.

---

### D-33 — The rescue's spreadsheet is imported in three separate stages

`Documents/MASTER copy FINAL (2).xlsx` is loaded by a reader
(`scripts/import/read_master.py`), a staging schema (`sql/91_import_staging.sql`) and a mapping
(`sql/92_import_master.sql`), in that order, rather than by one script.

The reader transcribes the cells verbatim into all-`TEXT` tables and interprets nothing. The mapping
does every reading — species, dates, ages, which note is medical, which two rows are one dog — with
the reasoning commented next to the statement that acts on it, and recorded in
`rhrms_import.decision_log`.

The point is that any argument about the import is an argument about stage 3, and can be settled by
querying stage 2. The spreadsheet's own README says the mess is the evidence and asks that it not be
tidied; loading data into a schema is unavoidably tidying, so the original had to survive the
loading. It does, in `rhrms_import`, including the `?` in the litter quantity and the row that says
"Diane said she would keep this up".

The reader uses only the standard library. An `.xlsx` is a zip of XML, and adding openpyxl for one
throwaway read would need justifying under the spec's rule on libraries.

### D-34 — The import never invents a fact

Where the spreadsheet is silent the column stays `NULL`: no guessed sexes, no guessed vet dates, no
invented home-visit findings. A `NULL` is a true statement about what the rescue recorded; a
plausible value is a lie that survives forever.

The cost is real and worth stating plainly: **the twelve completed adoptions in the file cannot be
recorded as adoptions.** HV-1 wants a home visit with findings and DE-3 wants a decision with a
rationale, and the spreadsheet has a column containing the word `yes`. Six of the twelve do not even
have that. So each application is `CLOSED` with the adoption date and fee in `closed_reason`, the
animal is `ADOPTED` and the stay is closed. Everything the spreadsheet says is loaded; the approval
is not, because there is nothing to approve it with.

The rule applied throughout: **record a process step where the spreadsheet recorded its substance,
and never manufacture one where it did not.** Two denials pass that test and are loaded as real
decisions — Boomer's because the director wrote his reason down, Barley's with a rationale saying
the reason is unknown. Nothing else does.

Where a `NOT NULL` leaves no choice (`not_adoptable_reason`, `closed_reason`, `decision.rationale`),
the text begins "Imported from the 2026 spreadsheet" so nobody mistakes the importer's words for a
volunteer's. Notes fields the rescue owns get the spreadsheet's words and nothing else.

### D-35 — Where the spreadsheet contradicts itself, the safe reading wins

Maisie's row says a home visit happened and that she "went home with the Alvarez family", and leaves
the adoption date and fee blank. `CHECK ((ended_on IS NULL) = (end_reason IS NULL))` means a stay
cannot have ended on an unknown date, and there is no `UNKNOWN` animal status, so the import has to
choose.

It chose `NOT_ADOPTABLE`. Of the two available lies, "she is available" is the one that offers an
animal who may already be in somebody's living room to a second adopter. The reason on the record is
the contradiction itself.

Duplicates are the other half of this. Cooper and Copper are one letter apart with identical
everything else; Jen Kowalczyk and Jennifer Kowalcyzk share a phone number. Both were loaded as two
records, not merged — deciding that two records are one is the rescue's call, and merging silently
would destroy the evidence that somebody typed it twice. Searching the shared phone number returns
both side by side, which is the moment a human can settle it. Twenty such questions are queued in
`decision_log WHERE ask_director`.

### D-36 — Installing from an empty database is now a test

`POST /api/bootstrap/director` used `Ctx.update()` — `executeUpdate()` — on a
`SELECT set_config(...)`, which JDBC refuses. **RHRMS could not be installed on an empty database
at all.** The director's PL-3 vetting override had the identical bug on the identical call.

Both survived 297 checks because every test starts from `build_db.sh --sample`, which inserts the
staff accounts with plain SQL. `tests/api/lib.sh` even had a `fresh_empty()` helper written for a
first-run test that was never written, called from nowhere.

Fixed by adding `Ctx.run()`, which uses `execute()` and tolerates a result set, and by writing
`tests/api/import.sh` (61 checks) — the only tests that install the system the way a rescue would.
The lesson is the same one D-13 and D-26 recorded: **the dangerous bugs are the ones on paths the
tests reach a different way.** A fixture that is convenient for every test is a fixture that hides
whatever only happens without it.

## Carried forward from Stage 1

These were recorded when the database was built and still hold.

- **Business rules live in the database.** Triggers in `sql/02_rules.sql` raise messages written
  for the front desk, and the API passes them through unchanged. An importer, or somebody with
  `psql`, cannot get round them.
- **No trials or foster periods** (open question Q1). Interview 2 describes a week-long trial;
  Interview 4 says there is no such thing. Not built. The state machine is easy to extend if the
  director and Diane settle it the other way.
- **One record per animal, litters linked by a `litter` row** (Q2). Diane agreed individual records
  are worth it so one kitten can have its own diet — which is also what makes Phase 1 items 9
  and 10 work.
- **Order shown, not enforced.** Interview 1 said first come, first served; Interview 2 overruled it
  with the Bella story. The decision board shows the order applications arrived and does not act on
  it.
- **Money is `BigDecimal`, phones are `VARCHAR`.** Spec section 13, and Interview 4 on phones.
- **Nothing is ever deleted.** A removal is a status change with a reason, refused both by privilege
  and by a trigger.
