# What the rescue's spreadsheet contains, and what RHRMS can hold

Source: `Documents/MASTER copy FINAL (2).xlsx`, three sheets, 46 animal rows, 9 food rows,
9 donations. Loaded 2026-09-28. The file was not modified — `scripts/import_spreadsheet.sh`
checks its checksum before and after and refuses to continue if it changed.

Everything below is reproducible:

```bash
psql -c "SELECT * FROM rhrms_import.decision_log ORDER BY id"
```

`rhrms_import.animal_row` holds the spreadsheet verbatim — every `?`, every `yes`, the row that
says "Diane said she would keep this up" — so any claim here can be checked against what was
actually typed.

## What loaded

| | |
|---|---|
| Animals | **45** (46 rows; two are the same dog) |
| — available | 32 |
| — adopted | 12 |
| — held back pending a question | 1 |
| Stays | 45, with arrival date and source |
| Adopters | 15 |
| Applications | 15 |
| Decisions | **2 of 14** |
| Home visits | **0 of 9** |
| Placements | **0 of 12** |
| Restrictions | 3, recovered from free-text notes |
| Bonded pairs | 2 |
| Litters | 1 of a possible 6 |
| Food products | 4, 24 bags across 2 locations |
| Donations | 9 |
| Diets | **0** |

---

# 1. The wall: twelve adoptions that cannot be recorded as adoptions

This is the finding that matters most, so it goes first.

The spreadsheet shows twelve completed adoptions. RHRMS will not accept any of them, and the
reason is not that the rules are too strict.

To record an adoption the system needs, in order:

1. a **home visit** with findings — how many adults, children present, yard fenced, housing
   confirmed, and the visitor's reasoning in their own words (rule HV-1)
2. a **director's decision** with a rationale of at least 15 characters (rule DE-3)
3. a **placement** with a fee, a baseline, a payment method, and a reason if they differ (PL-1..PL-5)

The spreadsheet has a column headed `home visit` containing the word **`yes`**. Not a date, not a
visitor, not a finding. And six of the twelve adoptions have that column **blank** — they went
ahead with no home visit recorded at all.

There is no way to write "a visit happened but nobody wrote down what they saw." Every field on
`home_visit` is `NOT NULL`, which is correct for a visit happening next week and impossible for
one that happened in March.

**What the import did instead.** It loaded everything the spreadsheet does say — the adopter, the
phone number, the application date, the adoption date, the fee — and set each application to
`CLOSED`, with the reason written on the record:

> Imported from the 2026 spreadsheet, row 34. The adoption went ahead on 2/6/26 for $250, but the
> home visit findings and the director's decision were never recorded, so this application cannot
> be approved in the system and no placement record exists. The animal is marked ADOPTED and the
> stay is closed. Adoption paperwork for this animal predates RHRMS.

Nothing was invented. The animals are `ADOPTED`, their stays are closed on the right dates, and
the people who took them home are in the database and linked to them. What is missing is the
approval — because there is nothing to approve it with.

**What this costs you:** the twelve adoption fees are not money the system can total (see §7), and
`/api/reports` will show twelve adoptions with no placement records behind them.

**The two decisions that did load** are the exception that proves the rule. Both are denials, and
both loaded because somebody wrote down the substance:

- **Boomer / Greg Mattingly** — *"denied - apartment doesnt take dogs, told him reapply if he
  moves"*. A real reason, in the director's own words, mapping cleanly to `HOUSING_NO_PETS`. Loaded
  as a proper decision with that sentence as the rationale.
- **Barley / Tyler Boothe** — the note says `DENIED` and nothing else. The denial is a fact and was
  recorded; the rationale says *"row 40 recorded only the word DENIED and gave no reason… If the
  applicant asks, or reapplies, somebody will have to remember."*

The rule the import followed throughout: **record a process step where the spreadsheet recorded
its substance; never manufacture one where it didn't.**

---

# 2. Contradictions, kept rather than tidied

The README that came with the file says don't clean it — the mess is the evidence. Loading data
into a schema is unavoidably an act of interpretation, so the import kept both: the verbatim rows
in `rhrms_import`, and every judgement call in `decision_log`.

### Maisie — the one row that isn't a fact

Her home visit says `yes`. Her note says **"went home with the Alvarez family, lovely people."**
Her Adopted date and her fee are **both blank**.

So either she is in a home and the paperwork was never finished, or she is in a kennel and somebody
wrote the note early. The schema will not let the import sit on the fence:
`CHECK ((ended_on IS NULL) = (end_reason IS NULL))` means a stay cannot have ended on an unknown
date, and there is no `UNKNOWN` animal status.

