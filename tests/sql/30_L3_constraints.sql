-- L3  It holds under contention (single-session checks of the guarantees).
-- The true two-session race tests are in tests/concurrency/.
BEGIN;
SET LOCAL search_path = rhrms, rhrms_test;
SET LOCAL rhrms.user_id = '1';

-- one approved application per animal
SELECT ready_to_approve('Kai', 'Nguyen', 'Bella');
SELECT expect_error('L3: a second approval for Bella is refused (one approved per animal)',
  $$ UPDATE application SET status = 'APPROVED' WHERE id = app_of('Nguyen', 'Bella') $$, NULL, '23505');

-- one active placement per animal
SELECT place_animal(app_of('Lee', 'Bella'), 2, 250, 'CASH');
SELECT expect_error('L3: placing Bella a second time is refused',
  $$ SELECT place_animal(app_of('Lee', 'Bella'), 2, 250, 'CASH') $$, 'RULE PL-1');
SELECT expect_error('L3: a raw INSERT of a second placement is refused too',
  $$ INSERT INTO placement(animal_id, application_id, person_id, placed_on, fee_baseline, fee_charged,
                           payment_method, recorded_by)
     SELECT animal_id, application_id, person_id, CURRENT_DATE, 250, 250, 'CASH', 2
     FROM placement WHERE animal_id = animal_id('Bella') $$, NULL, '23505');

-- optimistic locking: an update with a stale version changes nothing
DO $$
DECLARE v INT; n INT;
BEGIN
  SELECT version INTO v FROM rhrms.animal WHERE name = 'Luna';
  UPDATE rhrms.animal SET behavior_notes = 'first edit', version = version + 1 WHERE name = 'Luna' AND version = v;
  UPDATE rhrms.animal SET behavior_notes = 'stale edit', version = version + 1 WHERE name = 'Luna' AND version = v;
  GET DIAGNOSTICS n = ROW_COUNT;
  PERFORM rhrms_test.ok(n = 0, 'L3: an edit based on an old version is detected (0 rows updated)');
  PERFORM rhrms_test.ok((SELECT behavior_notes FROM rhrms.animal WHERE name = 'Luna') = 'first edit',
                        'L3: the first edit was not silently overwritten');
END $$;

-- one open food order per product
INSERT INTO food_order(food_product_id, bags, requested_by) VALUES (3, 2, 4);
SELECT expect_error('FD-9: only one open order per product (no double ordering)',
  $$ INSERT INTO food_order(food_product_id, bags, requested_by) VALUES (3, 2, 2) $$, NULL, '23505');

ROLLBACK;
