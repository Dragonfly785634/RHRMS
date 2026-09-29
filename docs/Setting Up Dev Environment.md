# RHRMS development environment on WSL

This repository setup is for the Ruby Hill Rescue Management System. 
This was built using Ubuntu on Wsl. 
The project uses Java 21, Maven, PostgreSQL, a local API server, and a terminal client.

## 1. Navigate to the repository

~~~bash
cd ~/RHRMS
pwd
~~~

The path should end in `/RHRMS`. Keep the project in the Linux filesystem when possible; 
builds and database scripts are slower under `/mnt/c`, and Linux permissions can behave unexpectedly.

## 2. Install Requirements

~~~bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven postgresql postgresql-contrib postgresql-client curl python3
~~~

Verify the tools:

~~~bash
java --version
javac --version
mvn --version
psql --version
curl --version
python3 --version
~~~

The project requires the Java 21 JDK. A JRE without `javac` cannot build the application.
Python is not required to run the Java server or terminal client, but it is required to run the API acceptance tests.

## 3. Start PostgreSQL

WSL may not start services automatically:

~~~bash
sudo service postgresql start
pg_lsclusters
sudo -u postgres psql -c "SELECT version();"
~~~

The cluster should report `online`. If WSL uses systemd, you may enable PostgreSQL at startup:

~~~bash
ps -p 1 -o comm=
sudo systemctl enable postgresql
~~~

Only run the second command when the first reports `systemd`. Otherwise, start the service after opening WSL.

## 4. Prepare the repository

From the repository root:

~~~bash
chmod +x scripts/*.sh tests/run_tests.sh tests/api/phase1.sh
~~~

The scripts share settings through `../scripts/config.sh`. Development passwords in that file are not production passwords. You can override a setting for one command, for example `PGPORT=5433 ./scripts/build_db.sh --sample`.

## 5. Create the database roles

Run this once on a new WSL installation:

~~~bash
./scripts/setup_db.sh
~~~

The script creates the `rhrms` database and its PostgreSQL roles, then saves development credentials in `~/.pgpass` with mode `600`. See [ROLES_AND_PASSWORDS.md](ROLES_AND_PASSWORDS.md) for the role model and development accounts.

If your PostgreSQL superuser command differs:

~~~bash
PSQL_SUPER='sudo -u postgres psql' ./scripts/setup_db.sh
~~~

## 6. Build the database

For normal development, create the schema and sample rescue data:

~~~bash
./scripts/build_db.sh --sample
~~~

This command **drops and recreates the `rhrms` schema**, so it erases data in that schema. Do not use it against data that must be preserved.

For an empty database, omit `--sample`:

~~~bash
./scripts/build_db.sh
~~~

## 7. Build the Java application

~~~bash
./scripts/build_app.sh
~~~

For a clean rebuild:

~~~bash
./scripts/build_app.sh --clean
~~~

The build creates `../app/target/rhrms.jar` and copies runtime libraries to `app/target/lib`.

## 8. Start and use the application

Start the API server:

~~~bash
./scripts/start_server.sh
curl http://127.0.0.1:8642/api/health
~~~

The default API address is `127.0.0.1:8642`. In another WSL terminal:

~~~bash
cd ~/QJR42EXT/RHRMS
./scripts/rhrms.sh
~~~

With sample data, the development accounts are:

| Username | Password | Role |
| --- | --- | --- |
| `mike` | `Director#2026` | Director |
| `diane` | `Staff#Diane26` | Staff |
| `jordan` | `Staff#Jordan26` | Staff |
| `sam` | `Volunteer#Sam26` | Volunteer |

At the `rhrms>` prompt, type `help`. You can pre-fill a username with `./scripts/rhrms.sh --user diane`.

Stop the server when finished:

~~~bash
./scripts/stop_server.sh
~~~

The server log is `../app/target/rhrms-server.log`; its PID file is `app/target/rhrms-server.pid`.

## 9. Run tests

The complete suite includes database rules, concurrency tests, and HTTP/API acceptance tests:

~~~bash
./tests/run_tests.sh
~~~

Run individual suites while diagnosing a problem:

~~~bash
./tests/run_tests.sh sql
./tests/run_tests.sh race
./tests/run_tests.sh api
~~~

Run selected Phase 1 sections by number:

~~~bash
./tests/api/phase1.sh 4 5 6
~~~

The test helpers rebuild the database with sample data. **Tests erase data in the `rhrms` schema.** Back up anything important first.

## 10. Daily workflow

~~~bash
cd ~/QJR42EXT/RHRMS
sudo service postgresql start
./scripts/build_app.sh
./scripts/start_server.sh
./scripts/rhrms.sh
~~~

After SQL changes, rebuild the database. After Java changes, rebuild and restart the server:

~~~bash
./scripts/build_db.sh --sample
./scripts/build_app.sh
./scripts/stop_server.sh
./scripts/start_server.sh
~~~

Before submitting:

~~~bash
git status --short
git diff --check
git ls-files
~~~

## Troubleshooting

**The application is not built yet:** run `./scripts/build_app.sh`.

**The server is not answering:** run `./scripts/start_server.sh`, then inspect `../app/target/rhrms-server.log`.

**PostgreSQL connection refused:** run `sudo service postgresql start` and confirm `pg_lsclusters` reports `online`.

**Password authentication failed:** run `./scripts/setup_db.sh` again and check `~/.pgpass`:

~~~bash
ls -l ~/.pgpass
chmod 600 ~/.pgpass
~~~

**Role does not exist:** run `./scripts/setup_db.sh` before `./scripts/build_db.sh`.

**Permission denied:** run `chmod +x scripts/*.sh tests/run_tests.sh tests/api/phase1.sh`.

**Port 5432 is busy:** inspect `pg_lsclusters` and `ss -ltnp | grep 5432`. If needed, choose another PostgreSQL port with `PGPORT`, consistently for setup, builds, tests, and the server.

**API port 8642 is busy:** set the same alternate address for the server and client:

~~~bash
export RHRMS_API_PORT=8643
export RHRMS_API_URL=http://127.0.0.1:8643
./scripts/start_server.sh
./scripts/rhrms.sh
~~~

## Related documentation

- [README.md](../README.md) — project overview and feature map
- [ROLES_AND_PASSWORDS.md](ROLES_AND_PASSWORDS.md) — accounts and database privileges
- [DECISIONS.md](DECISIONS.md) — implementation decisions and open questions
- [RHRMS_BUILD_SPEC.md](../Personal/RHRMS_BUILD_SPEC.md) — requirements and architecture