Of the two available lies, **"she is available" is the dangerous one** — it offers an animal who may
already be in somebody's living room to a second adopter. She is loaded as `NOT_ADOPTABLE` with the
contradiction itself as the reason. **This is question one for the director.**

Her adopter is worse: recorded as **"Alvarez"** — no first name, no phone. `person.first_name` is
`NOT NULL`, so the record reads `(not recorded) Alvarez`, visibly a placeholder rather than a
guessed name.

### Rufus was adopted six days before he was applied for

Applied 4/18/26. Adopted 4/12/26. The note says *"paperwork got away from me on this one."*

Both dates loaded exactly as written. Nothing in the schema forbids it, because nothing in the
schema expected a placement to precede its own application — an ordering check would be worth
adding for data entered from now on.

### Cooper and Copper

Two rows, one letter apart. Same species, same breed (spelled `Pit mix` and `pit mix`), same age,
same intake date 4/26/26, same source. One has *"scar on muzzle"*; the other has no note.

Either one dog was entered twice, or two similar dogs arrived the same day. **The import cannot
tell, so it did not decide** — both are loaded. Merging them would lose an animal if they are two;
leaving them is a duplicate if they are one. There is no "possible duplicate" flag in the schema to
mark it with, which is a gap worth filling.

### Jen Kowalczyk and Jennifer Kowalcyzk

Two spellings — one of them a transposition — on the **same phone number**, `720-555-0198`. Row 36
is noted *"second cat for the same family i think."* That "i think" is the volunteer telling you she
wasn't sure either.

Almost certainly one person. The import loaded **two**, each with a caution in their notes, because
deciding that two records are one person is the rescue's call. This is one place the new system is
strictly better than the spreadsheet: searching the phone number returns both, side by side, which
is the moment a human can settle it.

### The donations total is wrong

Row 12 of the donations sheet reads **1025**. The nine cash donations above it add up to **1075**.
The typed total is out by 50 — a hardcoded number that drifted from the rows it was meant to
summarise. The nine donations were loaded individually; the total was not.

```
money_actually_donated | sheet_says_total | difference
                1075.00 |             1025 |      50.00
```

### Bella's renal food contradicts the food sheet

The food sheet: **2 bags, at the shelter**, last checked 2/11/26.
Bella's own note: **"1 bag left in garage as of aug."**

They disagree about the count *and* the location, and the note is six months newer. The food sheet
figure was loaded; the note is in her health notes. Somebody needs to walk to the garage.

---

# 3. Where the system is better than the spreadsheet

Not everything is a gap. Four things the file could not do, that now work:

**Luna is findable as Princess.** *"came in as Princess, renamed"* was a note nobody would think to
search. `animal_name_history` holds both, dated to her arrival, and `find princess` returns her.

**Both sides of a bonded pair know.** Otis's row said *"bonded w Pearl - do not separate."* Pearl's
row said nothing — so whoever opened Pearl's record first would never learn she had a partner.
A `bond_group` is a property of the pair, so both records now carry it. Same for the two ferrets,
whose note is the weaker *"these two came in together"* — recorded as such, because "came in
together" is not the same claim as "do not separate."

**Three restrictions came out of the notes column** and will now raise a conflict in front of the
director at decision time, instead of depending on whoever read the note last:

| Animal | Note | Recorded as |
|---|---|---|
| Moose | "jumps fence. tall fence only" | `NEEDS_FENCED_YARD` |
| Willow | "escape artist. crate at night" | `OTHER` |
| Scout | "scared of men, needs slow intro" | `OTHER` |

Scout is deliberately **not** `NO_ADULT_MEN`. The note says *needs slow intro* — a condition on
*how*, not a bar on *who*. `NO_ADULT_MEN` would refuse half the homes in the county on the strength
of six words. **All three need the director's confirmation**; this is the import reading intent.

Two notes deliberately did *not* become restrictions: Tank's *"GOOD with cats!! surprising"* is a
recommendation, and Marmalade's *"hates the carrier, needs 2 people"* is an instruction for staff,
not a condition on the adopter.

**Olive's litter was reconstructed.** Her note says *"had 4 kittens"*; four cats are recorded born
here on 4/1/26; one of them says *"mom was Olive."* Four kittens, four rows, one named mother — the
only one of the six litters in the file that can be pieced together.

---

