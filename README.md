# RHRMS — Ruby Hill Rescue Management System

A terminal system for the one front-desk computer at a small animal rescue. It replaces a shared
spreadsheet that four people overwrite, and it records who changed what and why, so the director
can prove later why any placement was or was not made.

Three parts:

```
terminal  ──HTTP/JSON──▶  local API server  ──JDBC──▶  PostgreSQL
(any number)              (127.0.0.1:8642)             (rules live here too)
```

Every rule, every permission check and every password check is on the server side. The terminal
holds no database password and enforces nothing, so it can be replaced with a web page later
without a single rule moving. See `docs/DECISIONS.md` (D-02) and `docs/ROLES_AND_PASSWORDS.md`.

---

## Quick start

```bash
sudo apt install -y postgresql postgresql-contrib   # once
sudo service postgresql start
./scripts/setup_db.sh          # creates the 7 database roles and the database
./scripts/build_app.sh         # compiles the server and the terminal
./scripts/build_db.sh --sample # schema, rules, audit, and the sample rescue
./scripts/start_server.sh      # the local API
./scripts/rhrms.sh             # a terminal; log in as mike / Director#2026
```

Then `help` at the `rhrms>` prompt. `./scripts/stop_server.sh` when you are done.

Development logins: `mike` / `Director#2026`, `diane` / `Staff#Diane26`,
`jordan` / `Staff#Jordan26`, `sam` / `Volunteer#Sam26`.
A real installation starts with no accounts and asks for the director's on first run.

### Loading the rescue's own data

To start from the real spreadsheet instead of the sample rescue:

```bash
./scripts/build_db.sh                 # empty: schema, rules, audit, roles, settings
./scripts/start_server.sh
./scripts/rhrms.sh                    # the first-run screen creates the director
./scripts/import_spreadsheet.sh       # loads Documents/MASTER copy FINAL (2).xlsx
```

That gives you 45 animals, 15 adopters, the food stock and the donations, and prints the twenty
questions the spreadsheet could not answer. The spreadsheet itself is never written to — the script
checks its checksum before and after.

What loaded, what could not, and why, is in **[docs/SPREADSHEET_FINDINGS.md](docs/SPREADSHEET_FINDINGS.md)**.
The short version: the twelve historical adoptions are loaded as far as the evidence goes and no
further, because HV-1 and DE-3 want a home visit with findings and a decision with a rationale, and
the spreadsheet recorded the word `yes`.

## Running the tests

```bash
./tests/run_tests.sh           # everything: database rules, Phase 1, and the terminal
./tests/run_tests.sh sql       # just the database rules  (L1, L2, L3, L4)
./tests/run_tests.sh race      # just the two-session concurrency tests
./tests/run_tests.sh api       # just the Phase 1 acceptance tests, over HTTP
./tests/run_tests.sh term      # just the terminal client, through a pseudo-terminal
./tests/run_tests.sh import    # just installing from empty, and the spreadsheet import
./tests/api/phase1.sh 4 5 6    # just those Phase 1 items
```

All of them rebuild the database first, so **your data is erased** — including a spreadsheet import,
so re-run `./scripts/import_spreadsheet.sh` afterwards if you want the rescue's data back.
The API tests need `curl` and `python3`; the terminal tests also need `script` (util-linux).

## Filling in the intake form

The intake screen is paper form **RH-1** (`Documents/Animal_Intake_Form.pdf`), box for box and in
the same order: animal name, came in as, kennel no., type, breed, age and whether it is exact,
date and time in, where from, bonded with, mother if born here, the notes box, and who took the
animal in. When it saves, it gives you the number to write in the form's **No.** box.

At any box, a word starting with a **dot** is a system command, never an answer:

| | |
| --- | --- |
| `.q` `.quit` `.e` `.exit` | leave the form; nothing is saved |
| `.h` `.help` `.?` | list these |

That is what the dot is for: "exit" is a perfectly ordinary thing to write in a notes box about an
animal that got out, and `.exit` is not. Anything else starting with a dot is refused rather than
stored, so a typo cannot end up in a record. Dots are not honoured at a password prompt, because a
password is allowed to start with one.

## The Phase 1 features, and where each one is tested

`tests/api/phase1.sh` has one section per item. Each is checked both ways round: the main flow
works, **and** the wrong thing is refused with a sentence the front desk can act on.

