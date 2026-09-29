-- =====================================================================
-- RHRMS · 92_import_master.sql
-- Reads the transcribed spreadsheet in rhrms_import and loads it as rescue data.
--
-- This file is the argument, not the transcription. Every place where the
-- spreadsheet is ambiguous, contradictory or silent, the reasoning is written
-- down next to the statement that acts on it, and recorded in
-- rhrms_import.decision_log so it can be read back without reading SQL.
--
-- Two rules this file keeps:
--
--   1. NEVER INVENT A FACT. Where the spreadsheet is silent the column stays
--      NULL. No guessed sexes, no guessed vet dates, no made-up home-visit
--      findings. A NULL is a true statement about what the rescue recorded;
--      a plausible value is a lie that survives forever.
--
--   2. THE IMPORT DOES NOT PUT ITS OWN PROSE IN THE RESCUE'S FIELDS. Notes
--      columns get the spreadsheet's words and nothing else. Where a NOT NULL
--      constraint forces the import to write something (not_adoptable_reason,
--      closed_reason, decision.rationale) the text begins "Imported from the
--      2026 spreadsheet" so nobody mistakes it for something a volunteer said.
--
-- The honest consequence of rule 1 is that the twelve completed adoptions in
-- the spreadsheet CANNOT be recorded as adoptions in this system. HV-1 and
-- DE-3 require a home visit with findings and a decision with a rationale, and
-- the spreadsheet recorded a 'yes' and a date. That is not the rules being too
-- strict; it is the spreadsheet not holding the information the rules are
-- about. Those adoptions are loaded as far as the evidence goes and no further.
-- See docs/SPREADSHEET_FINDINGS.md.
-- =====================================================================

BEGIN;
SET search_path = rhrms;

-- ---------------------------------------------------------------------
-- Who is doing this, and refusing to do it twice
-- ---------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM staff_user WHERE role = 'DIRECTOR' AND active) THEN
    RAISE EXCEPTION 'There is no active director account to attribute this import to.'
      USING HINT = 'Create the first account with the terminal''s first-run screen, '
                   'or use scripts/build_db.sh --sample for a development database.';
  END IF;
  IF EXISTS (SELECT 1 FROM setting WHERE key = 'import.master_spreadsheet') THEN
    RAISE EXCEPTION 'This spreadsheet has already been imported (%).',
      (SELECT value FROM setting WHERE key = 'import.master_spreadsheet')
      USING HINT = 'Rebuild the database with scripts/build_db.sh before importing again.';
  END IF;
  IF (SELECT count(*) FROM rhrms_import.animal_row) = 0 THEN
    RAISE EXCEPTION 'The staging tables are empty. Run scripts/import/read_master.py first.';
  END IF;
END $$;

-- Every row this file writes is audited against a real person, with a reason that names
-- the source document, so the History screen can answer "where did this come from?".
SELECT set_config('rhrms.user_id',
  (SELECT id::text FROM staff_user WHERE role = 'DIRECTOR' AND active ORDER BY id LIMIT 1), true);
SELECT set_config('rhrms.reason',
  'Imported from Documents/MASTER copy FINAL (2).xlsx', true);

-- =====================================================================
-- 1. The animals
-- =====================================================================
-- Derivations applied to every row, each one a reading that could be wrong and so is
-- written down here rather than buried:
--
--   species      'dog'/'cat' are unambiguous. 'other' appears twice, both with 'ferret'
--                in the breed column, and the schema has a FERRET species, so they become
--                FERRET rather than OTHER - the more specific true answer.
--
--   breed        verbatim. The spreadsheet spells one breed four ways ('Shepherd mix',
--                'German Shepard mix', 'GSD mix', 'pit mix' vs 'Pit mix'). Normalising
--                them would destroy the evidence of who typed what. Note this is not
--                harmless: searching 'shep' finds 'Shepherd mix' and 'German Shepard mix'
--                but NOT 'GSD mix', so one dog is invisible to the obvious search.
--
--   birth_date   the spreadsheet has a whole-number Age and no birth dates. The ages were
--                written when the animal arrived, so birth_date = intake date minus that
--                many years, with birth_date_is_estimate TRUE (IN-8). This is a derived
--                value: it is right to within a year, which is what "approximate age"
--                means, and unlike a bare number it does not silently rot as time passes.
--
--   sex          NOT IN THE SPREADSHEET AT ALL. Left NULL for all 45 animals.
--
--   vet_status   not recorded either, so every animal is NOT_VETTED, the schema default.
--                One row ('just came in, not vetted yet') confirms it for one cat and says
--                nothing about the other 44. This has a real consequence, flagged below.
--
--   kennel_id    the spreadsheet has no location column, so NULL for every animal. The
--                kennel grid on the dashboard will be empty until somebody walks the
--                building with a clipboard.
DO $$
DECLARE
  r        RECORD;
  new_id   BIGINT;
  v_species TEXT;
  v_status  TEXT;
  v_reason  TEXT;