# 4. What the spreadsheet tracks that RHRMS cannot hold

Five things, in order of how much they matter.

### Cat litter — not loaded at all

The food sheet tracks litter alongside the food, quantity `?`. The donations sheet records
*"litter + food."* RHRMS models **food products** with a bag weight and cups per meal. Litter has no
bag weight and nobody eats it.

**Options:** relax `food_product` into a general `supply_item` where `bag_lbs` and `lbs_per_cup` are
nullable and only feeding products carry them; or add a separate `consumable` table. The first is
less work and matches how the rescue already thinks — one list of things that run out.

### Two locations per product, but no "last counted"

`stock_level` is keyed on product and location, which matches `shelter` / `garage` exactly — a good
sign the schema was drawn from this sheet. But the sheet's `last checked` column has nowhere to go.
Every row says **2/11/26**: one stock-take, seven months stale. The import put that date in each
movement's reason, which is recoverable but not queryable. A `last_counted_at` on `stock_level`
would let the dashboard say "this count is 7 months old" instead of presenting it as current.

### `ORDER MORE` — no quantity, so no order

The RENAL row has `ORDER MORE` in a column with no header. No number of bags, no date. `food_order`
requires `bags > 0`, so **no order was created** — inventing a quantity would put a fictional order
on the dashboard. The flag itself is lost.

### The `pallet` column

Two rows carry the word `pallet` in a sixth column with no header. Kept verbatim in
`rhrms_import.food_row.extra`; no field in `stock_level` for it. Probably means "this is a sealed
pallet, don't count it as loose bags," which would be worth a proper field if so.

### `RENAL (Bella)` — an animal's name inside a product name

