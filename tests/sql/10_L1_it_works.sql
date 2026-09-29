-- L1  It works: the happy path of every workflow.
-- Runs as rhrms_app (same rights as the UI). Everything is rolled back at the end.
BEGIN;
SET LOCAL search_path = rhrms, rhrms_test;
SET LOCAL rhrms.user_id = '4';

-- seed
SELECT ok((SELECT count(*) FROM kennel) = 40, 'Seed: 40 numbered kennels exist');
SELECT ok((SELECT value FROM setting WHERE key = 'fee.baseline') = '250.00', 'Seed: fee baseline is $250 (setting)');

-- intake
INSERT INTO animal(name, species, kennel_id) VALUES ('Rex', 'DOG', 5);
INSERT INTO stay(animal_id, intake_date, intake_source, received_by)
  VALUES (animal_id('Rex'), CURRENT_DATE, 'STRAY', 4);
SELECT ok((SELECT animal_code ~ '^RH-[0-9]{6}$' FROM animal WHERE name = 'Rex'),
          'IN-5: intake assigns a permanent animal code like RH-000010');
SELECT ok((SELECT status FROM animal WHERE name = 'Rex') = 'AVAILABLE', 'IN-1: new animal is AVAILABLE');
SELECT ok((SELECT animals FROM kennel_board WHERE kennel = 5) = 1, 'Kennel board shows Rex in kennel 5');

-- unnamed animal
INSERT INTO animal(species, kennel_id) VALUES ('CAT', 6);
SELECT ok((SELECT count(*) FROM animal WHERE kennel_id = 6 AND name IS NULL) = 1, 'IN-1: an animal can arrive without a name');

-- rename history
SELECT ok(EXISTS (SELECT 1 FROM animal_name_history h JOIN animal a ON a.id = h.animal_id
                  WHERE lower(h.name) = 'princess' AND a.name = 'Luna'),
          'AN-2: searching "Princess" finds Luna (name history kept)');

-- bonded pair and litter share kennels
SELECT ok((SELECT animals FROM kennel_board WHERE kennel = 2) = 2, 'Otis and Pearl (bonded) share kennel 2');
SELECT ok((SELECT animals FROM kennel_board WHERE kennel = 4) = 4, 'Maple and her 3 kittens share kennel 4');

-- adoption workflow from the sample data
SELECT ok((SELECT status FROM animal WHERE name = 'Bella') = 'PENDING_ADOPTION',
          'DE-5: approving Jamie Lee put Bella on PENDING_ADOPTION automatically');
SELECT ok((SELECT count(*) FROM decision_record WHERE animal_name = 'Bella') = 2,
          'LK-2: decision record lists both decisions for Bella');

-- food forecast: Bella eats 2 cups x 2 meals x 0.25 lb = 1 lb/day; 1 bag = 40 lb
SELECT ok((SELECT lbs_per_day FROM food_forecast WHERE kind = 'PRESCRIPTION') = 1.00,
          'FD-1: Bella uses 1.00 lb/day of prescription food');
SELECT ok((SELECT days_of_supply FROM food_forecast WHERE kind = 'PRESCRIPTION') = 40.0,
          'FD-3: one 40-lb bag = 40 days of supply');

-- open a bag
SELECT move_stock(1, 1::SMALLINT, 'OPEN', 1, 4);
SELECT ok((SELECT sealed_bags FROM stock_level WHERE food_product_id = 1 AND location_id = 1) = 2,
          'FD-6: opening a bag leaves 2 sealed bags at the shelter');
SELECT ok((SELECT count(*) FROM open_bag WHERE food_product_id = 1 AND finished_at IS NULL) = 1,
          'FD-6: one open bag is tracked');

-- adoption day for Bella
SET LOCAL rhrms.user_id = '2';
SELECT ok(place_animal(app_of('Lee', 'Bella'), 2, 250, 'CASH', NULL, 'Loveland Vet Clinic') = 1,
          'PL-1: adoption recorded for Bella');
SELECT ok((SELECT status = 'ADOPTED' AND kennel_id IS NULL FROM animal WHERE name = 'Bella'),
          'PL-1: Bella is ADOPTED and her kennel is free');
SELECT ok((SELECT status FROM application WHERE id = app_of('Lee', 'Bella')) = 'PLACED',
          'PL-1: Jamie Lee''s application is PLACED');
SELECT ok((SELECT status FROM application WHERE id = app_of('Morgan', 'Bella')) = 'DENIED',
          'PL-1: the earlier denial is untouched');
SELECT ok((SELECT food_status FROM food_forecast WHERE kind = 'PRESCRIPTION') = 'NO CURRENT USE',
          'FD-3: after adoption nobody eats the prescription food');

-- bonded pair adoption: one application covers both
SET LOCAL rhrms.user_id = '1';
SELECT ready_to_approve('Robin', 'Diaz', 'Otis');
UPDATE application SET status = 'APPROVED' WHERE id = app_of('Diaz', 'Otis');
SELECT ok((SELECT status FROM animal WHERE name = 'Pearl') = 'PENDING_ADOPTION',
          'AN-6: approving Otis also holds his bond partner Pearl');
SELECT ok(place_animal(app_of('Diaz', 'Otis'), 2, 200, 'CHECK', 'Good home, adopter asked for a reduction') = 2,
          'AN-6: one adoption places both Otis and Pearl');
SELECT ok((SELECT count(*) FROM placement WHERE application_id = app_of('Diaz', 'Otis')) = 2,
          'AN-6: two placement rows, one application');

-- return
SET LOCAL rhrms.user_id = '2';
SELECT return_animal(animal_id('Bella'), 2, 'Adopter moved to a place that does not allow dogs', 1::SMALLINT);
SELECT ok((SELECT intake_source FROM stay WHERE animal_id = animal_id('Bella') AND ended_on IS NULL) = 'RETURN',
          'IN-4: return starts a new stay on the same animal');
SELECT ok((SELECT return_reason IS NOT NULL FROM placement WHERE animal_id = animal_id('Bella')),
          'IN-4: the return reason is recorded on the placement');
SELECT ok((SELECT status FROM animal WHERE name = 'Bella') = 'NOT_ADOPTABLE',
          'IN-4: returned animal waits for restriction review');

ROLLBACK;