BEGIN
  FOR r IN SELECT * FROM rhrms_import.animal_row WHERE row_no BETWEEN 2 AND 47 ORDER BY row_no LOOP

    -- Row 41 is the second Barley row. It is not a second Barley: same name, same breed,
    -- same age, same intake date and same source as row 40, with a different adopter.
    -- Read together the two rows are one dog and two adoption attempts - the first denied,
    -- the second successful. Row 41 therefore creates an APPLICATION later, not an animal.
    IF r.row_no = 41 THEN CONTINUE; END IF;

    v_species := CASE lower(trim(r.type))
                   WHEN 'dog' THEN 'DOG'
                   WHEN 'cat' THEN 'CAT'
                   ELSE CASE WHEN r.breed ILIKE '%ferret%' THEN 'FERRET' ELSE 'OTHER' END
                 END;

    -- Maisie (row 17) is the one row that cannot be read as a fact. Her "home visit" says
    -- yes, her note says "went home with the Alvarez family", and her Adopted date and fee
    -- are both blank. So either she is in a home and the paperwork was never finished, or
    -- she is in a kennel and somebody wrote the note early.
    --
    -- The schema will not let the import sit on the fence: CHECK ((ended_on IS NULL) =
    -- (end_reason IS NULL)) means a stay cannot have ended on an unknown date, and there
    -- is no 'UNKNOWN' animal status. Of the two available lies, "she is available" is the
    -- dangerous one - it offers an animal who may already be in somebody's living room to
    -- a second adopter. So she is held out of the adoptable list until the director says
    -- which it is, and the reason on the record is the contradiction itself.
    -- Every animal is inserted as AVAILABLE, including the twelve the spreadsheet shows as
    -- adopted and Maisie, who is held back in section 11. This is not laziness about the
    -- end state: AP-1 refuses an application for an animal who is not adoptable, and the
    -- application is the only surviving record of who took an animal home. So the animals
    -- start where they started, their applications are entered, and only then are they
    -- walked to where the spreadsheet says they ended up.
    v_status := 'AVAILABLE';
    v_reason := NULL;

    INSERT INTO animal(name, species, breed, birth_date, birth_date_is_estimate,
                       status, not_adoptable_reason,
                       health_notes, behavior_notes, general_notes)
    VALUES (
      r.name,
      v_species,
      r.breed,
      CASE WHEN r.age ~ '^[0-9]+$' AND r.in_date IS NOT NULL
           THEN to_date(r.in_date, 'MM/DD/YY') - (r.age || ' years')::interval
           END::date,
      TRUE,
      v_status,
      v_reason,
      -- The Notes column is one free-text field doing four jobs. Splitting it by hand is a
      -- reading, so each row is named rather than pattern-matched: a regex that decided
      -- 'jumps fence' was medical would be a silent error, and there are only twenty notes.
      CASE r.row_no WHEN 2 THEN r.notes    -- Bella: renal food only
                    WHEN 12 THEN r.notes   -- Duke: limps, arthritis, cost so far
                    WHEN 24 THEN r.notes   -- Rosie: senior, dental cost
                    WHEN 33 THEN r.notes   -- Pip: not vetted yet
                    END,
      CASE r.row_no WHEN 6  THEN r.notes   -- Moose: jumps fence
                    WHEN 19 THEN r.notes   -- Tank: good with cats
                    WHEN 23 THEN r.notes   -- Marmalade: hates the carrier
                    WHEN 28 THEN r.notes   -- Willow: escape artist
                    WHEN 32 THEN r.notes   -- Scout: scared of men
                    END,
      CASE WHEN r.row_no IN (2, 6, 12, 19, 23, 24, 28, 32, 33) THEN NULL ELSE r.notes END
    )
    RETURNING id INTO new_id;

    INSERT INTO rhrms_import.animal_link(row_no, animal_id) VALUES (r.row_no, new_id);
  END LOOP;

  -- Row 41 points at the animal row 40 created, which is what makes the two Barley rows
  -- one dog with two applications when anybody queries the provenance.
  INSERT INTO rhrms_import.animal_link(row_no, animal_id)
    SELECT 41, animal_id FROM rhrms_import.animal_link WHERE row_no = 40;
END $$;