The product is named after the dog that eats it. It loaded verbatim, with a proper `prescription`
row linking it to Bella so FD-7 permits dispensing it to her. But that name works exactly until a
second dog needs renal food, and then there is nowhere to put it. Renaming it to the actual product
(`Science Diet k/d`, going by Bella's note) and letting the prescription do the linking is a
five-minute fix and worth doing before it bites.

---

# 5. What the spreadsheet simply doesn't record

These are not schema gaps. They are blank columns, and they have operational consequences.

### No animal has a sex

There is no sex column. `sex` is `NULL` for all 45 animals. It will have to be collected from the
animals themselves.

### No animal has been vetted

Vetting is recorded nowhere. One row says *"just came in, not vetted yet"*; the other 44 say
nothing. So all 45 are `NOT_VETTED`, and **rule PL-3 will refuse every single adoption** until each
animal is either vetted or the director overrides it in writing.

That is correct behaviour and it will bite on day one. The override works (a director must type a
reason of at least ten characters, which lands in the audit log), but somebody should expect to use
it, or work through 32 animals with the vet first.

### No animal has a kennel

There is no location column. `kennel_id` is `NULL` for all 45, so the dashboard kennel grid shows
**40 free kennels and 33 animals in the building**. That is an accurate report of what the
spreadsheet knows. Somebody has to walk the building with a clipboard once.

### No feeding portions anywhere

No cups per meal, no meals per day, for any animal. **This disables Phase 1 item 10.** The inventory
view is specified as "on hand, against what the animals in care need" — the *on hand* half works
(24 bags across two locations); the *needed* half cannot be computed from nothing. The forecast will
report days of supply as unknown until somebody enters portions.

This is the largest functional gap the import found, and the cheapest to close: it is one number per
animal, and the `diet` screen already exists.

### Litters are mostly lost

**Eleven** animals are marked `born here`, on six different dates — so six litters. Only one can be
reconstructed: Olive's four kittens. The other **seven** — Sunny, Tulip, Clementine, Juniper,
Cricket, Chip and Dale — have no litter and no parent recorded, though the dates group them into
plausible pairs (Clementine + Juniper on 6/6, Chip + Dale on 7/2) that somebody may remember.

### Ages are whole years with no reference date

`Age` is an integer and there are no birth dates. The ages were written at intake, so the import
derived `birth_date = intake date − age years` with `birth_date_is_estimate = TRUE`. Accurate to
within a year, which is what "approximate age" means — and unlike a bare number it doesn't silently
rot as time passes. A "7" written in January 2026 still says 7 today; a derived birth date says 8.

### One breed, spelled four ways

`Shepherd mix` (3 animals), `German Shepard mix` (2), `GSD mix` (1). Also `Pit mix` and `pit mix`.

All loaded verbatim. Searching `shep` finds five of the six — **`GSD mix` is invisible to it** — and
a report grouped by breed shows three breeds where there is one. Worth a decision on whether to
standardise going forward; the import will not do it retroactively, because who typed what is
evidence.

---

# 6. Bugs the spreadsheet found in RHRMS

Loading real data exposed three defects. All three are fixed, and all three are now tested by
`tests/api/import.sh` (61 checks).

### 1. RHRMS could not be installed on an empty database

`POST /api/bootstrap/director` failed with *"A result was returned when none was expected."* It used
`Ctx.update()` — which calls `executeUpdate()` — on a `SELECT set_config(...)` statement. JDBC
rejects that, so **the first-run screen never worked**, and the system could not be installed at
all.

It survived 297 other checks because every test starts from `build_db.sh --sample`, which inserts
the staff accounts with plain SQL. `tests/api/lib.sh` even has a `fresh_empty()` helper, written for
a first-run test **that was never written** — the helper was called from nowhere.

Fixed by adding `Ctx.run()`, which uses `execute()` and tolerates a result set, with a comment
saying why it exists.

### 2. The director's vetting override had the identical bug

`AdoptionApi:616` made the same `Ctx.update()`-on-a-`SELECT` call to set `rhrms.override_vetting`.
Nothing in the suite had ever asked a director to place an unvetted animal, so nothing had ever run
the line. Given that **every animal in this spreadsheet is unvetted**, this would have failed the
first time it was needed. Same fix.

### 3. The animal search did not cover breed

`/api/animals?q=shep` returned nothing, while `/api/animals/available?q=shep` returned the
shepherds — the same query giving different answers depending on which endpoint you reached it
through. Found because the rescue's own file arrived with six shepherd mixes in it and none of them
turned up. Breed added to the general search.

### 4. A refused re-import destroyed the provenance tables (in my own importer)

The runner rebuilt the staging schema *before* checking the "already imported" guard, so a second,
correctly-refused import still dropped `decision_log` and the link tables on its way out — a refusal
that did damage. The guard now runs first. Caught by the test that runs the import twice.

---

# 7. Questions for the director

Twenty, in the order I'd ask them. `SELECT * FROM rhrms_import.decision_log WHERE ask_director;`

**Needed before anyone adopts anything:**

1. **Maisie** — is she in a home or in a kennel? She is held back until you say.
2. **Portions** — one line per animal, so the food forecast can work.
3. **Vetting** — which of the 32 available animals have actually been vetted? Every adoption is
   blocked until this is answered or overridden.
4. **Kennels** — which animal is in which kennel?

**Needed soon:**

5. **Cooper and Copper** — one dog entered twice, or two dogs?
6. **Jen / Jennifer Kowalczyk** — one person or two?
7. **The three restrictions** — are Moose, Willow and Scout recorded correctly? (I read the notes;
   you know the animals.)
8. **The ferrets** — "came in together": must they stay together?
9. **The twelve historical adoptions** — is "closed, paperwork predates RHRMS" acceptable, or do you
   want a legacy-adoption record so the fees appear in the books?

**Worth knowing:**

10. Barley's first denial had no reason recorded — do you remember it?
11. Rufus is dated as adopted before he was applied for.
12. The donations total on the sheet is out by $50.
13. Bella's renal food: 2 bags at the shelter, or 1 in the garage?
14. `RENAL (Bella)` — what is the product actually called?
15. `ORDER MORE` on the renal food — how many bags?
16. The food count is from 2/11/26. Still right?
17. Cat litter needs somewhere to live in the system.
18. Winnie's fee was $175 with no reason recorded — PL-2 would refuse that today.
19. Seven "born here" animals have no mother recorded.
20. Four spellings of one breed — standardise going forward?

---

# 8. How to re-run it

```bash
./scripts/build_db.sh                  # empty database
./scripts/start_server.sh              # then create the director on the first-run screen
./scripts/import_spreadsheet.sh        # reads the .xlsx, loads it, prints the questions
```

The three stages are separate on purpose:

| Stage | File | Job |
|---|---|---|
| Transcribe | `scripts/import/read_master.py` | `.xlsx` → verbatim SQL. Standard library only; opens the file read-only |
| Stage | `sql/91_import_staging.sql` | the `rhrms_import` schema: all `TEXT`, nothing interpreted |
| Interpret | `sql/92_import_master.sql` | every judgement call, commented next to the statement that makes it |

Keeping them apart means any argument about the import is an argument about stage 3, and can be
settled by querying stage 2. Running it twice is refused rather than doubling the data.