| # | Feature | Endpoint | Terminal command |
| --- | --- | --- | --- |
| 1 | Animal intake, including the dietary requirement | `POST /api/animals/intake` | `intake` |
| 2 | Status lifecycle | `POST /api/animals/{id}/status` | `status` |
| 3 | Available listing and search | `GET /api/animals/available` | `find` |
| 4 | Adoption application | `POST /api/applications` | `apply`, `visit` |
| 5 | Application decision | `POST /api/applications/{id}/decision` | `decide` |
| 6 | Adoption placement | `POST /api/applications/{id}/place` | `place`, `return` |
| 7 | Supply items: type, unit, quantity | `GET/POST /api/food/products` | `supplies` |
| 8 | Stock movements: receive and dispense | `POST /api/food/movements` | `move` |
| 9 | Dietary link | `POST /api/food/diets` | `diet` |
| 10 | Inventory view: on hand vs needed | `GET /api/inventory` | `food` |

The terminal has **19 commands** in total, covering those ten plus `help clear dash animal person
passwd admin`. It used to have 48; see `docs/DECISIONS.md` (D-30) for what was folded, what was
dropped, and why. **The API was not cut** - every endpoint still exists and is still tested, so a
later phase can put a screen back on top of one without rebuilding it.

`GET /api` lists every endpoint, with the permission each one needs and whether it asks for a
password again.

## Layout

```
RHRMS/
├── sql/          the database: tables, rules as triggers, audit, db roles, seed, sample data
│                 91/92_import_*.sql load the rescue's spreadsheet
├── scripts/      setup, build, start/stop the server, open a terminal
│   └── import/   read_master.py: the .xlsx reader (standard library only)
├── app/          Java: the API server and the terminal client
├── tests/        sql/ database rules   api/ Phase 1, terminal, and import tests
├── Documents/    what the client gave us: interviews, form RH-1, the spreadsheet
└── docs/         DECISIONS.md   ROLES_AND_PASSWORDS.md   SPREADSHEET_FINDINGS.md
```

`app/target/` is Maven's build output — compiled classes, `rhrms.jar` and the dependency jars.
It is generated, and `mvn clean` deletes it safely. Note the server's pid file and both log files
live there too, so a clean also deletes your logs.

---

# Stage 1: the database

The rest of this page is the database layer on its own: tables, business rules, audit log, sample
data and the SQL test suite. **If the database refuses something, the API shows the message; that
is all.**

## 1. Install PostgreSQL in WSL (one time)

Open your WSL (Ubuntu) terminal.

**1. Check your Ubuntu version** (22.04 installs PostgreSQL 14, 24.04 installs 16; both work):

```bash
lsb_release -a
```

**2. Install PostgreSQL:**

```bash
sudo apt update
sudo apt install -y postgresql postgresql-contrib
```

**3. Start the server.** WSL doesn't always start services on its own.

```bash
sudo service postgresql start
```

Check that it's running. You should see `online` on port 5432:

```bash
pg_lsclusters
```

> **Start it automatically (optional).** Most WSL installs run `systemd`. Check with `ps -p 1 -o comm=`: if it says `systemd`, run `sudo systemctl enable postgresql` once and it will start with WSL. If it says `init`, either run `sudo service postgresql start` each time you open WSL, or turn on systemd by adding these lines to `/etc/wsl.conf`:
> ```
> [boot]
> systemd=true
> ```
> then run `wsl --shutdown` in Windows PowerShell and reopen WSL.

**4. Test the install:**

```bash
sudo -u postgres psql -c "SELECT version();"
```

## 2. Set up the RHRMS database (one time)

Keep the project **inside the Linux file system** (e.g. `~/cs444/rhrms-db`), not under `/mnt/c/...`. Scripts run much faster there and file permissions work properly.

```bash
cd ~/cs444/rhrms-db
chmod +x scripts/*.sh tests/run_tests.sh
./scripts/setup_db.sh            # asks for your sudo password once
```

This creates:

| What | Name | Purpose |
| --- | --- | --- |
| Database | `rhrms` | everything lives in schema `rhrms` inside it |
| Role | `rhrms_owner` | owns the tables; used only by the build scripts |
| Role | `rhrms_auth` | the server's login path. The **only** role that can read a password hash. |
| Role | `rhrms_volunteer` | the server, for a session logged in as a VOLUNTEER |
| Role | `rhrms_staff` | the server, for a session logged in as STAFF |
| Role | `rhrms_director` | the server, for a session logged in as DIRECTOR |
| Role | `rhrms_readonly` | reports and `psql_read.sh`; can change nothing |
| Role | `rhrms_app` | the SQL test suite and `psql_app.sh`. Never used by the server. |

None of them can delete anything, and none can write to the audit log.
The full chart, with every password and the exact privilege table, is in
**`docs/ROLES_AND_PASSWORDS.md`**.

It also saves the development passwords in `~/.pgpass`, so you won't be prompted. The passwords are set in `scripts/config.sh`; change them before a real install.

## 3. Build and test

```bash
./scripts/build_db.sh --sample   # build tables + rules + sample data
./tests/run_tests.sh             # expect: ALL 78 TESTS PASSED
```