-- =====================================================================
-- 2. The stays: when each animal arrived and where from
-- =====================================================================
-- 'born here' maps to BORN_IN_CARE. The other three map straight across. intake_time is
-- left NULL for all 45: the spreadsheet has no time column, and form RH-1's TIME IN box is
-- nullable precisely so a guessed time never gets written down.
INSERT INTO stay(animal_id, intake_date, intake_source, received_by)
SELECT l.animal_id,
       to_date(r.in_date, 'MM/DD/YY'),
       CASE lower(trim(r.where_from))
         WHEN 'owner surrender' THEN 'OWNER_SURRENDER'
         WHEN 'stray'           THEN 'STRAY'
         WHEN 'city shelter'    THEN 'CITY_SHELTER'
         WHEN 'born here'       THEN 'BORN_IN_CARE'
       END,
       current_setting('rhrms.user_id')::bigint
FROM rhrms_import.animal_row r
JOIN rhrms_import.animal_link l ON l.row_no = r.row_no
WHERE r.row_no BETWEEN 2 AND 47 AND r.row_no <> 41;

-- =====================================================================
-- 3. Luna was Princess (AN-2)
-- =====================================================================
-- "came in as Princess, renamed". The rescue's own search has to find her under both, and
-- AN-2 keeps name history for exactly this. The history row is dated to her intake rather
-- than to now(), so the order of the two names is the true one.
INSERT INTO animal_name_history(animal_id, name, valid_from)
SELECT l.animal_id, 'Princess', to_date(r.in_date, 'MM/DD/YY')
FROM rhrms_import.animal_row r
JOIN rhrms_import.animal_link l ON l.row_no = r.row_no
WHERE r.row_no = 5;

-- =====================================================================
-- 4. Olive's litter
-- =====================================================================
-- Olive arrived 2/18/26 as a stray and her note says "had 4 kittens". Four cats are
-- recorded as born here on 4/1/26, and one of them (Pickles) says "mom was Olive". Four
-- kittens, four rows, one named mother: the litter can be reconstructed with confidence.
--
-- It is the ONLY one that can. Nine other animals are marked 'born here' with no mother
-- named anywhere, so their litters are lost.
WITH new_litter AS (
  INSERT INTO litter(mother_animal_id, born_on, note)
  SELECT l.animal_id, DATE '2026-04-01',
         'Reconstructed from the 2026 spreadsheet: Olive''s note says "had 4 kittens" and '
         'four cats are recorded as born here on 4/1/26, one of them noted "mom was Olive".'
  FROM rhrms_import.animal_link l WHERE l.row_no = 8
  RETURNING id
)
UPDATE animal SET litter_id = (SELECT id FROM new_litter), version = version + 1
WHERE id IN (SELECT animal_id FROM rhrms_import.animal_link WHERE row_no IN (7, 9, 10, 11));

-- =====================================================================
-- 5. Bonded pairs
-- =====================================================================
-- Otis's note says "bonded w Pearl - do not separate". Pearl's own row says nothing, which
-- is the whole problem with recording a relationship on one side of it: whoever opens
-- Pearl's record first never learns she has a partner. A bond_group fixes that by being a
-- property of the pair rather than of one animal.
WITH g AS (
  INSERT INTO bond_group(note)
  VALUES ('From the 2026 spreadsheet, on Otis''s row: "bonded w Pearl - do not separate". '
          'Pearl''s own row did not mention it.')
  RETURNING id
)
UPDATE animal SET bond_group_id = (SELECT id FROM g), version = version + 1
WHERE id IN (SELECT animal_id FROM rhrms_import.animal_link WHERE row_no IN (3, 4));

-- The two ferrets: "these two came in together", same intake date, same source. Same
-- one-sided recording, same fix. 'Came in together' is weaker evidence than 'do not
-- separate', so the note says which it is.
WITH g AS (
  INSERT INTO bond_group(note)
  VALUES ('From the 2026 spreadsheet, on Ferret 1''s row: "these two came in together". '
          'Whether they must stay together is not recorded - worth asking the director.')
  RETURNING id
)
UPDATE animal SET bond_group_id = (SELECT id FROM g), version = version + 1
WHERE id IN (SELECT animal_id FROM rhrms_import.animal_link WHERE row_no IN (29, 30));

-- =====================================================================
-- 6. Restrictions hidden in the Notes column
-- =====================================================================
-- Three notes describe a requirement of the home rather than a fact about the animal, and
-- a requirement of the home is what a restriction is - it is what makes a conflict appear
-- in front of the director at decision time instead of being remembered by whoever read
-- the note last.
--
-- This is interpretation, and the risk runs both ways: invent a restriction and a good home
-- gets a warning it does not deserve; miss one and Moose is adopted to a house with a
-- four-foot fence. The wording is kept verbatim in detail so the director can overrule it,
-- and each one is on the list of questions for him.
--
-- Two notes deliberately did NOT become restrictions. Tank's "GOOD with cats!! surprising"
-- is a recommendation, not a limit. Marmalade's "hates the carrier, needs 2 people" is a
-- handling instruction for staff, not a condition on the adopter. Both stay behaviour notes.
INSERT INTO restriction(animal_id, type, detail)
SELECT l.animal_id, 'NEEDS_FENCED_YARD', r.notes
FROM rhrms_import.animal_row r JOIN rhrms_import.animal_link l ON l.row_no = r.row_no
WHERE r.row_no = 6;      -- Moose: "jumps fence. tall fence only"

