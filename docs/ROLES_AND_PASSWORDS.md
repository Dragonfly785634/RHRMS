# Roles and passwords

Two different things are called a "role" in this system, and keeping them apart is the whole point
of the design. This page is the chart for both.

- **Application accounts** — Mike, Diane, Jordan, Sam. People. They log in at the terminal with a
  username and a password, and the system records everything they do against their name.
- **PostgreSQL login roles** — `rhrms_volunteer`, `rhrms_staff`, … Not people. These are the
  accounts the *server* uses to talk to the database, one per application role, each with only the
  privileges that role is allowed.

When Sam logs in, the server borrows a database connection that belongs to `rhrms_volunteer`. So
when Sam's terminal asks to approve an adoption, the request is refused twice by two different
pieces of software: once by the API, with a sentence Sam can act on, and once by PostgreSQL,
which has no `INSERT` privilege on the `decision` table for that role at all.

---

## 1. Application accounts (people who log in)

These four come with the development sample data (`sql/90_sample_data.sql`). **They do not exist
on a real installation**, which starts empty and asks for the director's account on first run.

| Username | Name | Role | Development password |
| --- | --- | --- | --- |
| `mike` | Mike (Director) | `DIRECTOR` | `Director#2026` |
| `diane` | Diane | `STAFF` | `Staff#Diane26` |
| `jordan` | Jordan | `STAFF` | `Staff#Jordan26` |
| `sam` | Sam (volunteer) | `VOLUNTEER` | `Volunteer#Sam26` |

Passwords are stored as BCrypt hashes at cost 12, and nothing — not the director, not the server,
not this document — can read one back. A forgotten password is reset, never recovered.

### What each application role may do

| | VOLUNTEER | STAFF | DIRECTOR |
| --- | :-: | :-: | :-: |
| See everything except user accounts | yes | yes | yes |
| Intake, including the dietary requirement | yes | yes | yes |
| Take an application, record a home visit | yes | yes | yes |
| Open or receive supplies, flag "order more" | yes | yes | yes |
| Correct records, move kennels, restrictions | – | yes | yes |
| Record an adoption, a fee, a return | – | yes | yes |
| Mark supplies ordered/received, write off, recount | – | yes | yes |
| Donations, vet visits, diets, prescriptions | – | yes | yes |
| **Decide an application** | never | only if the setting says so | yes |
| Override a decision or the vetting block | – | – | yes |
| Settings, user accounts, password resets | – | – | yes |
| Edit or delete the audit log | never | never | never |

"Decide an application" is the one row that is not fixed by the role alone. The setting
`decision.allowed_roles` decides it, and defaults to `DIRECTOR` on its own. The director can add
`STAFF` to it when he is away (open question Q4) without anybody rebuilding anything.
`VOLUNTEER` cannot be added: a volunteer records a *recommendation* on the home visit, and keeping
that separate from the decision is the clearest requirement change between Interview 1 and
Interview 3. The API refuses it, and `rhrms_volunteer` has no privilege on the table either.

---

## 2. PostgreSQL login roles (what the server connects as)

Created by `scripts/setup_db.sh`, privileges granted in `sql/05_db_roles.sql`.

| # | PostgreSQL role | Who uses it | Development password |
| --- | --- | --- | --- |
| 1 | `rhrms_owner` | The build scripts only. Owns the schema. | `owner_dev_pw` |
| 2 | `rhrms_auth` | The server, for logins and password checks — **nothing else** | `auth_dev_pw` |
| 3 | `rhrms_volunteer` | The server, for a session logged in as a VOLUNTEER | `volunteer_dev_pw` |
| 4 | `rhrms_staff` | The server, for a session logged in as STAFF | `staff_dev_pw` |
| 5 | `rhrms_director` | The server, for a session logged in as DIRECTOR | `director_dev_pw` |
| 6 | `rhrms_readonly` | Reports, and `scripts/psql_read.sh`. Can change nothing. | `readonly_dev_pw` |
| 7 | `rhrms_app` | The SQL test suite and `scripts/psql_app.sh`. Never the server. | `app_dev_pw` |

All seven live in one place, `scripts/config.sh`, which is also where you change them.

The three session roles build on each other, so each one only *adds* to the one below:

```
rhrms_volunteer  <  rhrms_staff  <  rhrms_director
```

`rhrms_auth` and `rhrms_readonly` are outside that chain on purpose.

### The actual privilege table

Read straight out of `information_schema` on a freshly built database. Each column shows what that
role is granted **directly**; staff also holds everything in the volunteer column, and the
director holds everything in both.