`run_tests.sh sql` runs only the SQL tests. `run_tests.sh race` runs only the concurrency tests. The test run rebuilds the database several times and leaves a fresh sample database behind.

## 4. Look around

```bash
./scripts/psql_app.sh
```

Try these queries:

```sql
SELECT * FROM kennel_board WHERE animals > 0;           -- who is in which kennel
SELECT name, sealed_bags, lbs_per_day, days_of_supply, food_status, eaten_by FROM food_forecast;
SELECT applicant, outcome, denial_reason, rationale, decided_by, visitor_name FROM decision_record;
SELECT at, user_id, action, table_name, reason FROM audit_log ORDER BY id DESC LIMIT 10;
```

**Try adopting Bella.** Every change must say who is making it, so start a transaction and set the user:

```sql
BEGIN;
SET LOCAL rhrms.user_id = '2';                          -- Diane
SELECT place_animal(2, 2, 250, 'CASH');                 -- application 2 = Jamie Lee
SELECT name, status, kennel_id FROM animal WHERE name = 'Bella';
ROLLBACK;                                               -- or COMMIT to keep it
```

**Try breaking it.** Each of these is refused with a plain-English message. The first line lets psql keep going after each refusal:

```sql
\set ON_ERROR_ROLLBACK on
BEGIN; SET LOCAL rhrms.user_id = '4';
SELECT move_stock(3, 1::smallint, 'OPEN', 5, 4);         -- more bags than exist
UPDATE animal SET kennel_id = 1 WHERE name = 'Luna';     -- Bella's kennel
DELETE FROM person;                                      -- nothing is ever deleted
ROLLBACK;
```

**GUI tools (optional).** pgAdmin or DBeaver on Windows can connect to WSL at `localhost:5432`, database `rhrms`, user `rhrms_app`, password `app_dev_pw`.

---

## 5. How the database enforces the four levels

| Level | Enforced by | Examples |
| --- | --- | --- |
| **L1 It works** | Tables, views, stored functions | `place_animal` does the whole adoption; `food_forecast` computes days of supply |
| **L2 It doesn't lie** | CHECK constraints + triggers that raise a plain message with a `RULE xx` hint | Stock can't go below 0; no application for an adopted animal; a denial needs a reason; approving over a restriction conflict needs a written acknowledgement |
| **L3 Holds under contention** | Row locks inside triggers/functions + partial unique indexes + `version` columns | Last bag can't be opened twice; only one approved application and one active placement per animal; stale edits are detected |
| **L4 Explains itself** | `audit_log` written by a trigger on every table; the app role cannot write or change it | Who, when, old row, new row, reason, "entered on behalf of"; decisions and home visits can't be edited, only superseded |

### How the terminal UI must talk to the database

Every write happens in a transaction that says who is acting. A change without a user is refused.

```sql
BEGIN;
SET LOCAL rhrms.user_id      = '<staff_user.id>';     -- required
SET LOCAL rhrms.reason       = '<why>';               -- when the screen asks for one
SET LOCAL rhrms.on_behalf_of = '<volunteer name>';    -- when typing for someone without a login
-- inserts / updates / SELECT place_animal(...) etc.
COMMIT;
```

To show an error, print the error's `MESSAGE`. The `HINT` (e.g. `RULE PL-1`) tells the code which rule fired.

### The tables

| Area | Tables |
| --- | --- |
| People & access | `staff_user`, `person`, `setting` |
| Animals | `animal`, `animal_name_history`, `restriction`, `kennel`, `bond_group`, `litter`, `stay`, `vet_visit` |
| Adoption | `application`, `home_visit`, `decision`, `placement` |
| Food | `food_product`, `prescription`, `diet`, `stock_location`, `stock_level`, `open_bag`, `stock_movement`, `food_order`, `donation` |
| Audit | `audit_log` |
| Views | `food_forecast`, `kennel_board`, `restriction_conflicts`, `decision_record` |

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `connection refused` / `could not connect` | The server isn't running: `sudo service postgresql start` |
| `Peer authentication failed` | Connect over TCP: the scripts already use `-h localhost`; do the same if typing psql yourself |
| `password authentication failed` | Re-run `./scripts/setup_db.sh`, or check `~/.pgpass` (must be `chmod 600`) |
| Port 5432 already in use | A Windows PostgreSQL is running. Stop it, or run `PGPORT=5433 ./scripts/...` after changing the WSL port in `/etc/postgresql/*/main/postgresql.conf` |
| `permission denied` running a script | `chmod +x scripts/*.sh tests/run_tests.sh` |
| `bad interpreter: /bin/bash^M` | The files got Windows line endings: `sudo apt install dos2unix && dos2unix scripts/*.sh tests/*.sh` |
| Start over completely | `./scripts/build_db.sh --sample` (rebuilds the schema; roles and database stay) |