INSERT INTO restriction(animal_id, type, detail)
SELECT l.animal_id, 'OTHER', r.notes
FROM rhrms_import.animal_row r JOIN rhrms_import.animal_link l ON l.row_no = r.row_no
WHERE r.row_no IN (28, 32);   -- Willow "escape artist. crate at night"; Scout "scared of men"
-- Scout is OTHER rather than NO_ADULT_MEN on purpose. The note says "needs slow intro",
-- which is a condition on how, not a bar on who; NO_ADULT_MEN would refuse half the homes
-- in the county on the strength of six words.

-- =====================================================================
-- 7. The adopters
-- =====================================================================
-- Names are split on the first space, which is the only rule available and is wrong for
-- anyone with two given names. The spreadsheet has one name per cell and no way to tell.
--
-- 'Alvarez' (row 17) is a surname with no first name and no phone. person.first_name is NOT
-- NULL, so the import writes '(not recorded)' - visibly a placeholder, not a guessed name.
--
-- Rows 35 and 36 are 'Jen Kowalczyk' and 'Jennifer Kowalcyzk' on the SAME phone number,
-- and row 36's note reads "second cat for the same family i think". That is almost
-- certainly one person, and "i think" is the volunteer telling us she was not sure. The
-- import does not merge them: deciding that two records are one person is the rescue's
-- call, not the importer's, and merging silently would destroy the evidence that somebody
-- typed the name two ways. They are loaded as two people who share a phone number, which
-- is a state the search in this system surfaces rather than hides - looking up the number
-- returns both, side by side, which is the moment a human can settle it.
INSERT INTO person(first_name, last_name, phone, notes)
SELECT CASE WHEN position(' ' in trim(r.adopter)) > 0
            THEN split_part(trim(r.adopter), ' ', 1) ELSE '(not recorded)' END,
       CASE WHEN position(' ' in trim(r.adopter)) > 0
            THEN substring(trim(r.adopter) from position(' ' in trim(r.adopter)) + 1)
            ELSE trim(r.adopter) END,
       r.phone,
       'Imported from the 2026 spreadsheet, row ' || r.row_no || '.'
       || CASE WHEN r.row_no IN (35, 36)
               THEN ' CAUTION: rows 35 and 36 of that spreadsheet give this same phone '
                    || 'number under two spellings of the name, and row 36 is noted '
                    || '"second cat for the same family i think". These may be one person. '
                    || 'Ask before treating them as two.'
               WHEN r.row_no = 17
               THEN ' The spreadsheet recorded only the surname and no phone number.'
               ELSE '' END
FROM rhrms_import.animal_row r
WHERE r.row_no BETWEEN 2 AND 47 AND r.adopter IS NOT NULL
ORDER BY r.row_no;

-- Link each spreadsheet row to the person it created. The link is read back out of the
-- note the insert above wrote, rather than by lining up two row_number() sequences and
-- hoping: INSERT ... SELECT ... ORDER BY does not promise that RETURNING comes back in that
-- order, and a link table that is quietly off by one would misattribute every adoption.
INSERT INTO rhrms_import.person_link(row_no, person_id)
SELECT (regexp_match(p.notes, 'spreadsheet, row ([0-9]+)\.'))[1]::int, p.id
FROM person p
WHERE p.notes LIKE 'Imported from the 2026 spreadsheet, row %';

-- =====================================================================
-- 8. The applications
-- =====================================================================
-- Fifteen rows have an 'applied' date. Every one starts as SUBMITTED because AP-1 insists
-- on it, and that is correct: an application did begin that way, and the steps after it
-- are what the spreadsheet failed to record.
--
-- Everything the spreadsheet knows about the household - which is nothing - stays NULL.
-- housing_type, has_yard, children_count and the rest are what the home visit and the
-- application form collect, and this file will not pretend to know them.
--
-- situation_check_note carries what the spreadsheet DID say about the visit, which is the
-- word 'yes' or nothing at all, plus the fee, so the original columns are readable from
-- the record without opening the staging table.
INSERT INTO application(person_id, animal_id, submitted_at, received_by, situation_check_note)
SELECT pl.person_id, al.animal_id,
       to_date(r.applied, 'MM/DD/YY'),
       current_setting('rhrms.user_id')::bigint,
       'Imported from the 2026 spreadsheet, row ' || r.row_no || '. '
       || 'Its "home visit" column said: ' || coalesce(r.home_visit, '(blank)') || '. '
       || 'Its "Adopted" column said: '    || coalesce(r.adopted, '(blank)')    || '. '
       || 'Its "$" column said: '          || coalesce(r.fee, '(blank)')        || '.'
