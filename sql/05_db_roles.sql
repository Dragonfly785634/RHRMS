-- =====================================================================
-- RHRMS  Stage 2 · 05_db_roles.sql
-- PostgreSQL privileges for the API server's login roles.
--
-- WHY THIS FILE EXISTS
-- The build spec (§7) says who may do what. The API server enforces that table in
-- security/Permissions.java and gives the person a sentence they can act on. This file
-- enforces the SAME table a second time, in PostgreSQL, using a different mechanism.
--
-- The server keeps one connection pool per application role and borrows a connection from
-- the pool that matches the logged-in person. A request from a VOLUNTEER session therefore
-- runs as the PostgreSQL role rhrms_volunteer, which has no INSERT privilege on "decision".
-- If the permission check in the API were ever bypassed - a coding mistake, a patched
-- client, somebody with curl and the port number - PostgreSQL still refuses, and the refusal
-- arrives as SQLSTATE 42501 which the server turns into a plain "not allowed" message.
--
-- The roles build on each other, so each block below only lists what that role ADDS:
--     rhrms_volunteer  <  rhrms_staff  <  rhrms_director
-- plus two roles outside that chain:
--     rhrms_auth       credentials only: read a hash, write a hash, log an attempt
--     rhrms_readonly   look, never touch (reports, a safe psql prompt)
--
-- Passwords for all of these are in docs/ROLES_AND_PASSWORDS.md and are created by
-- scripts/setup_db.sh. Runs as rhrms_owner.
-- =====================================================================
SET search_path = rhrms;

-- =====================================================================
-- 1. rhrms_volunteer - the floor. Sees everything, records the day's work.
--    Section 7: "Intake, application, home visit, open/receive food, order more".
-- =====================================================================
GRANT USAGE ON SCHEMA rhrms TO rhrms_volunteer;

-- Reading: everything, including the four views and the audit log (the History screen).
GRANT SELECT ON ALL TABLES IN SCHEMA rhrms TO rhrms_volunteer;

-- ...with two carve-outs.
-- (a) Password hashes. Only rhrms_auth ever reads one. A column privilege cannot be
--     subtracted from a table-wide GRANT, so staff_user is revoked and re-granted by column.
REVOKE ALL ON staff_user FROM rhrms_volunteer;
GRANT SELECT (id, username, display_name, role, active, created_at, version)
  ON staff_user TO rhrms_volunteer;

-- (b) The login trail is user administration, which section 7 keeps for the director.
REVOKE ALL ON auth_event FROM rhrms_volunteer;

-- Sequences, so BIGSERIAL inserts can claim an id.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA rhrms TO rhrms_volunteer;

-- The stored procedures in 02_rules.sql. None of them is SECURITY DEFINER, so EXECUTE on
-- its own grants nothing: move_stock() can only touch what the calling role may touch.
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rhrms TO rhrms_volunteer;

-- Writing.
GRANT INSERT ON animal               TO rhrms_volunteer;   -- IN-1 intake
GRANT INSERT ON animal_name_history  TO rhrms_volunteer;   -- AN-2 trigger writes this on insert
GRANT INSERT ON stay                 TO rhrms_volunteer;   -- IN-1 the arrival itself
GRANT INSERT ON litter               TO rhrms_volunteer;   -- IN-6 a litter born in care
GRANT INSERT ON person               TO rhrms_volunteer;   -- AP-4 the applicant at the desk
GRANT INSERT ON application          TO rhrms_volunteer;   -- AP-1
GRANT INSERT ON home_visit           TO rhrms_volunteer;   -- HV-1, HV-2
GRANT INSERT, UPDATE ON stock_level  TO rhrms_volunteer;   -- FD-5 through move_stock()
GRANT INSERT ON stock_movement       TO rhrms_volunteer;   -- FD-5 the ledger row
GRANT INSERT, UPDATE ON open_bag     TO rhrms_volunteer;   -- FD-6 open a bag, finish the old one
GRANT INSERT ON food_order           TO rhrms_volunteer;   -- FD-9 "order more"
-- Phase 1 item 1 puts the dietary requirement on the intake form, and item 9 makes it a link to
-- a supply type rather than a note. So whoever records the arrival has to be able to create that
-- link. INSERT only: changing or ending a portion afterwards is still a staff correction.
GRANT INSERT ON diet                 TO rhrms_volunteer;
-- "BONDED WITH" is a box on the intake form RH-1, and the form is filled in by whoever took the
-- animal in. So recording a bond AT INTAKE has to be open to a volunteer. Only these two
-- privileges, and the second is one column: a volunteer can say "these two came in together",
-- and still cannot change anything else about either animal.
GRANT INSERT ON bond_group           TO rhrms_volunteer;
GRANT UPDATE (bond_group_id) ON animal TO rhrms_volunteer;

-- Recording a home visit moves the application SUBMITTED -> UNDER_REVIEW (HV-3), and that
-- trigger runs as whoever called it. Exactly two columns, so a volunteer cannot rewrite the
-- answers on an application; and the state-machine trigger still polices which move is legal.
GRANT UPDATE (status, version) ON application TO rhrms_volunteer;

-- =====================================================================
-- 2. rhrms_staff - Diane and the other part-timer.
--    Section 7 adds: corrections, kennel moves, restrictions, placement, fee, return,
--    food ordered/received, donations, vet visits, stock counts.
-- =====================================================================
-- rhrms_staff is a member of rhrms_volunteer (granted in scripts/setup_db.sh, because only
-- the superuser can grant role membership), so it already holds everything listed above.

