# Administrator guide

This guide is for the person who installs, backs up, restores, and hands off RHRMS. For daily staff use,
see `QUICK_GUIDE.md`. For developer setup, see `Documents/Setting Up Dev Environment.md`.

## Installation

1. Install PostgreSQL, Java 21, Maven, curl, and Python 3.
2. Start PostgreSQL.
3. Run `./scripts/setup_db.sh`.
4. Run `./scripts/build_db.sh` for an empty installation or `./scripts/build_db.sh --sample` for development data.
5. Run `./scripts/build_app.sh`.
6. Start the server with `./scripts/start_server.sh`.
7. Use the first-run screen to create the initial director account on an empty installation.

The sample build creates development accounts. Do not use those passwords on a real installation.

## Configuration

Installation-specific database credentials may be provided through environment variables or an
external `rhrms.properties` file. Do not commit real credentials. The lookup order is documented in
`app/src/main/java/org/rubyhill/rhrms/config/AppConfig.java`.

The API defaults to loopback address `127.0.0.1` and port `8642`. If the port changes, set
`RHRMS_API_PORT` and `RHRMS_API_URL` consistently before starting both the server and terminal.

## Backup

Create a logical PostgreSQL backup before rebuilding or migrating:

~~~bash
pg_dump -h "$PGHOST" -p "$PGPORT" -U "$RHRMS_OWNER" \
  --format=custom --file="rhrms-$(date +%Y%m%d-%H%M%S).dump" "$RHRMS_DB"
~~~

Keep backups outside the repository. A dump contains rescue data and must be protected like the
database itself.

## Restore

Restore into a database prepared for the same schema version:

~~~bash
pg_restore -h "$PGHOST" -p "$PGPORT" -U "$RHRMS_OWNER" \
  --clean --if-exists --dbname="$RHRMS_DB" path/to/rhrms-backup.dump
~~~

Stop the API before restoring. Verify the restored database with read-only queries and restart the
server afterward. Never restore a backup over the only copy of current data.

## Rebuilding

`./scripts/build_db.sh` drops and recreates the `rhrms` schema. It is for a clean rebuild and testing,
not for preserving live data. Back up first.

The spreadsheet importer is one-time per database build:

~~~bash
./scripts/import_spreadsheet.sh
~~~

It checks the source checksum, records provenance, and refuses a second import into the same
database. Read `docs/SPREADSHEET_FINDINGS.md` before treating imported historical records as complete.

## Server operations

~~~bash
./scripts/start_server.sh
./scripts/stop_server.sh
~~~

Use `--foreground` when diagnosing startup:

~~~bash
./scripts/start_server.sh --foreground
~~~

Logs and the PID file are under `app/target`. A server restart ends all in-memory sessions.

## User administration

Application users are created and managed through the director-only terminal commands. PostgreSQL
roles are infrastructure accounts and should not be confused with application users. The mapping and
privileges are in `docs/ROLES_AND_PASSWORDS.md`.

Do not edit password hashes directly. Use the application password-change or reset flow.

## Handoff checklist

- [ ] PostgreSQL is running and the database responds.
- [ ] The database backup location is documented outside the repository.
- [ ] Real credentials are outside Git.
- [ ] `./scripts/build_app.sh` succeeds.
- [ ] The server health endpoint answers.
- [ ] The next student has read `docs/HANDOFF.md` and `../Personal/TRACEABILITY.md`.
- [ ] Known decisions and limitations are recorded in `docs/DECISIONS.md`.

