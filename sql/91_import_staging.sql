-- =====================================================================
-- RHRMS · 91_import_staging.sql
-- A home for the rescue's spreadsheet, kept exactly as it was written.
--
-- The file this holds is "MASTER copy FINAL (2).xlsx", the sheet four people
-- edited at once for a year. Its README says, in short: it is a mess, do not
-- tidy it, the mess is the evidence. That is a sound instruction and it does
-- not conflict with loading the data - it just means the original has to
-- survive the loading.
--
-- So the import happens in two halves. This schema is the first: three tables
-- whose columns are all TEXT and whose contents are the cells, verbatim,
-- including 'yes', '?', 'DENIED', the blank rows, the stray 'pallet' in a
-- column with no header, and the row that reads "Diane said she would keep
-- this up". Nothing is corrected here and nothing is dropped.
--
-- sql/92_import_master.sql is the second half: it reads these tables and
-- decides what they mean. Keeping the two apart means any argument about the
-- import is an argument about 92, and can be settled by querying 91.
--
-- These tables are deliberately OUTSIDE the rhrms schema. They are not rescue
-- data - they are a photograph of a document - so they get no audit triggers,
-- no rules and no place in the dashboard.
-- =====================================================================

DROP SCHEMA IF EXISTS rhrms_import CASCADE;
CREATE SCHEMA rhrms_import;
SET search_path = rhrms_import;

-- Sheet1: the animals. Column names are the spreadsheet's own headers, lower-cased,
-- with the header row kept as row 1 so the transcription can be checked against the file.
CREATE TABLE animal_row (
  row_no      INT PRIMARY KEY,   -- the spreadsheet row number, so any row can be found again
  name        TEXT,
  type        TEXT,              -- dog / cat / other
  breed       TEXT,
  age         TEXT,              -- whole years, as text: it is not always a number
  in_date     TEXT,              -- header is "In"
  where_from  TEXT,
  adopter     TEXT,
  phone       TEXT,
  applied     TEXT,
  home_visit  TEXT,              -- 'yes' or blank. Never a date, never a finding.
  adopted     TEXT,
  fee         TEXT,              -- header is "$"
  notes       TEXT,
  extra       TEXT               -- anything past column N, which has no header
);

-- The food sheet. "size" is a bag weight for two products and blank for the rest;
-- "how many" contains '?' for litter; the sixth column has no header and says 'pallet'.
CREATE TABLE food_row (
  row_no       INT PRIMARY KEY,
  item         TEXT,
  size         TEXT,
  how_many     TEXT,
  where_at     TEXT,             -- header is "where": shelter / garage
  last_checked TEXT,
  extra        TEXT              -- the unheaded column: 'pallet', 'ORDER MORE'
);

-- The donations sheet, including its blank row and its hand-typed total.
CREATE TABLE donation_row (
  row_no    INT PRIMARY KEY,
  date_text TEXT,
  who       TEXT,
  what      TEXT,
  amount    TEXT,
  extra     TEXT
);

-- ---------------------------------------------------------------------
-- Provenance. After 92 runs, these say which spreadsheet row became which
-- record - the answer to "where did this animal come from?" and to
-- "why are there two Barley rows but one Barley?".
-- ---------------------------------------------------------------------
CREATE TABLE animal_link      (row_no INT PRIMARY KEY, animal_id      BIGINT NOT NULL);
CREATE TABLE person_link      (row_no INT PRIMARY KEY, person_id      BIGINT NOT NULL);
CREATE TABLE application_link (row_no INT PRIMARY KEY, application_id BIGINT NOT NULL);

-- Every judgement call 92 makes, recorded as it is made, so the import can be
-- read back as prose instead of re-derived from the SQL.
CREATE TABLE decision_log (
  id       BIGSERIAL PRIMARY KEY,
  row_no   INT,                  -- the spreadsheet row it concerns, or NULL for a whole-sheet call
  subject  TEXT NOT NULL,        -- what it is about, e.g. 'Cooper / Copper'
  finding  TEXT NOT NULL,        -- what the spreadsheet says
  action   TEXT NOT NULL,        -- what the import did about it
  ask_director BOOLEAN NOT NULL DEFAULT FALSE   -- does this need a human answer?
);

GRANT USAGE ON SCHEMA rhrms_import TO rhrms_app, rhrms_readonly,
  rhrms_volunteer, rhrms_staff, rhrms_director;
GRANT SELECT ON ALL TABLES IN SCHEMA rhrms_import TO rhrms_app, rhrms_readonly,
  rhrms_volunteer, rhrms_staff, rhrms_director;
