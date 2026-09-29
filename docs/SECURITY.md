# Security model

RHRMS is designed for one rescue computer. The API binds to `127.0.0.1`, so it is reachable only
from the local machine by default. It uses HTTP on loopback because the service is not exposed to a
network; do not change the bind address without adding transport security and an access-control
plan.

## Three enforcement layers

1. The terminal hides commands the current person cannot use. This is a usability aid, not a
   security boundary.
2. The API authenticates the session, checks the application permission, and checks a password
   again for critical actions.
3. PostgreSQL connects through a role matching the application role and enforces table privileges,
   triggers, constraints, and append-only audit behavior.

A new endpoint must declare a permission in `Router`. A new write must use a transaction helper and
set the audit context. A new critical action should use `Router.critical` so a password confirmation
cannot be accidentally omitted.

## Accounts and secrets

Application passwords are BCrypt hashes. The authentication role can read hashes for login checks;
normal application roles cannot. Password hashes are redacted by the audit trigger. Development
passwords in `scripts/config.sh` and `app/src/main/resources/rhrms.properties` are examples only.
Change them before any real installation and keep real configuration outside Git.

`setup_db.sh` writes PostgreSQL credentials to `~/.pgpass` and sets mode `600`. Do not commit that
file, a real `rhrms.properties`, or generated logs.

## Audit and accountability

Every business-table insert or update is captured by an owner-security trigger. The application
roles can read history but cannot insert, update, or delete audit rows. Deletes are refused; mutable
records are changed through status fields or append-only replacement records.

The audit context is transaction-local:

~~~sql
SET LOCAL rhrms.user_id = '4';
SET LOCAL rhrms.reason = 'Recorded return from phone call';
SET LOCAL rhrms.on_behalf_of = 'Volunteer name';
~~~

Never accept a client-supplied `user_id` as proof of identity. The API takes the user from the live
session and passes it into the database transaction.

## Session behavior

Sessions are random in-memory tokens. They expire after the configured idle period and disappear
when the server stops. A restart therefore logs out every terminal, which is intentional on a
shared front-desk computer.

## Security review checklist

Before accepting a change, check:

- no password or database role credential was added to source control;
- the route has the least permission that fits the action;
- critical, irreversible actions require confirmation;
- input is validated in the API and protected by database constraints;
- every write has a transaction-local audit user and reason;
- concurrent writes use locks, unique constraints, or version checks;
- error responses do not expose SQL statements, password hashes, or stack traces.