FROM rhrms_import.animal_row r
JOIN rhrms_import.person_link pl ON pl.row_no = r.row_no
JOIN rhrms_import.animal_link al ON al.row_no = r.row_no
WHERE r.applied IS NOT NULL
ORDER BY r.row_no;

-- Linked the same way, from the note the insert wrote, for the same reason.
INSERT INTO rhrms_import.application_link(row_no, application_id)
SELECT (regexp_match(a.situation_check_note, 'spreadsheet, row ([0-9]+)\.'))[1]::int, a.id
FROM application a
WHERE a.situation_check_note LIKE 'Imported from the 2026 spreadsheet, row %';

-- =====================================================================
-- 9. The two denials
-- =====================================================================
-- These are the only two process steps in the whole spreadsheet where the substance of the
-- step was written down, so they are the only two that can be loaded as what they were.
-- The rule the import follows: record a decision where the reason exists, and do not
-- manufacture one where it does not.

-- Boomer / Greg Mattingly (row 44): "denied - apartment doesnt take dogs, told him reapply
-- if he moves". A reason, in the director's own words, that maps cleanly onto
-- HOUSING_NO_PETS. The rationale is his sentence, not a summary of it.
INSERT INTO decision(application_id, outcome, denial_reason, rationale, decided_by)
SELECT ap.application_id, 'DENY', 'HOUSING_NO_PETS', r.notes,
       current_setting('rhrms.user_id')::bigint
FROM rhrms_import.animal_row r
JOIN rhrms_import.application_link ap ON ap.row_no = r.row_no
WHERE r.row_no = 44;

-- Barley / Tyler Boothe (row 40): the Notes column says 'DENIED' and nothing else. The
-- denial is a fact and gets recorded; the reason is missing and the rationale says so
-- rather than filling the gap. denial_reason is OTHER because the schema requires one for
-- a denial and 'the reason was not written down' is not on the list - which is itself worth
-- reporting.
INSERT INTO decision(application_id, outcome, denial_reason, rationale, decided_by)
SELECT ap.application_id, 'DENY', 'OTHER',
       'Imported from the 2026 spreadsheet: row 40 recorded only the word "DENIED" and gave '
       'no reason. The reason for this denial is not known. If the applicant asks, or '
       'reapplies, somebody will have to remember.',
       current_setting('rhrms.user_id')::bigint
FROM rhrms_import.application_link ap WHERE ap.row_no = 40;

UPDATE application SET status = 'DENIED', version = version + 1
WHERE id IN (SELECT application_id FROM rhrms_import.application_link WHERE row_no IN (40, 44));

-- =====================================================================
-- 10. The twelve completed adoptions, closed rather than approved
-- =====================================================================
-- This is the wall, and it is worth being exact about where it is.
--
-- To record an adoption this system needs, in order: a home visit with findings (HV-1), a
-- director's decision with a rationale (DE-3), then a placement with a fee and a payment
-- method (PL-1..PL-5). The spreadsheet has a column that says 'yes' or nothing, a date, and
-- a number of dollars. The findings and the rationale were never written down anywhere.
--
-- Four ways out, three of them wrong:
--   - invent the home visits: fabricates client records that look real forever. No.
--   - leave the animals AVAILABLE: says twelve animals in homes are in kennels. No.
--   - mark the animals ADOPTED with no trace of who took them: loses the adopters. No.
--   - record everything the spreadsheet does say, and close the application with the
--     reason it cannot go further.
--
-- So each application is CLOSED - a real status, reachable from SUBMITTED, meaning "this
-- did not run its course in this system" - with closed_reason carrying the adoption date
-- and the fee verbatim. The person, the animal, the application date, the adoption date and
-- the fee all survive and are all queryable. What does not survive is the approval, because
-- there is nothing to approve it with.
UPDATE application a SET status = 'CLOSED', version = version + 1,
  closed_reason =
    'Imported from the 2026 spreadsheet, row ' || r.row_no || '. The adoption went ahead on '
    || r.adopted || ' for $' || coalesce(r.fee, '(blank)') || ', but the home visit findings '
    || 'and the director''s decision were never recorded, so this application cannot be '
    || 'approved in the system and no placement record exists. The animal is marked ADOPTED '
    || 'and the stay is closed. Adoption paperwork for this animal predates RHRMS.'
FROM rhrms_import.animal_row r
JOIN rhrms_import.application_link ap ON ap.row_no = r.row_no
WHERE a.id = ap.application_id AND r.adopted IS NOT NULL;

