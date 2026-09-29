# RHRMS quick guide

This is the daily front-desk reference. It assumes the server has already been started.

## Start

A developer or administrator starts the service:

~~~bash
./scripts/start_server.sh
~~~

Each staff member opens a separate terminal:

~~~bash
./scripts/rhrms.sh
~~~

Log in with your own application account. Never share an account; the audit trail records the
logged-in person.

## Common commands

| Command | Use |
| --- | --- |
| `help` | Show commands allowed for the current role |
| `dash` | Open the dashboard |
| `kennels` | View all kennels and occupants |
| `find <name>` | Search animals |
| `animal <id or code>` | Open an animal record |
| `intake` | Record an arriving animal |
| `status <animal>` | Change an animal status |
| `apply` | Enter an adoption application |
| `visit <application>` | Record a home visit |
| `decide <application>` | Record the director's decision |
| `place <application>` | Record an adoption |
| `return <animal>` | Record a returned adoption |
| `food` | View inventory and forecast |
| `move` | Receive, open, or correct stock |
| `diet <animal>` | Record a current diet |
| `history <table> <id>` | Review audit history |
| `recent` | Review recent changes |
| `logout` | End the current session |

Type `help` for the exact command syntax available to your role. A command hidden from `help` is
not a security boundary; the server checks permissions again.

## Safe behavior

- Read the refusal message before trying again.
- If an action says nothing was saved, verify the record before repeating a request.
- Do not use database rebuild commands during normal operations.
- Do not edit PostgreSQL tables directly to bypass a refusal.
- Record a useful reason when the screen asks for one.
- Use your own login so the audit trail remains meaningful.

## Food

Use `food` before opening or receiving stock. The forecast shows on-hand stock, daily need, days of
supply, and reorder status. Prescription food is linked to an animal's current prescription; the
database refuses an invalid prescription-food link.

## Adoption

The normal path is:

~~~text
person -> application -> home visit -> director decision -> placement
~~~

Every application needs a visit before approval unless the documented reuse flow applies. A denial
requires a reason. A restriction conflict must be acknowledged in writing before approval. Placement
records the fee, payment method, person who recorded it, and the audit reason.

## Ending a session

Use `logout` when another person will use the computer. Use `quit` to log out and close the terminal.
An idle session eventually expires; log in again when prompted.

