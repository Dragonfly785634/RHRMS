-- =====================================================================
-- RHRMS  Stage 1 · 05_sample_data.sql   (DEVELOPMENT ONLY)
-- A small, realistic data set built from the interview stories:
--   * Bella: reactive to children, eats prescription food, two applicants
--   * Otis and Pearl: a bonded pair sharing kennel 2
--   * Luna: renamed from Princess
--   * Maple and her litter of three kittens (not vetted yet)
-- Every section sets rhrms.user_id so the audit log shows who did what.
-- =====================================================================
SET search_path = rhrms;

-- ---------- staff (bootstrap: first accounts, created by the installer) ----------
-- Real BCrypt hashes (cost 12), so the terminal login in Stage 2 actually works against this
-- sample data. The plaintext passwords are listed in docs/ROLES_AND_PASSWORDS.md and are for
-- DEVELOPMENT ONLY: this file is never loaded on the front-desk computer, which starts empty
-- and gets its director account from the first-run screen. Generated with:
--     java -cp app/target/rhrms.jar:app/target/lib/* org.rubyhill.rhrms.tool.HashTool
INSERT INTO staff_user(username, display_name, role, password_hash) VALUES
  ('mike',   'Mike (Director)',  'DIRECTOR',  '$2a$12$7kkDkrfonLljWWsEbiy.uuX2YHLgkksR9GeNoEzMfb5JjnOPN9BIa'),  -- Director#2026
  ('diane',  'Diane',            'STAFF',     '$2a$12$JWqjcLXa.deSRNryX0zBYeizU/jCN4UZWk2N4ukoNtkkOrODnZLfy'),  -- Staff#Diane26
  ('jordan', 'Jordan',           'STAFF',     '$2a$12$/KF02MpQprRwI.DkK8qPDuHkgZBAuOrV2WhsDXsDo8ev/n7acW0Mu'),  -- Staff#Jordan26
  ('sam',    'Sam (volunteer)',  'VOLUNTEER', '$2a$12$f50gtd4vobNo1NKqWGvaEezk2QrfHrMI7AUvmwwm4oBQt43ATtH9K');  -- Volunteer#Sam26

-- ---------- food products (Diane) ----------
SET rhrms.user_id = '2';
SET rhrms.reason  = 'Initial product list';
INSERT INTO food_product(name, kind, species, lead_time_days) VALUES
  ('Adult Dog Kibble',        'REGULAR',      'DOG', 4),
  ('Adult Cat Food',          'REGULAR',      'CAT', 4),
  ('Science Diet Rx (Bella)', 'PRESCRIPTION', 'DOG', 10);

-- ---------- intake (Sam at the front desk) ----------
SET rhrms.user_id = '4';
SET rhrms.reason  = 'Intake';

INSERT INTO bond_group(note) VALUES ('Otis and Pearl came in together; must be adopted together');

INSERT INTO animal(name, species, breed, sex, birth_date, birth_date_is_estimate, vet_status, spay_neuter,
                   behavior_notes, kennel_id, bond_group_id)
VALUES
  ('Bella', 'DOG', 'Shepherd mix', 'F', CURRENT_DATE - INTERVAL '5 years', TRUE, 'VETTED', 'ALTERED',
   'Somewhat reactive; not around young children', 1, NULL),
  ('Otis',  'DOG', 'St. Bernard',  'M', CURRENT_DATE - INTERVAL '7 years', FALSE, 'VETTED', 'ALTERED',
   NULL, 2, (SELECT id FROM bond_group LIMIT 1)),
  ('Pearl', 'DOG', 'St. Bernard',  'F', CURRENT_DATE - INTERVAL '6 years', FALSE, 'VETTED', 'ALTERED',
   NULL, 2, (SELECT id FROM bond_group LIMIT 1)),
  ('Princess', 'CAT', 'Domestic Short Hair', 'F', CURRENT_DATE - INTERVAL '2 years', TRUE, 'VETTED', 'ALTERED',
   NULL, 3, NULL),
  ('Maple', 'CAT', 'Domestic Short Hair', 'F', CURRENT_DATE - INTERVAL '3 years', TRUE, 'NOT_VETTED', 'INTACT',
   NULL, 4, NULL);

INSERT INTO litter(mother_animal_id, born_on, note)
VALUES ((SELECT id FROM animal WHERE name = 'Maple'), CURRENT_DATE - 21, 'Born in care, 3 kittens');

INSERT INTO animal(species, breed, sex, birth_date, birth_date_is_estimate, kennel_id, litter_id)
SELECT 'CAT', 'Domestic Short Hair', 'U', CURRENT_DATE - 21, FALSE, 4, (SELECT id FROM litter LIMIT 1)
FROM generate_series(1, 3);

INSERT INTO stay(animal_id, intake_date, intake_source, received_by)
SELECT id,
       CASE WHEN litter_id IS NOT NULL THEN CURRENT_DATE - 21 ELSE CURRENT_DATE - 30 END,
       CASE name WHEN 'Bella' THEN 'OWNER_SURRENDER' WHEN 'Otis' THEN 'OWNER_SURRENDER'
                 WHEN 'Pearl' THEN 'OWNER_SURRENDER' WHEN 'Princess' THEN 'STRAY'
                 WHEN 'Maple' THEN 'CITY_SHELTER' ELSE 'BORN_IN_CARE' END,
       4
FROM animal;

-- Maple and kittens are not ready yet
UPDATE animal SET status = 'NOT_ADOPTABLE', not_adoptable_reason = 'Not vetted yet; kittens too young'
WHERE kennel_id = 4;

-- ---------- corrections by staff ----------
SET rhrms.user_id = '2';
SET rhrms.reason  = 'Renamed: new name suits her better';
UPDATE animal SET name = 'Luna', version = version + 1 WHERE name = 'Princess';

SET rhrms.reason  = 'Behavior noted at intake';
INSERT INTO restriction(animal_id, type, detail)
VALUES ((SELECT id FROM animal WHERE name = 'Bella'), 'NO_CHILDREN', 'Reactive; no homes with young children');

SET rhrms.reason  = 'Vet prescription';
INSERT INTO prescription(food_product_id, animal_id, start_date)
VALUES ((SELECT id FROM food_product WHERE kind = 'PRESCRIPTION'), (SELECT id FROM animal WHERE name = 'Bella'),
        CURRENT_DATE - 30);

SET rhrms.reason  = 'Diets';
INSERT INTO diet(animal_id, food_product_id, cups_per_meal)
SELECT a.id, p.id, v.cups
FROM (VALUES ('Bella', 'Science Diet Rx (Bella)', 2.0),
             ('Otis',  'Adult Dog Kibble',        2.0),
             ('Pearl', 'Adult Dog Kibble',        1.75),
             ('Luna',  'Adult Cat Food',          0.5),
             ('Maple', 'Adult Cat Food',          0.75)) AS v(animal, food, cups)
JOIN animal a       ON a.name = v.animal
JOIN food_product p ON p.name = v.food;

-- ---------- stock on hand (Diane) ----------
SET rhrms.reason = 'Opening stock count';
SELECT move_stock(1, 1::SMALLINT, 'RECEIVE', 3, 2);   -- dog kibble, shelter
SELECT move_stock(1, 2::SMALLINT, 'RECEIVE', 2, 2);   -- dog kibble, garage overflow
SELECT move_stock(2, 1::SMALLINT, 'RECEIVE', 2, 2);   -- cat food, shelter
SELECT move_stock(3, 1::SMALLINT, 'RECEIVE', 1, 2);   -- Bella's prescription, shelter: the last bag

-- ---------- two applicants for Bella (Interview 2 story) ----------
SET rhrms.user_id = '4';
SET rhrms.reason  = 'Paper application entered at front desk';
INSERT INTO person(first_name, last_name, phone, city) VALUES
  ('Alex',  'Morgan', '(970) 555-0142', 'Loveland'),
  ('Jamie', 'Lee',    '970-555-0188',   'Berthoud');

INSERT INTO application(person_id, animal_id, received_by, housing_type, has_yard, yard_fenced,
                        children_count, youngest_child_age, elderly_in_home, adults_in_home, reason_for_adopting)
VALUES
  ((SELECT id FROM person WHERE last_name = 'Morgan'), (SELECT id FROM animal WHERE name = 'Bella'), 4,
   'HOUSE', TRUE, TRUE, 1, 4, FALSE, '1 man, 1 woman', 'Want a family dog for our son'),
  ((SELECT id FROM person WHERE last_name = 'Lee'),    (SELECT id FROM animal WHERE name = 'Bella'), 4,
   'HOUSE', TRUE, FALSE, 0, NULL, FALSE, '1 woman', 'Lots of room to run on two acres');

-- ---------- home visits ----------
-- Sam visited Morgan and entered it himself
SET rhrms.reason = 'Home visit';
INSERT INTO home_visit(application_id, visited_on, visitor_user_id, visitor_name, entered_by,
                       children_present, youngest_child_age, yard, yard_fenced, elderly_residents,
                       adult_men, adult_women, housing_confirmed, recommendation, notes)
VALUES ((SELECT a.id FROM application a JOIN person p ON p.id = a.person_id WHERE p.last_name = 'Morgan'),
        CURRENT_DATE - 3, 4, 'Sam', 4, TRUE, 4, TRUE, TRUE, FALSE, 1, 1, TRUE, 'UNSURE',
        'Great chain-link fenced yard and a kind family, but there is a four-year-old boy at home.');

-- A volunteer without a login visited Lee; Jordan typed it in on their behalf
SET rhrms.user_id      = '3';
SET rhrms.on_behalf_of = 'Casey (volunteer, no login)';
INSERT INTO home_visit(application_id, visited_on, visitor_name, entered_by,
                       children_present, yard, yard_fenced, elderly_residents,
                       adult_men, adult_women, housing_confirmed, recommendation, notes)
VALUES ((SELECT a.id FROM application a JOIN person p ON p.id = a.person_id WHERE p.last_name = 'Lee'),
        CURRENT_DATE - 2, 'Casey', 3, FALSE, TRUE, FALSE, FALSE, 0, 1, TRUE, 'APPROVE',
        'About two acres of open land, no children, calm household. Good fit for a reactive dog.');
RESET rhrms.on_behalf_of;

-- ---------- the director decides (Interview 3: Mike makes the final call) ----------
SET rhrms.user_id = '1';
SET rhrms.reason  = 'Decision on competing applications for Bella';

INSERT INTO decision(application_id, outcome, denial_reason, rationale, conflicts_snapshot, home_visit_id, decided_by)
SELECT a.id, 'DENY', 'CHILDREN_UNSUITABLE',
       'Bella is reactive and will not be placed with a young child, even though this family applied first and has a fenced yard.',
       (SELECT string_agg(conflict, '; ') FROM restriction_conflicts rc WHERE rc.application_id = a.id),
       (SELECT id FROM home_visit WHERE application_id = a.id), 1
FROM application a JOIN person p ON p.id = a.person_id WHERE p.last_name = 'Morgan';
UPDATE application SET status = 'DENIED', version = version + 1
WHERE person_id = (SELECT id FROM person WHERE last_name = 'Morgan');

INSERT INTO decision(application_id, outcome, rationale, home_visit_id, decided_by)
SELECT a.id, 'APPROVE',
       'No children, lots of space, and the visitor recommended approval. Best fit for Bella.',
       (SELECT id FROM home_visit WHERE application_id = a.id), 1
FROM application a JOIN person p ON p.id = a.person_id WHERE p.last_name = 'Lee';
UPDATE application SET status = 'APPROVED', version = version + 1   -- Bella becomes PENDING_ADOPTION automatically
WHERE person_id = (SELECT id FROM person WHERE last_name = 'Lee');

RESET rhrms.user_id;
RESET rhrms.reason;
-- Bella is now approved for Jamie Lee and waiting for adoption day:
--   SELECT place_animal(<application id>, 2, 250, 'CASH');
