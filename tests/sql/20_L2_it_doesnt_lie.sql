-- L2  It doesn't lie: every bad input is refused with a clear message.
BEGIN;
SET LOCAL search_path = rhrms, rhrms_test;
SET LOCAL rhrms.user_id = '2';

-- ---------- food ----------
SELECT expect_error('FD-5: cannot open 2 bags when only 1 is on hand',
  $$ SELECT move_stock(3, 1::SMALLINT, 'OPEN', 2, 2) $$, 'RULE FD-5');
SELECT expect_error('L2: stock can never be set below zero, even by direct UPDATE',
  $$ UPDATE stock_level SET sealed_bags = -1 WHERE food_product_id = 3 $$, NULL, '23514');
SELECT expect_error('FD-5: zero bags is refused',
  $$ SELECT move_stock(1, 1::SMALLINT, 'RECEIVE', 0, 2) $$, 'RULE FD-5');
SELECT expect_error('FD-8: a write-off needs a reason',
  $$ SELECT move_stock(1, 1::SMALLINT, 'WRITE_OFF', 1, 2) $$, NULL, '23514');
SELECT expect_error('FD-7: Otis cannot be put on Bella''s prescription food',
  $$ INSERT INTO diet(animal_id, food_product_id, cups_per_meal) VALUES (animal_id('Otis'), 3, 1) $$, 'RULE FD-7');
SELECT expect_error('FD-7: prescription food cannot be sent home with the wrong animal',
  $$ SELECT move_stock(3, 1::SMALLINT, 'SEND_WITH_ANIMAL', 1, 2, NULL, animal_id('Luna')) $$, 'RULE FD-7');

-- ---------- animals and kennels ----------
SELECT expect_error('IN-2: Luna cannot move into Bella''s kennel (not bonded)',
  $$ UPDATE animal SET kennel_id = 1 WHERE name = 'Luna' $$, 'RULE IN-2');
UPDATE kennel SET in_service = FALSE, out_of_service_reason = 'Broken latch' WHERE id = 39;
SELECT expect_error('IN-2: an out-of-service kennel cannot be used',
  $$ INSERT INTO animal(name, species, kennel_id) VALUES ('Scout', 'DOG', 39) $$, 'RULE IN-2');
SELECT expect_error('AN-1: NOT_ADOPTABLE needs a reason',
  $$ UPDATE animal SET status = 'NOT_ADOPTABLE' WHERE name = 'Luna' $$, NULL, '23514');
UPDATE animal SET status = 'DECEASED', kennel_id = NULL WHERE name = 'Luna';
SELECT expect_error('AN-1: a deceased animal cannot become available',
  $$ UPDATE animal SET status = 'AVAILABLE' WHERE name = 'Luna' $$, 'RULE AN-1');
SELECT expect_error('Schema: an adopted animal cannot still hold a kennel',
  $$ UPDATE animal SET status = 'ADOPTED' WHERE name = 'Bella' $$, NULL, '23514');
SELECT expect_error('Schema: species must be one of the known values',
  $$ INSERT INTO animal(name, species) VALUES ('Spike', 'IGUANA') $$, NULL, '23514');
SELECT expect_error('Schema: phone numbers cannot contain letters',
  $$ INSERT INTO person(first_name, last_name, phone) VALUES ('Pat', 'Kim', '970-CALL-NOW') $$, NULL, '23514');

-- ---------- applications ----------
SELECT expect_error('AP-1: cannot apply for Maple (not adoptable yet)',
  $$ INSERT INTO application(person_id, animal_id, received_by)
     VALUES ((SELECT id FROM person WHERE last_name = 'Lee'), animal_id('Maple'), 4) $$, 'RULE AP-1');
SELECT expect_error('AP-3: the same person cannot have two open applications for Bella',
  $$ INSERT INTO application(person_id, animal_id, received_by)
     VALUES ((SELECT id FROM person WHERE last_name = 'Lee'), animal_id('Bella'), 4) $$, NULL, '23505');
SELECT expect_error('AP-STATE: a denied application cannot be placed',
  $$ UPDATE application SET status = 'PLACED' WHERE id = app_of('Morgan', 'Bella') $$, 'RULE AP-STATE');

-- HV-1: approval without a home visit
INSERT INTO person(first_name, last_name) VALUES ('Pat', 'Kim');
INSERT INTO application(person_id, animal_id, received_by)
  VALUES ((SELECT id FROM person WHERE last_name = 'Kim'), animal_id('Otis'), 4);
UPDATE application SET status = 'UNDER_REVIEW' WHERE id = app_of('Kim', 'Otis');
SELECT expect_error('HV-1: no approval without a home visit',
  $$ UPDATE application SET status = 'APPROVED' WHERE id = app_of('Kim', 'Otis') $$, 'RULE HV-1');

-- ---------- decisions ----------
SELECT expect_error('DE-4: a denial must have a reason category',
  $$ INSERT INTO decision(application_id, outcome, rationale, decided_by)
     VALUES (app_of('Kim', 'Otis'), 'DENY', 'Not a good match for this dog.', 1) $$, NULL, '23514');
SELECT expect_error('DE-4: a rationale of a few words is not enough',
  $$ INSERT INTO decision(application_id, outcome, denial_reason, rationale, decided_by)
     VALUES (app_of('Kim', 'Otis'), 'DENY', 'OTHER', 'no', 1) $$, NULL, '23514');
SELECT expect_error('DE-3: approving despite a restriction conflict needs a written acknowledgement',
  $$ INSERT INTO decision(application_id, outcome, rationale, conflicts_snapshot, decided_by)
     VALUES (app_of('Morgan', 'Bella'), 'APPROVE', 'Family seems very capable overall.',
             'Children in home (youngest 4)', 1) $$, NULL, '23514');
SELECT expect_error('DE-1: a volunteer cannot decide an application',
  $$ INSERT INTO decision(application_id, outcome, denial_reason, rationale, decided_by)
     VALUES (app_of('Kim', 'Otis'), 'DENY', 'OTHER', 'Volunteer trying to decide alone.', 4) $$, 'RULE DE-1');
SELECT expect_error('DE-1: staff cannot decide either (director makes the final call)',
  $$ INSERT INTO decision(application_id, outcome, denial_reason, rationale, decided_by)
     VALUES (app_of('Kim', 'Otis'), 'DENY', 'OTHER', 'Staff member trying to decide.', 2) $$, 'RULE DE-1');

-- ---------- placement ----------
SELECT expect_error('PL-1: cannot record an adoption for a denied application',
  $$ SELECT place_animal(app_of('Morgan', 'Bella'), 2, 250, 'CASH') $$, 'RULE PL-1');
SELECT expect_error('PL-2: a fee other than $250 needs a reason',
  $$ SELECT place_animal(app_of('Lee', 'Bella'), 2, 150, 'CASH') $$, NULL, '23514');
SELECT expect_error('PL-2: a waived fee must be $0',
  $$ SELECT place_animal(app_of('Lee', 'Bella'), 2, 100, 'WAIVED', 'Student discount') $$, NULL, '23514');
SELECT expect_error('IN-4: cannot return an animal that was never adopted',
  $$ SELECT return_animal(animal_id('Otis'), 2, 'Test', 2::SMALLINT) $$, 'RULE IN-4');

-- After a failed place_animal, nothing half-done remains (IN-4 all-or-nothing)
SELECT ok((SELECT status FROM animal WHERE name = 'Bella') = 'PENDING_ADOPTION'
          AND NOT EXISTS (SELECT 1 FROM placement WHERE animal_id = animal_id('Bella')),
          'IN-4: failed adoption attempts left no partial changes');

ROLLBACK;