GRANT UPDATE ON animal                    TO rhrms_staff;   -- AN-3, AN-4 corrections and kennel moves
GRANT UPDATE ON application               TO rhrms_staff;   -- all columns now, not just status
GRANT UPDATE ON person                    TO rhrms_staff;
GRANT UPDATE ON stay                      TO rhrms_staff;   -- ends a stay on placement
GRANT UPDATE ON kennel                    TO rhrms_staff;   -- take one out of service
GRANT UPDATE ON litter                    TO rhrms_staff;
GRANT INSERT, UPDATE ON bond_group        TO rhrms_staff;   -- AN-6
GRANT INSERT, UPDATE ON restriction       TO rhrms_staff;   -- AN-5 (liability-critical)
GRANT INSERT, UPDATE ON placement         TO rhrms_staff;   -- PL-1, IN-4 return
GRANT INSERT ON vet_visit                 TO rhrms_staff;   -- VV-1
GRANT INSERT ON donation                  TO rhrms_staff;   -- DN-1
GRANT INSERT, UPDATE ON food_product      TO rhrms_staff;
GRANT INSERT, UPDATE ON prescription      TO rhrms_staff;   -- FD-7
GRANT INSERT, UPDATE ON diet              TO rhrms_staff;   -- FD-1
GRANT INSERT, UPDATE ON stock_location    TO rhrms_staff;
GRANT UPDATE ON food_order                TO rhrms_staff;   -- FD-9 mark ordered / received

-- DE-1. The decision table: the director's written reason for a placement, which is what
-- defends the rescue if an adopted animal ever bites a child.
--
-- The privilege is granted to rhrms_staff, not only to rhrms_director, and that is deliberate.
-- Which roles may actually decide is the setting decision.allowed_roles (default DIRECTOR), and
-- the check_decision() trigger in 02_rules.sql reads that setting on every insert. Open question
-- Q4 asks whether anyone may stand in when the director is away; the answer is a setting the
-- director changes himself, and the trigger is what enforces it.
--
-- rhrms_volunteer is NOT given this, and that is the floor nothing can raise: a volunteer
-- records a *recommendation* on the home visit. Separating that from the decision is the
-- clearest requirement change between Interview 1 and Interview 3.
--
-- No UPDATE: 02_rules.sql makes decisions permanent. A correction is a new row (DE-6).
GRANT INSERT ON decision TO rhrms_staff;

-- =====================================================================
-- 3. rhrms_director - Mike. Section 7 adds: override, settings, user accounts.
-- =====================================================================
-- rhrms_director is a member of rhrms_staff (scripts/setup_db.sh), so it already holds
-- everything listed above.

GRANT INSERT, UPDATE ON setting TO rhrms_director;          -- §9, the values the client changes
GRANT SELECT ON auth_event TO rhrms_director;               -- who logged in, and who failed to

-- User administration. Note there is still no SELECT on password_hash: the director can set
-- someone's password, and cannot read anybody's.
GRANT INSERT ON staff_user TO rhrms_director;
GRANT UPDATE (username, display_name, role, password_hash, active, version)
  ON staff_user TO rhrms_director;

-- =====================================================================
-- 4. rhrms_auth - the login path, and nothing else.
--    This is the only role in the system that can read a password hash. It cannot read an
--    animal's name, so a mistake in the login code cannot leak or change rescue data.
-- =====================================================================
GRANT USAGE ON SCHEMA rhrms TO rhrms_auth;
GRANT SELECT (id, username, display_name, role, password_hash, active, created_at, version)
  ON staff_user TO rhrms_auth;
-- Changing your own password, and the director resetting someone else's, both write here.
GRANT UPDATE (password_hash, version) ON staff_user TO rhrms_auth;
GRANT SELECT ON setting TO rhrms_auth;                     -- session.idle_minutes, auth.* limits
GRANT SELECT, INSERT ON auth_event TO rhrms_auth;          -- §7 "Every login is logged"
GRANT USAGE, SELECT ON SEQUENCE auth_event_id_seq TO rhrms_auth;

-- =====================================================================
-- 5. rhrms_readonly - reports, and a psql prompt that cannot break anything.
--    Not part of the chain above. Use it for scripts/psql_read.sh and for anyone who only
--    needs to look. Still no password hashes.
-- =====================================================================
GRANT USAGE ON SCHEMA rhrms TO rhrms_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA rhrms TO rhrms_readonly;
REVOKE ALL ON staff_user FROM rhrms_readonly;
GRANT SELECT (id, username, display_name, role, active, created_at, version)
  ON staff_user TO rhrms_readonly;
REVOKE ALL ON auth_event FROM rhrms_readonly;

-- =====================================================================
-- 6. Belt and braces: nobody deletes anything, anywhere.
--    03_audit.sql already puts a BEFORE DELETE trigger on every business table (AU-2). This
--    takes the privilege away as well, so the refusal happens before the trigger is reached.
-- =====================================================================
REVOKE DELETE, TRUNCATE ON ALL TABLES IN SCHEMA rhrms
  FROM rhrms_volunteer, rhrms_staff, rhrms_director, rhrms_auth, rhrms_readonly;

-- audit_log stays readable and unwritable for everyone: the trigger in 03_audit.sql is
-- SECURITY DEFINER and writes it as the owner, which is the only way a row gets in.
REVOKE INSERT, UPDATE ON audit_log
  FROM rhrms_volunteer, rhrms_staff, rhrms_director, rhrms_auth, rhrms_readonly;
