# RHRMS handoff guide

This document is for the student who inherits the project after Phase 1. Read it together with
`README.md`, `../Personal/TRACEABILITY.md`, and `docs/DECISIONS.md` before changing code.

## System shape

~~~text
Terminal client (ClientMain / ui package)
        |
        | HTTP + JSON, loopback only
        v
API server (ServerMain / api package)
        |
        | JDBC through one pool per PostgreSQL role
        v
PostgreSQL (schema rhrms)
        |
        +-- constraints and triggers: business rules
        +-- stored functions: multi-step atomic actions
        +-- audit triggers: who, when, old row, new row, reason
~~~

The client is deliberately thin. It should collect input, call an endpoint, and display the
server's response. Do not add rescue rules to `app/src/main/java/org/rubyhill/rhrms/ui`; the API and
database are the authorities.

## First setup

From a fresh WSL installation, follow `Documents/Setting Up Dev Environment.md`. The short form is:

~~~bash
sudo service postgresql start
./scripts/setup_db.sh
./scripts/build_db.sh --sample
./scripts/build_app.sh
./scripts/start_server.sh
./scripts/rhrms.sh
~~~

`build_db.sh --sample` is a reset. It drops and recreates the `rhrms` schema, so never run it when
you need to preserve the current rescue data.

## Where to make changes

| Change | Primary location | Also update |
| --- | --- | --- |
| New or changed table/column | `sql/01_schema.sql` | `sql/02_rules.sql`, `sql/03_audit.sql`, roles, tests, API queries |
| Business invariant | `sql/02_rules.sql` | L2/L3 SQL tests and `../Personal/TRACEABILITY.md` |
| Audit behavior or history view | `sql/03_audit.sql` | L4 SQL tests and `docs/SECURITY.md` |
| Permission or critical action | `app/.../security/Perm.java`, `Router.java`, `sql/05_db_roles.sql` | role docs and API tests |
| Endpoint | relevant class under `app/.../api` | terminal screen, API acceptance tests, endpoint list from `GET /api` |
| Terminal workflow | `app/.../ui` | terminal tests and `README.md` |
| Settings | `sql/04_seed.sql`, `Settings.java`, or `AppConfig.java` depending on scope | docs and tests |
| Spreadsheet import | `scripts/import_spreadsheet.sh`, `scripts/import/read_master.py`, `sql/91_*`, `sql/92_*` | `docs/SPREADSHEET_FINDINGS.md` and import tests |

## Rules that must remain true

1. Stock cannot become negative, including when two sessions dispense the last bag at once.
2. An animal that is adopted, deceased, or otherwise unavailable cannot receive a new application.
3. An approval requires a home visit or an explicitly reused visit; a decision has a rationale.
4. A denial has a reason. A conflict acknowledged by an approval has a written acknowledgement.
5. An animal has at most one active placement and one open stay.
6. A bonded group is handled as a unit unless the surviving-partner exception applies.
7. Decisions and home visits are append-only. Corrections create a new decision or a new record.
8. Every write has an acting staff user and is written to the append-only audit log.
9. Password hashes never appear in the audit log or normal application queries.
10. A stale update must be refused rather than silently overwriting another person's edit.

When adding a write path, verify it has all of these applicable pieces: route permission, database
role privilege, transaction context, audit user/reason, validation, a useful error, and a test.

## Transaction and audit pattern

API writes should use `ctx.db().tx(...)` or the corresponding helper. That method sets the local
PostgreSQL audit context, runs the work, commits on success, and rolls back on failure. A direct
connection commit or a write outside this pattern can lose attribution or leave a multi-step action
half-complete.

The audit trigger reads:

~~~sql
SET LOCAL rhrms.user_id = '...';
SET LOCAL rhrms.reason = '...';
SET LOCAL rhrms.on_behalf_of = '...';
~~~

Do not write `audit_log` directly. The database role permissions and append-only trigger are part
of the design.

## Test workflow

Compile after Java changes:

~~~bash
./scripts/build_app.sh
~~~

Run focused checks first:

~~~bash
./tests/run_tests.sh sql
./tests/run_tests.sh race
./tests/run_tests.sh api
./tests/run_tests.sh term
./tests/run_tests.sh import
~~~

Run the complete suite before handing work off:

~~~bash
./tests/run_tests.sh
~~~

Every suite rebuilds the database. The import suite also reads the committed spreadsheet and
checks that it was not modified. Keep a backup of any local data before testing.

## Safe extension sequence

1. Write the requirement and failure cases in `../Personal/TRACEABILITY.md`.
2. Decide which role may perform it and whether it is a critical action.
3. Add or change the schema and database rule first.
4. Add a focused SQL test for the happy path, refusal, and contention if relevant.
5. Add the API transaction and error message.
6. Add an HTTP acceptance test.
7. Add the terminal command or screen.
8. Add a terminal test if the client behavior changed.
9. Update this guide, the README, and `docs/DECISIONS.md` for any new design choice.
10. Commit the logical change with a message that states what changed and why.

Never claim a concurrency guarantee because a test passed once. The protection must be visible in
the SQL lock, guarded update, unique index, or version predicate that makes the race safe.

## Operational notes

- The API listens on loopback (`127.0.0.1`) by default. It is not intended to be a network service.
- Sessions live in server memory. Restarting the server logs everyone out.
- `app/target` is generated build output and is ignored by Git.
- `scripts/import/master_verbatim.sql` is generated and ignored; regenerate it from the spreadsheet.
- Development accounts and passwords are for local testing only.
- The SQL build scripts are authoritative for a clean database; do not hand-edit a live database to
  make a test pass.

## Before handoff

The outgoing student should leave:

~~~bash
git status --short
git diff --check
./scripts/build_app.sh
~~~

They should also record which test suites were run, their results, any known limitation, and the
next unresolved decision in `docs/DECISIONS.md`.

