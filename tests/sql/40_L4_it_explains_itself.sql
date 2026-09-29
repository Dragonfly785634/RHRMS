-- L4  It explains itself: who, when, what changed, and why.
BEGIN;
SET LOCAL search_path = rhrms, rhrms_test;

-- the sample data was written with users set; check a few of those entries
SELECT ok((SELECT count(*) FROM audit_log WHERE table_name = 'decision' AND user_id = 1) = 2,
          'AU-1: both decisions on Bella are logged under Mike');
SELECT ok(EXISTS (SELECT 1 FROM audit_log WHERE table_name = 'home_visit'
                  AND on_behalf_of = 'Casey (volunteer, no login)' AND user_id = 3),
          'HV-2: a visit typed in for a volunteer records both people');
SELECT ok(EXISTS (SELECT 1 FROM audit_log WHERE table_name = 'animal' AND action = 'UPDATE'
                  AND old_row->>'name' = 'Princess' AND new_row->>'name' = 'Luna'
                  AND reason = 'Renamed: new name suits her better' AND user_id = 2),
          'AU-1: the rename shows old name, new name, who and why');

-- a fresh change by the app
SET LOCAL rhrms.user_id = '2';
SET LOCAL rhrms.reason  = 'Vet estimate: 6 years, not 5';
UPDATE animal SET birth_date = CURRENT_DATE - INTERVAL '6 years', version = version + 1 WHERE name = 'Bella';
SELECT ok(EXISTS (SELECT 1 FROM audit_log
                  WHERE table_name = 'animal' AND row_id = animal_id('Bella')::text
                    AND action = 'UPDATE' AND user_id = 2 AND reason = 'Vet estimate: 6 years, not 5'
                    AND old_row->>'birth_date' <> new_row->>'birth_date'),
          'AU-1: a correction keeps the old value, the new value, the user and the reason');

-- the decision record the director would pull up after a complaint
SELECT ok((SELECT denial_reason = 'CHILDREN_UNSUITABLE' AND decided_by = 'Mike (Director)'
                  AND visitor_name = 'Sam' AND conflicts_snapshot LIKE 'Children in home%'
           FROM decision_record WHERE applicant = 'Alex Morgan'),
          'LK-2: the Morgan denial shows reason, decider, visitor and the conflict he saw');

-- things that must be impossible
SELECT expect_error('AU-2: records cannot be deleted',
  $$ DELETE FROM person WHERE last_name = 'Morgan' $$, NULL, '42501');
SELECT expect_error('AU-2: the app cannot write to the audit log directly',
  $$ INSERT INTO audit_log(action, table_name) VALUES ('FAKE', 'animal') $$, NULL, '42501');
SELECT expect_error('AU-2: the app cannot edit the audit log',
  $$ UPDATE audit_log SET reason = 'covered my tracks' $$, NULL, '42501');
SELECT expect_error('AU-2: the app cannot erase the audit log',
  $$ DELETE FROM audit_log $$, NULL, '42501');
SELECT expect_error('L4: a decision cannot be edited after the fact (record an override instead)',
  $$ UPDATE decision SET rationale = 'Rewritten later to look better' WHERE outcome = 'DENY' $$, 'RULE L4');
SELECT expect_error('L4: home-visit findings cannot be edited after the fact',
  $$ UPDATE home_visit SET children_present = FALSE $$, 'RULE L4');
SELECT expect_error('L4: the stock ledger cannot be edited',
  $$ UPDATE stock_movement SET bags = 5 $$, 'RULE L4');

-- a change with no user attached is refused
SET LOCAL rhrms.user_id = '';
SELECT expect_error('L4: a change with no user set is refused',
  $$ UPDATE animal SET general_notes = 'anonymous edit' WHERE name = 'Otis' $$, 'RULE L4');

-- override: a new decision supersedes, both remain
SET LOCAL rhrms.user_id = '1';
INSERT INTO decision(application_id, outcome, rationale, supersedes_decision_id, decided_by, conflicts_snapshot,
                     conflicts_acknowledgement)
SELECT application_id, 'APPROVE', 'Family moved; child now lives with other parent full time.',
       id, 1, 'Children in home (youngest 4)', 'Child no longer lives in the home as of this month.'
FROM decision WHERE outcome = 'DENY';
SELECT ok((SELECT count(*) FROM decision_record WHERE applicant = 'Alex Morgan') = 2,
          'DE-6: an override adds a new decision; the original denial is still visible');

ROLLBACK;