-- Maisie's application (row 17) has no adoption date to quote, so it gets its own wording.
UPDATE application a SET status = 'CLOSED', version = version + 1,
  closed_reason =
    'Imported from the 2026 spreadsheet, row 17. That row says a home visit happened and '
    'the note says "went home with the Alvarez family", but the Adopted date and the fee '
    'are blank, so whether this adoption completed is not known. The animal is held as not '
    'adoptable until the director confirms. The adopter was recorded only as "Alvarez", '
    'with no first name and no phone number.'
FROM rhrms_import.application_link ap
WHERE a.id = ap.application_id AND ap.row_no = 17;

-- =====================================================================
-- 11. Walking the adopted animals out
-- =====================================================================
-- AN-1 has no AVAILABLE -> ADOPTED edge, on purpose: in normal use an animal reaches
-- ADOPTED only by being approved for somebody and then placed. These twelve were adopted
-- before this system existed, so the import steps them through PENDING_ADOPTION rather
-- than routing them round the rule.
UPDATE animal SET status = 'PENDING_ADOPTION', version = version + 1
WHERE id IN (SELECT al.animal_id FROM rhrms_import.animal_link al
             JOIN rhrms_import.animal_row r ON r.row_no = al.row_no
             WHERE r.adopted IS NOT NULL);

UPDATE animal SET status = 'ADOPTED', kennel_id = NULL, version = version + 1
WHERE id IN (SELECT al.animal_id FROM rhrms_import.animal_link al
             JOIN rhrms_import.animal_row r ON r.row_no = al.row_no
             WHERE r.adopted IS NOT NULL);

-- The stay closes on the date in the Adopted column. Note this preserves a contradiction
-- rather than smoothing it: Rufus (row 37) has applied 4/18/26 and adopted 4/12/26, six
-- days BEFORE the application, with the note "paperwork got away from me on this one". The
-- import loads both dates as written. Nothing in the schema forbids it, because nothing in
-- the schema expected a placement to precede its own application.
UPDATE stay s SET ended_on = to_date(r.adopted, 'MM/DD/YY'), end_reason = 'PLACED'
FROM rhrms_import.animal_row r
JOIN rhrms_import.animal_link al ON al.row_no = r.row_no
WHERE s.animal_id = al.animal_id AND r.adopted IS NOT NULL;

-- Maisie is held out of the adoptable list now that her application exists. See the note
-- in section 1: of the two available lies, "she is available" is the dangerous one, because
-- it offers an animal who may already be in somebody's living room to a second adopter.
UPDATE animal SET status = 'NOT_ADOPTABLE', version = version + 1,
  not_adoptable_reason =
    'Imported from the 2026 spreadsheet: row 17 says a home visit happened and the note '
    'says "went home with the Alvarez family", but the Adopted date and the fee are both '
    'blank. Held back until the director confirms whether she is in a home. Do not offer '
    'her to an adopter until then.'
WHERE id = (SELECT animal_id FROM rhrms_import.animal_link WHERE row_no = 17);

-- =====================================================================
-- 12. Food
-- =====================================================================
-- Four of the five items on the food sheet are food and load cleanly. Bag weights come from
-- the 'size' column where it is filled in (40 lb for the two bulk items) and fall back to
-- the schema default where it is blank. lead_time_days is NOT NULL and is not on the
-- spreadsheet at all, so it comes from the settings table's defaults - a system default,
-- not a fact about this rescue's suppliers, and it should be corrected by somebody who
-- knows how long an order actually takes.
INSERT INTO food_product(name, kind, species, unit, bag_lbs, lead_time_days)
VALUES
  -- The product name is the spreadsheet's, verbatim, including 'RENAL (Bella)' - which has
  -- an animal's name inside a product name. That works exactly until a second dog needs
  -- renal food, and then there is no way to say so. Flagged for the director.
  ('dog food (reg)', 'REGULAR',      'DOG', 'bag', 40,
   (SELECT value::smallint FROM setting WHERE key = 'food.default_lead_days.regular')),
  ('cat food',       'REGULAR',      'CAT', 'bag', 40,
   (SELECT value::smallint FROM setting WHERE key = 'food.default_lead_days.regular')),
  ('kitten food',    'REGULAR',      'CAT', 'bag', 40,
   (SELECT value::smallint FROM setting WHERE key = 'food.default_lead_days.regular')),
  ('RENAL (Bella)',  'PRESCRIPTION', 'DOG', 'bag', 40,
   (SELECT value::smallint FROM setting WHERE key = 'food.default_lead_days.prescription'));

-- Bella's prescription, which FD-7 requires before her renal food can be dispensed to her.
-- It is dated from her intake, because her note has said 'RENAL FOOD ONLY' since the day
-- she arrived. No vet, no end date and no portion were ever written down.
INSERT INTO prescription(food_product_id, animal_id, start_date)
SELECT (SELECT id FROM food_product WHERE name = 'RENAL (Bella)'),
       al.animal_id, to_date(r.in_date, 'MM/DD/YY')
