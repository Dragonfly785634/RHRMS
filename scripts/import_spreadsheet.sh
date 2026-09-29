#!/usr/bin/env bash
# Loads Documents/MASTER copy FINAL (2).xlsx into the database.
#
# Three steps, deliberately separate so each can be checked on its own:
#   1. read_master.py transcribes the spreadsheet into SQL. Read-only on the .xlsx.
#   2. 91_import_staging.sql makes the staging schema; the transcription loads into it.
#   3. 92_import_master.sql decides what the rows mean and writes rescue data.
#
# The spreadsheet is never written to. This script checks its checksum before and after and
# fails if it changed, because "don't touch the spreadsheet" is worth enforcing rather than
# promising.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
source "$HERE/config.sh"

SOURCE="${RHRMS_SPREADSHEET:-$ROOT/Documents/MASTER copy FINAL (2).xlsx}"
GENERATED="$HERE/import/master_verbatim.sql"

if [[ ! -f "$SOURCE" ]]; then
  echo "Cannot find the spreadsheet: $SOURCE" >&2
  echo "Set RHRMS_SPREADSHEET to point at it." >&2
  exit 1
fi

before="$(md5sum "$SOURCE" | cut -d' ' -f1)"

echo "== 1/3  reading the spreadsheet (read-only) =="
python3 "$HERE/import/read_master.py" "$SOURCE" "$GENERATED"

after="$(md5sum "$SOURCE" | cut -d' ' -f1)"
if [[ "$before" != "$after" ]]; then
  echo "REFUSING TO CONTINUE: the spreadsheet changed while being read." >&2
  echo "  was $before" >&2
  echo "  now $after" >&2
  exit 1
fi
echo "   spreadsheet unchanged (md5 $after)"

# Ask before rebuilding the staging schema, because rebuilding it drops the provenance and
# decision tables from the last run. Doing this the other way round meant a SECOND, correctly
# refused import still destroyed the record of what the first one decided - a refusal that did
# damage on its way out.
if as_owner -tAc "SELECT 1 FROM rhrms.setting WHERE key = 'import.master_spreadsheet'" \
     2>/dev/null | grep -q 1; then
  echo "This spreadsheet has already been imported:" >&2
  as_owner -tAc "SELECT '  ' || value FROM rhrms.setting WHERE key='import.master_spreadsheet'" >&2
  echo "Nothing was changed. Rebuild the database with scripts/build_db.sh to import again." >&2
  exit 1
fi

echo "== 2/3  loading the transcription into the staging schema =="
as_owner -v ON_ERROR_STOP=1 -q -f "$ROOT/sql/91_import_staging.sql"
as_owner -v ON_ERROR_STOP=1 -q -f "$GENERATED"

echo "== 3/3  loading it as rescue data =="
as_owner -v ON_ERROR_STOP=1 -q -f "$ROOT/sql/92_import_master.sql"

final="$(md5sum "$SOURCE" | cut -d' ' -f1)"
if [[ "$before" != "$final" ]]; then
  echo "WARNING: the spreadsheet changed during the import. It should not have." >&2
  exit 1
fi

echo
echo "== what is now in the database =="
as_owner -q -c "
SET search_path = rhrms;
SELECT 'animals'            AS record, count(*)::text AS n FROM animal
UNION ALL SELECT '  ..available',    count(*)::text FROM animal WHERE status = 'AVAILABLE'
UNION ALL SELECT '  ..adopted',     count(*)::text FROM animal WHERE status = 'ADOPTED'
UNION ALL SELECT '  ..held back',   count(*)::text FROM animal WHERE status = 'NOT_ADOPTABLE'
UNION ALL SELECT 'stays',           count(*)::text FROM stay
UNION ALL SELECT 'people',          count(*)::text FROM person
UNION ALL SELECT 'applications',    count(*)::text FROM application
UNION ALL SELECT 'decisions',       count(*)::text FROM decision
UNION ALL SELECT 'restrictions',    count(*)::text FROM restriction
UNION ALL SELECT 'bond groups',     count(*)::text FROM bond_group
UNION ALL SELECT 'litters',         count(*)::text FROM litter
UNION ALL SELECT 'food products',   count(*)::text FROM food_product
UNION ALL SELECT 'bags on hand',    coalesce(sum(sealed_bags),0)::text FROM stock_level
UNION ALL SELECT 'donations',       count(*)::text FROM donation
UNION ALL SELECT 'audit rows',      count(*)::text FROM audit_log;"

echo
echo "== questions for the director ($(as_owner -tAc "SELECT count(*) FROM rhrms_import.decision_log WHERE ask_director") of them) =="
as_owner -q -c "SELECT coalesce('row ' || row_no, '-') AS sheet, subject
                FROM rhrms_import.decision_log WHERE ask_director ORDER BY id;"
echo
echo "Full reasoning:  docs/SPREADSHEET_FINDINGS.md"
echo "Every call made: SELECT * FROM rhrms_import.decision_log;"
echo "The original:    SELECT * FROM rhrms_import.animal_row;   (verbatim, nothing cleaned)"