```
table                   vol    staff  dir    auth   readonly
animal                  I S    U      -      -      S
animal_name_history     I S    -      -      -      S
application             I S    U      -      -      S
audit_log                 S    -      -      -      S
auth_event              -      -        S    I S    -
bond_group                S    I U    -      -      S
decision                  S    I      -      -      S
diet                    I S    I U    -      -      S
donation                  S    I      -      -      S
food_order              I S    U      -      -      S
food_product              S    I U    -      -      S
home_visit              I S    -      -      -      S
kennel                    S    U      -      -      S
litter                  I S    U      -      -      S
open_bag                I S U  -      -      -      S
person                  I S    U      -      -      S
placement                 S    I U    -      -      S
prescription              S    I U    -      -      S
restriction               S    I U    -      -      S
setting                   S    -      I U      S      S
staff_user              (columns, see below)
stay                    I S    U      -      -      S
stock_level             I S U  -      -      -      S
stock_location            S    I U    -      -      S
stock_movement          I S    -      -      -      S
vet_visit                 S    I      -      -      S
```

Five things that table is saying, worth reading out loud:

1. **Nobody has `DELETE` on anything.** Not the director, not the owner's own app roles. Records
   are never deleted; a removal is a status change with a reason. There is a trigger that says so
   as well, so the refusal happens twice.
2. **`audit_log` is `SELECT` only for every role.** Rows get in exactly one way: through a
   `SECURITY DEFINER` trigger that runs as the owner. Nobody can add, alter or erase an entry.
   `auth_event`, the login trail, is the same.
3. **`decision` has no `UPDATE` for anybody.** A decision is permanent. A correction is a new
   decision that supersedes the old one, and both stay visible for ever.
4. **A volunteer has no `UPDATE` on `animal`.** They can record an arrival; they cannot quietly
   change one afterwards. Same for `restriction`, which they cannot touch at all.
5. **`rhrms_auth` cannot read an animal's name.** It exists only to check credentials, so a
   mistake in the login code cannot reach, leak or change any rescue data.

### Password hashes: exactly one role can read one

```
rhrms_auth       SELECT(password_hash), UPDATE(password_hash)
rhrms_director                          UPDATE(password_hash), INSERT
rhrms_app                               UPDATE(password_hash), INSERT
rhrms_owner      SELECT, INSERT, UPDATE  (it owns the table)
```

`staff_user` is granted column by column rather than table-wide, because a column privilege cannot
be subtracted from a table-wide `GRANT`. The effect:

- Only `rhrms_auth` can read a hash, and it is used for nothing else.
- The director can **set** somebody's password and cannot **read** anybody's. That is not a
  courtesy; it is enforced by PostgreSQL.
- `SELECT *` on `staff_user` fails for every session role, which is why the code always lists
  columns.
- The audit trigger blanks `password_hash` before writing to `audit_log`, so a hash cannot leak
  out through the History screen either. It records *that* the password changed, not what to.

---

## 3. Installing for real

Every password on this page is a development default and is in a file in the repository. For the
front-desk computer:

**1. Pick real passwords.** Set them in the environment before running setup, or edit
`scripts/config.sh`:

```bash
export RHRMS_OWNER_PASSWORD='...'
export RHRMS_AUTH_PASSWORD='...'
export RHRMS_VOLUNTEER_PASSWORD='...'
export RHRMS_STAFF_PASSWORD='...'
export RHRMS_DIRECTOR_PASSWORD='...'
export RHRMS_READONLY_PASSWORD='...'
export RHRMS_APP_PASSWORD='...'
./scripts/setup_db.sh
```

`setup_db.sh` can be re-run safely: existing roles keep their identity and have their password
reset to whatever config says, so the two never drift apart. It also rewrites the matching lines
in `~/.pgpass` rather than appending, because a stale line earlier in that file wins and produces
an authentication failure with no clue as to why.

**2. Give the server the same passwords.** Copy `app/src/main/resources/rhrms.properties` next to
the jar, put the real values in it, and make it readable only by the account that runs the server:

```bash
chmod 600 rhrms.properties
```

Or set `RHRMS_DB_AUTH_PASSWORD`, `RHRMS_DB_STAFF_PASSWORD` and so on in the environment, which
`scripts/export_app_env` in `config.sh` already does from `config.sh` itself.

**3. Build the database with no sample data**, so no development account exists:

```bash
./scripts/build_db.sh          # NOT --sample
```

**4. Create the director's account** from the terminal's first-run screen. It is offered only
while `staff_user` is completely empty, and it is the only way an account is ever created without
being logged in as a director.

**5. Do not move the server off `127.0.0.1`.** It speaks plain HTTP, which is acceptable only
because the loopback address cannot be reached from the network. Putting it on `0.0.0.0` would put
passwords on the wire in the clear.
