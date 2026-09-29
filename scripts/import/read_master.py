#!/usr/bin/env python3
"""
Reads Documents/MASTER copy FINAL (2).xlsx and writes the three sheets, VERBATIM, as SQL
INSERTs into a staging schema.

Two rules govern this script:

  1. It never writes to the spreadsheet. It opens the file read-only ('rb' inside zipfile)
     and the only file it creates is the .sql output. The README that came with the
     spreadsheet says the copy is canonical and read-only, so the import reads it the way
     you would read a photograph of a paper ledger.

  2. It does not interpret anything. Every cell arrives in the database as the text that is
     in the cell, including '?', 'yes', 'DENIED', blank columns and the row that says
     "Diane said she would keep this up". Interpretation happens in
     sql/92_import_master.sql, where it can be read and argued with. Keeping the two apart
     means anybody can compare what the rescue wrote to what the system decided it meant.

Standard library only (no openpyxl): an .xlsx is a zip of XML, and a dependency for one
throwaway read would have to be justified under the spec's rule on libraries.
"""

import re
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'

# The sheets, in workbook order, with the staging table each one lands in and how many
# columns that table has. Trailing cells beyond the count are kept in an "extra" column,
# because two rows of the food sheet carry the word "pallet" in a column with no header.
SHEETS = [
    ('Sheet1',    'xl/worksheets/sheet1.xml', 'animal_row',   13),
    ('food',      'xl/worksheets/sheet2.xml', 'food_row',      5),
    ('donations', 'xl/worksheets/sheet3.xml', 'donation_row',  4),
]


def column_of(ref):
    """'C7' -> 2. Cells are addressed by letter, and skipped cells are simply absent."""
    letters = re.match(r'([A-Z]+)', ref).group(1)
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n - 1


def shared_strings(z):
    if 'xl/sharedStrings.xml' not in z.namelist():
        return []
    root = ET.fromstring(z.read('xl/sharedStrings.xml'))
    return [''.join(t.text or '' for t in si.iter(NS + 't')) for si in root.findall(NS + 'si')]


def rows_of(z, sheet_path, shared):
    """Every row as a list of strings, padded to the widest cell actually present."""
    root = ET.fromstring(z.read(sheet_path))
    out = []
    for row in root.iter(NS + 'row'):
        cells = {}
        for c in row.findall(NS + 'c'):
            kind = c.get('t')
            v = c.find(NS + 'v')
            inline = c.find(NS + 'is')
            if kind == 'inlineStr' and inline is not None:
                text = ''.join(t.text or '' for t in inline.iter(NS + 't'))
            elif kind == 's' and v is not None:
                text = shared[int(v.text)]
            elif v is not None:
                text = v.text or ''
            else:
                text = ''
            cells[column_of(c.get('r'))] = text
        width = max(cells) + 1 if cells else 0
        out.append([cells.get(i, '') for i in range(width)])
    return out


def sql_text(s):
    """A SQL literal, or NULL for a cell that is empty or only whitespace."""
    if s is None or s.strip() == '':
        return 'NULL'
    return "'" + s.replace("'", "''") + "'"


def main():
    if len(sys.argv) != 3:
        sys.exit('usage: read_master.py <MASTER copy FINAL (2).xlsx> <out.sql>')
    source, out_path = sys.argv[1], sys.argv[2]

    z = zipfile.ZipFile(source, 'r')          # read-only; nothing is written back
    shared = shared_strings(z)

    lines = [
        '-- ===================================================================',
        '-- GENERATED FILE - do not edit by hand.',
        '-- Written by scripts/import/read_master.py from',
        '--   ' + source,
        '--',
        '-- The rescue spreadsheet, transcribed cell for cell. No cleaning, no',
        '-- renaming, no dropped rows. sql/92_import_master.sql reads these tables',
        '-- and decides what they mean; this file only says what they SAY.',
        '-- ===================================================================',
        'SET search_path = rhrms_import;',
        '',
    ]

    for name, path, table, width in SHEETS:
        rows = rows_of(z, path, shared)
        lines.append('-- ---- sheet "%s" -> %s (%d rows including the header) ----'
                     % (name, table, len(rows)))
        lines.append('TRUNCATE %s;' % table)
        for number, row in enumerate(rows, start=1):
            padded = list(row) + [''] * max(0, width - len(row))
            fixed = [sql_text(c) for c in padded[:width]]
            # Anything past the declared width is a cell in a column with no header.
            extra = ' | '.join(c for c in padded[width:] if c.strip())
            lines.append('INSERT INTO %s VALUES (%d, %s, %s);'
                         % (table, number, ', '.join(fixed), sql_text(extra)))
        lines.append('')

    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')

    print('read %s' % source)
    for name, path, table, width in SHEETS:
        print('  sheet %-10s -> %s (%d rows)' % (name, table, len(rows_of(z, path, shared))))
    print('wrote %s' % out_path)


if __name__ == '__main__':
    main()