FROM rhrms_import.animal_row r
JOIN rhrms_import.animal_link al ON al.row_no = r.row_no
WHERE r.row_no = 2;

-- The stock count of 2/11/26. 'shelter' and 'garage' are the two stock locations the seed
-- already created, which is a good sign the schema was drawn from this sheet.
--
-- These go in as RECEIVE movements, and that is not quite the truth - nothing was delivered,
-- somebody counted. COUNT_ADJUST is the right type and the schema allows it in either
-- direction, but move_stock() forces every type except RECEIVE and TRANSFER_IN to be
-- negative, so an opening count cannot be recorded as an adjustment. The reason on every
-- movement says what it really was. Reported as a defect.
SELECT move_stock(
         (SELECT id FROM food_product WHERE name = f.item),
         (SELECT id FROM stock_location
           WHERE name = CASE lower(trim(f.where_at)) WHEN 'shelter' THEN 'Shelter'
                                                     WHEN 'garage'  THEN 'Garage overflow' END),
         'RECEIVE', f.how_many::int, current_setting('rhrms.user_id')::bigint,
         'Opening balance from the 2026 spreadsheet food sheet, row ' || f.row_no
         || ', which recorded this count as last checked ' || coalesce(f.last_checked, '(blank)')
         || '. Recorded as a receipt because move_stock cannot record an upward count '
         || 'adjustment; no delivery took place.')
FROM rhrms_import.food_row f
WHERE f.row_no BETWEEN 2 AND 7 AND f.how_many ~ '^[0-9]+$'
ORDER BY f.row_no;

-- =====================================================================
-- 13. Donations
-- =====================================================================
-- Nine donations. Two are in kind ('bags of food', 'litter + food') and have no amount, so
-- they are kind FOOD with the description verbatim and amount NULL - which is honest, and
-- which is also why the spreadsheet's own total cannot be checked against them.
INSERT INTO donation(received_on, donor_name, kind, amount, description, recorded_by)
SELECT to_date(d.date_text, 'MM/DD/YY'),
       d.who,
       CASE WHEN d.amount ~ '^[0-9.]+$' THEN 'MONEY' ELSE 'FOOD' END,
       CASE WHEN d.amount ~ '^[0-9.]+$' THEN d.amount::numeric END,
       d.what,
       current_setting('rhrms.user_id')::bigint
FROM rhrms_import.donation_row d
WHERE d.row_no BETWEEN 2 AND 10
  AND d.date_text IS NOT NULL
ORDER BY d.row_no;

-- =====================================================================
-- 14. What was decided, in prose
-- =====================================================================
INSERT INTO rhrms_import.decision_log(row_no, subject, finding, action, ask_director) VALUES
 (NULL, 'Twelve completed adoptions',
  'Rows 34-47 record adoptions with an adopter, a date and a fee, but no home visit findings and no decision rationale.',
  'Animals marked ADOPTED and stays closed. Applications CLOSED, not APPROVED, with the adoption date and fee in closed_reason. No placement records, so the fees are not in the books.', TRUE),
 (17, 'Maisie',
  'Home visit "yes", note "went home with the Alvarez family", but Adopted and $ both blank.',
  'Held as NOT_ADOPTABLE so she cannot be offered to a second adopter. Application CLOSED.', TRUE),
 (17, 'The Alvarez adopter',
  'Adopter recorded as "Alvarez" only: no first name, no phone.',
  'Person created with first_name "(not recorded)". No phone.', TRUE),
 (13, 'Cooper and Copper',
  'Two rows, one letter apart, same species, same breed spelled two ways, same age, same intake date 4/26/26, same source. One has "scar on muzzle", the other has no note.',
  'Both loaded as separate animals. Not merged: if they are one dog this is a duplicate row, and if they are two dogs merging them would lose one. The import cannot tell.', TRUE),
 (35, 'Jen Kowalczyk / Jennifer Kowalcyzk',
  'Rows 35 and 36: two spellings of the name on the same phone number, 720-555-0198. Row 36 notes "second cat for the same family i think".',
  'Loaded as two people sharing a phone number, each with a caution in their notes. Searching the number returns both together.', TRUE),
 (37, 'Rufus',
  'Applied 4/18/26, adopted 4/12/26 - the adoption is dated six days before the application. Note: "paperwork got away from me on this one".',
  'Both dates loaded as written. The contradiction is preserved rather than corrected.', TRUE),
 (40, 'Barley denied then adopted',
  'Rows 40 and 41 are the same dog: same name, breed, age, intake date and source, with two different adopters. Row 40 was denied, row 41 adopted 5/28/26.',
  'One animal, two applications. Row 40 denied with reason OTHER because the spreadsheet recorded only the word "DENIED".', FALSE),
 (40, 'A denial with no reason',
  'Row 40 records a denial with no reason of any kind.',
  'Decision recorded with denial_reason OTHER and a rationale stating that the reason is unknown.', TRUE),
 (44, 'Boomer',
  'Denied with a real reason: "apartment doesnt take dogs, told him reapply if he moves".',
  'The only decision in the file that could be loaded as a proper decision. HOUSING_NO_PETS, rationale verbatim.', FALSE),
 (NULL, 'Nobody has a sex',
  'The spreadsheet has no sex column.',
  'sex left NULL for all 45 animals. It will have to be collected from the animals themselves.', FALSE),
 (NULL, 'Nobody has been vetted',
  'Vetting is not recorded anywhere. One row says "not vetted yet"; the rest say nothing.',
  'All 45 animals are NOT_VETTED. PL-3 will refuse every adoption until each is vetted or the director overrides, which is correct but will bite on day one.', TRUE),
 (NULL, 'No kennel numbers',
  'The spreadsheet has no location column, so nobody knows which animal is in which kennel.',
  'kennel_id NULL for all 45. The dashboard kennel grid will be empty until somebody walks the building.', TRUE),
 (NULL, 'Ages are whole years with no date',
  'Age is a whole number of years, written at intake, with no birth dates.',
  'birth_date derived as intake date minus that many years, flagged as an estimate.', FALSE),
 (NULL, 'Litters are mostly lost',
  'Ten animals are marked "born here". Only Olive''s four kittens can be tied to a mother.',
  'One litter reconstructed. The other six born-here animals have no litter and no mother.', TRUE),
 (6, 'Restrictions buried in notes',
  'Moose "jumps fence. tall fence only"; Willow "escape artist. crate at night"; Scout "scared of men, needs slow intro".',
  'Three restrictions created with the wording verbatim, so they raise conflicts at decision time. This is the import reading intent and needs confirming.', TRUE),
 (2, 'RENAL (Bella)',
  'The food sheet names a product after the animal that eats it.',
  'Loaded verbatim with a prescription linking it to Bella. A second dog needing renal food has nowhere to go.', TRUE),
 (8, 'Cat litter is not food',
  'The food sheet tracks litter, quantity "?", alongside the food. The donations sheet records "litter + food".',
  'NOT LOADED. The schema holds food products with bag weights and cups per meal; litter has no home in it.', TRUE),
 (NULL, 'No portions anywhere',
  'The spreadsheet records no cups per meal or meals per day for any animal.',
  'No diet records created. The inventory forecast cannot work out what is needed until somebody enters portions.', TRUE),
 (6, 'The food count is seven months old',
  'Every row of the food sheet says last checked 2/11/26.',
  'Loaded as an opening balance with that date in the reason. Treat the numbers as unverified.', TRUE),
 (6, 'Bella''s renal food contradicts itself',
  'The food sheet says 2 bags at the shelter, checked 2/11/26. Bella''s own note says "1 bag left in garage as of aug".',
  'The food sheet figure was loaded. Bella''s note is in her health notes. They disagree about the count and the location.', TRUE),
 (6, 'ORDER MORE',
  'The RENAL row has "ORDER MORE" in a column with no header, with no quantity and no date.',
  'No food order created: the number of bags to order is not recorded and inventing one would put a fictional order on the dashboard.', TRUE),
 (9, 'Non-data rows',
  'Food sheet row 9 is blank and row 10 reads "Diane said she would keep this up". Donations row 11 is blank and row 12 is a typed total.',
  'Kept verbatim in the staging tables and not loaded as records.', FALSE),
 (12, 'The donations total is wrong',
  'Donations row 12 reads 1025. The nine cash donations above it add up to 1075.',
  'The nine donations were loaded individually. The typed total was not, and is out by 50.', TRUE),
 (NULL, 'Adoption fees are not in the books',
  'Twelve fees are recorded: nine at 250, one at 175 with no explanation, one at 50 "senior discount", one at 0 "volunteer adopted her".',
  'Fees are preserved as text on each closed application but no placement rows exist, so they are not money the system can total. The 175 has no reason, which PL-2 would refuse outright.', TRUE),
 (NULL, 'Four spellings of one breed',
  '"Shepherd mix" (3 animals), "German Shepard mix" (2), "GSD mix" (1); also "Pit mix" and "pit mix".',
  'All loaded verbatim. Searching "shep" finds five of the six but not "GSD mix", and a report grouped by breed shows three breeds where there is one. Worth a decision on whether to standardise going forward.', TRUE);

-- Mark the import as done, which is also what stops it running twice.
INSERT INTO setting(key, value)
VALUES ('import.master_spreadsheet',
        'Documents/MASTER copy FINAL (2).xlsx imported ' || now()::date);

COMMIT;
