-- =====================================================================
-- RHRMS  Stage 1 · 02_rules.sql
-- Business rules enforced INSIDE the database, so no client (terminal UI,
-- import script, or someone typing SQL) can put the data in a bad state.
-- Each trigger raises a plain-English message the UI can show as-is.
-- =====================================================================
SET search_path = rhrms;

-- ---------------------------------------------------------------------
-- AN-1  Animal status transitions
-- ---------------------------------------------------------------------
CREATE FUNCTION check_animal_status() RETURNS trigger AS $$
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF NOT (
       (OLD.status = 'AVAILABLE'        AND NEW.status IN ('NOT_ADOPTABLE','PENDING_ADOPTION','DECEASED'))
    OR (OLD.status = 'NOT_ADOPTABLE'    AND NEW.status IN ('AVAILABLE','DECEASED'))
    OR (OLD.status = 'PENDING_ADOPTION' AND NEW.status IN ('AVAILABLE','ADOPTED','NOT_ADOPTABLE','DECEASED'))
    OR (OLD.status = 'ADOPTED'          AND NEW.status IN ('NOT_ADOPTABLE','AVAILABLE'))      -- a return
  ) THEN
    RAISE EXCEPTION 'Animal % cannot change from % to %.', OLD.animal_code, OLD.status, NEW.status
      USING ERRCODE = 'check_violation', HINT = 'RULE AN-1';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER animal_status_rule BEFORE UPDATE OF status ON animal
  FOR EACH ROW EXECUTE FUNCTION check_animal_status();

-- ---------------------------------------------------------------------
-- AN-2  Renaming keeps the old name (searchable forever)
-- ---------------------------------------------------------------------
CREATE FUNCTION keep_name_history() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'INSERT' AND NEW.name IS NOT NULL THEN
    INSERT INTO animal_name_history(animal_id, name) VALUES (NEW.id, NEW.name);
  ELSIF TG_OP = 'UPDATE' AND NEW.name IS DISTINCT FROM OLD.name AND NEW.name IS NOT NULL THEN
    INSERT INTO animal_name_history(animal_id, name) VALUES (NEW.id, NEW.name);
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER animal_name_history_rule AFTER INSERT OR UPDATE OF name ON animal
  FOR EACH ROW EXECUTE FUNCTION keep_name_history();

-- ---------------------------------------------------------------------
-- IN-2  Kennel capacity: a kennel must be in service; an occupied kennel
--       may only be shared by a bond partner or a litter-mate / mother.
-- ---------------------------------------------------------------------

-- Two people putting two unrelated animals in kennel 7 at the same moment must not both
-- succeed, so the check starts by locking the kennel row (L3). Taking a FOR UPDATE lock
-- needs UPDATE privilege on kennel, and a VOLUNTEER deliberately does not have that: the
-- spec lets volunteers record an intake but not edit kennels (§7).
--
-- This one-statement SECURITY DEFINER helper takes the lock as the table owner, so intake by
-- a volunteer is still serialised without handing them the ability to change a kennel. It
-- cannot be used for anything else: it only reads and locks one row and returns it.
CREATE FUNCTION lock_kennel(p_id SMALLINT) RETURNS kennel
LANGUAGE sql SECURITY DEFINER SET search_path = rhrms AS
  $$ SELECT * FROM kennel WHERE id = p_id FOR UPDATE $$;

CREATE FUNCTION check_kennel() RETURNS trigger AS $$
DECLARE
  k        kennel%ROWTYPE;
  stranger TEXT;
BEGIN
  IF NEW.kennel_id IS NULL THEN RETURN NEW; END IF;
  IF TG_OP = 'UPDATE' AND NEW.kennel_id IS NOT DISTINCT FROM OLD.kennel_id THEN RETURN NEW; END IF;

  SELECT * INTO k FROM lock_kennel(NEW.kennel_id);   -- serialize kennel assignment (L3)
  IF NOT k.in_service THEN
    RAISE EXCEPTION 'Kennel % is out of service (%).', k.id, k.out_of_service_reason
      USING ERRCODE = 'check_violation', HINT = 'RULE IN-2';
  END IF;

  SELECT string_agg(coalesce(o.name, o.animal_code), ', ') INTO stranger
  FROM animal o
  WHERE o.kennel_id = NEW.kennel_id
    AND o.id <> NEW.id
    AND NOT (NEW.bond_group_id IS NOT NULL AND o.bond_group_id = NEW.bond_group_id)
    AND NOT (NEW.litter_id     IS NOT NULL AND o.litter_id     = NEW.litter_id)
    AND NOT (NEW.litter_id     IS NOT NULL AND o.id = (SELECT mother_animal_id FROM litter WHERE id = NEW.litter_id))
    AND NOT (o.litter_id       IS NOT NULL AND NEW.id = (SELECT mother_animal_id FROM litter WHERE id = o.litter_id));
  IF stranger IS NOT NULL THEN
    RAISE EXCEPTION 'Kennel % is occupied by %. Only a bond partner or litter can share it.', NEW.kennel_id, stranger
      USING ERRCODE = 'check_violation', HINT = 'RULE IN-2';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER animal_kennel_rule BEFORE INSERT OR UPDATE OF kennel_id ON animal
  FOR EACH ROW EXECUTE FUNCTION check_kennel();

-- ---------------------------------------------------------------------
-- AP-1  An application can only be created for an animal that is
--       adoptable AT THE MOMENT OF SAVING. FOR SHARE makes a concurrent
--       placement finish first, so we see its result (L2 + L3).
-- ---------------------------------------------------------------------
-- Same privilege problem as lock_kennel() above: PostgreSQL requires UPDATE privilege to
-- take ANY row lock, FOR SHARE included, and a VOLUNTEER must not be able to edit an animal.
-- A volunteer typing in a paper application still has to wait for a placement that is being
-- recorded at the same second, so the wait is done here as the owner. FOR SHARE, not FOR
-- UPDATE: two people may enter competing applications at once (Interview 2's Bella story),
-- they just both have to see the animal's settled status.
CREATE FUNCTION lock_animal_shared(p_id BIGINT) RETURNS animal
LANGUAGE sql SECURITY DEFINER SET search_path = rhrms AS
  $$ SELECT * FROM animal WHERE id = p_id FOR SHARE $$;

CREATE FUNCTION check_application_insert() RETURNS trigger AS $$
DECLARE a animal%ROWTYPE;
BEGIN
  SELECT * INTO a FROM lock_animal_shared(NEW.animal_id);
  IF a.status NOT IN ('AVAILABLE','PENDING_ADOPTION') THEN
    RAISE EXCEPTION '% (%) is % and cannot take new applications.',
      coalesce(a.name, 'This animal'), a.animal_code, lower(replace(a.status, '_', ' '))
      USING ERRCODE = 'check_violation', HINT = 'RULE AP-1';
  END IF;
  IF NEW.status <> 'SUBMITTED' THEN
    RAISE EXCEPTION 'A new application must start as SUBMITTED.'
      USING ERRCODE = 'check_violation', HINT = 'RULE AP-1';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER application_insert_rule BEFORE INSERT ON application
  FOR EACH ROW EXECUTE FUNCTION check_application_insert();

-- ---------------------------------------------------------------------
-- §6.3  Application status transitions (the state diagram)
-- ---------------------------------------------------------------------
CREATE FUNCTION check_application_status() RETURNS trigger AS $$
BEGIN
  IF NEW.status = OLD.status THEN RETURN NEW; END IF;
  IF NOT (
       (OLD.status = 'SUBMITTED'    AND NEW.status IN ('UNDER_REVIEW','DENIED','WITHDRAWN','CLOSED'))
    OR (OLD.status = 'UNDER_REVIEW' AND NEW.status IN ('APPROVED','DENIED','WITHDRAWN','CLOSED'))
    OR (OLD.status = 'APPROVED'     AND NEW.status IN ('PLACED','WITHDRAWN','DENIED'))   -- DENIED = director override
    OR (OLD.status = 'DENIED'       AND NEW.status IN ('APPROVED'))                      -- director override
  ) THEN
    RAISE EXCEPTION 'Application % cannot change from % to %.', OLD.id, OLD.status, NEW.status
      USING ERRCODE = 'check_violation', HINT = 'RULE AP-STATE';
  END IF;

  -- HV-1: approval requires a home visit on this application, or an approved reuse (RC-2)
  IF NEW.status = 'APPROVED'
     AND NEW.reused_home_visit_id IS NULL
     AND NOT EXISTS (SELECT 1 FROM home_visit WHERE application_id = NEW.id) THEN
    RAISE EXCEPTION 'Application % has no home visit. Every application needs one before approval.', NEW.id
      USING ERRCODE = 'check_violation', HINT = 'RULE HV-1';
  END IF;

  -- DE-3/DE-4: status APPROVED/DENIED must be backed by a decision row written first
  IF NEW.status IN ('APPROVED','DENIED') AND NOT EXISTS (
       SELECT 1 FROM decision d
       WHERE d.application_id = NEW.id
         AND d.outcome = CASE NEW.status WHEN 'APPROVED' THEN 'APPROVE' ELSE 'DENY' END
         AND NOT EXISTS (SELECT 1 FROM decision s WHERE s.supersedes_decision_id = d.id)) THEN
    RAISE EXCEPTION 'Application % cannot be % without a recorded decision and rationale.', NEW.id, lower(NEW.status)
      USING ERRCODE = 'check_violation', HINT = 'RULE DE-3';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER application_status_rule BEFORE UPDATE OF status ON application
  FOR EACH ROW EXECUTE FUNCTION check_application_status();

-- DE-5  Approval puts the animal (and bond partners) on hold; withdrawing or
--       overriding an approval puts them back up for adoption.
CREATE FUNCTION sync_animal_with_application() RETURNS trigger AS $$
BEGIN
  IF NEW.status = 'APPROVED' AND OLD.status <> 'APPROVED' THEN
    UPDATE animal a SET status = 'PENDING_ADOPTION', version = version + 1
     WHERE a.status <> 'PENDING_ADOPTION' AND a.status <> 'DECEASED'
       AND (a.id = NEW.animal_id OR a.bond_group_id =
            (SELECT bond_group_id FROM animal WHERE id = NEW.animal_id));
  ELSIF OLD.status = 'APPROVED' AND NEW.status IN ('WITHDRAWN','DENIED') THEN
    UPDATE animal a SET status = 'AVAILABLE', version = version + 1
     WHERE a.status = 'PENDING_ADOPTION'
       AND (a.id = NEW.animal_id OR a.bond_group_id =
            (SELECT bond_group_id FROM animal WHERE id = NEW.animal_id));
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER application_syncs_animal AFTER UPDATE OF status ON application
  FOR EACH ROW EXECUTE FUNCTION sync_animal_with_application();

-- ---------------------------------------------------------------------
-- HV-3  Recording a home visit moves SUBMITTED -> UNDER_REVIEW
-- ---------------------------------------------------------------------
CREATE FUNCTION home_visit_starts_review() RETURNS trigger AS $$
BEGIN
  UPDATE application SET status = 'UNDER_REVIEW', version = version + 1
  WHERE id = NEW.application_id AND status = 'SUBMITTED';
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER home_visit_review_rule AFTER INSERT ON home_visit
  FOR EACH ROW EXECUTE FUNCTION home_visit_starts_review();

-- ---------------------------------------------------------------------
-- DE-1  Only a DIRECTOR (or roles listed in setting decision.allowed_roles)
--       may record a decision. Overrides (supersedes) are DIRECTOR only.
-- ---------------------------------------------------------------------
CREATE FUNCTION check_decision() RETURNS trigger AS $$
DECLARE
  r       TEXT;
  allowed TEXT;
BEGIN
  SELECT role INTO r FROM staff_user WHERE id = NEW.decided_by AND active;
  SELECT value INTO allowed FROM setting WHERE key = 'decision.allowed_roles';
  IF r IS NULL OR NOT (r = ANY (string_to_array(coalesce(allowed, 'DIRECTOR'), ','))) THEN
    RAISE EXCEPTION 'Only the director can decide an application (user % is %).', NEW.decided_by, coalesce(r, 'inactive')
      USING ERRCODE = 'insufficient_privilege', HINT = 'RULE DE-1';
  END IF;
  IF NEW.supersedes_decision_id IS NOT NULL AND r <> 'DIRECTOR' THEN
    RAISE EXCEPTION 'Only the director can override an earlier decision.'
      USING ERRCODE = 'insufficient_privilege', HINT = 'RULE DE-6';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER decision_rule BEFORE INSERT ON decision
  FOR EACH ROW EXECUTE FUNCTION check_decision();

-- Decisions are permanent: corrections are new rows that supersede old ones.
CREATE FUNCTION forbid_update() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% records cannot be edited. Record a new one instead.', initcap(replace(TG_TABLE_NAME, '_', ' '))
    USING ERRCODE = 'check_violation', HINT = 'RULE L4';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER decision_is_permanent   BEFORE UPDATE ON decision   FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE TRIGGER home_visit_is_permanent BEFORE UPDATE ON home_visit FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- ---------------------------------------------------------------------
-- PL-1  A placement needs an APPROVED application for that animal.
--       Locks the application row so two placements cannot both pass (L3).
-- ---------------------------------------------------------------------
CREATE FUNCTION check_placement() RETURNS trigger AS $$
DECLARE app application%ROWTYPE;
BEGIN
  SELECT * INTO app FROM application WHERE id = NEW.application_id FOR UPDATE;
  IF app.status NOT IN ('APPROVED','PLACED') THEN
    RAISE EXCEPTION 'Application % is %, not approved. The adoption cannot be recorded.', app.id, lower(app.status)
      USING ERRCODE = 'check_violation', HINT = 'RULE PL-1';
  END IF;
  IF app.person_id <> NEW.person_id THEN
    RAISE EXCEPTION 'Placement person does not match the application.'
      USING ERRCODE = 'check_violation', HINT = 'RULE PL-1';
  END IF;
  -- the application's animal, or one of its bond partners (AN-6)
  IF NOT EXISTS (
       SELECT 1 FROM animal a JOIN animal b ON b.id = app.animal_id
       WHERE a.id = NEW.animal_id
         AND (a.id = b.id OR (a.bond_group_id IS NOT NULL AND a.bond_group_id = b.bond_group_id))) THEN
    RAISE EXCEPTION 'This animal is not covered by application %.', app.id
      USING ERRCODE = 'check_violation', HINT = 'RULE PL-1';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER placement_rule BEFORE INSERT ON placement
  FOR EACH ROW EXECUTE FUNCTION check_placement();

-- ---------------------------------------------------------------------
-- FD-7  Prescription food only for animals with an active prescription
-- ---------------------------------------------------------------------
CREATE FUNCTION check_prescription_use() RETURNS trigger AS $$
DECLARE k TEXT;
BEGIN
  IF NEW.animal_id IS NULL THEN RETURN NEW; END IF;
  SELECT kind INTO k FROM food_product WHERE id = NEW.food_product_id;
  IF k = 'PRESCRIPTION' AND NOT EXISTS (
       SELECT 1 FROM prescription p
       WHERE p.food_product_id = NEW.food_product_id AND p.animal_id = NEW.animal_id
         AND p.start_date <= CURRENT_DATE AND (p.end_date IS NULL OR p.end_date >= CURRENT_DATE)) THEN
    RAISE EXCEPTION 'This prescription food is not prescribed for animal %.', NEW.animal_id
      USING ERRCODE = 'check_violation', HINT = 'RULE FD-7';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER diet_prescription_rule     BEFORE INSERT OR UPDATE OF food_product_id, animal_id ON diet
  FOR EACH ROW EXECUTE FUNCTION check_prescription_use();
CREATE TRIGGER movement_prescription_rule BEFORE INSERT ON stock_movement
  FOR EACH ROW EXECUTE FUNCTION check_prescription_use();

-- Stock movements are a ledger: append-only.
CREATE TRIGGER stock_movement_is_permanent BEFORE UPDATE ON stock_movement
  FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- =====================================================================
-- Stored procedures: the ONLY supported way to do multi-step actions.
-- Each runs inside the caller's transaction: all or nothing (IN-4).
-- =====================================================================

-- FD-5  Move food stock. Guarded update: never below zero, and two
--       sessions taking the last bag cannot both succeed (L2 + L3).
CREATE FUNCTION move_stock(p_product BIGINT, p_location SMALLINT, p_type TEXT, p_bags INT,
                           p_user BIGINT, p_reason TEXT DEFAULT NULL, p_animal BIGINT DEFAULT NULL,
                           p_donation BIGINT DEFAULT NULL, p_order BIGINT DEFAULT NULL)
RETURNS INT AS $$
DECLARE
  delta   INT := CASE WHEN p_type IN ('RECEIVE','TRANSFER_IN') THEN abs(p_bags) ELSE -abs(p_bags) END;
  left_n  INT;
  on_hand INT;
BEGIN
  IF p_bags = 0 THEN
    RAISE EXCEPTION 'Enter a number of bags greater than zero.' USING ERRCODE = 'check_violation', HINT = 'RULE FD-5';
  END IF;
  INSERT INTO stock_level(food_product_id, location_id) VALUES (p_product, p_location)
    ON CONFLICT DO NOTHING;
  UPDATE stock_level SET sealed_bags = sealed_bags + delta, version = version + 1
   WHERE food_product_id = p_product AND location_id = p_location AND sealed_bags + delta >= 0
  RETURNING sealed_bags INTO left_n;
  IF NOT FOUND THEN
    SELECT sealed_bags INTO on_hand FROM stock_level WHERE food_product_id = p_product AND location_id = p_location;
    RAISE EXCEPTION 'Not enough bags: % on hand at this location, % requested.', on_hand, abs(p_bags)
      USING ERRCODE = 'check_violation', HINT = 'RULE FD-5';
  END IF;
  INSERT INTO stock_movement(food_product_id, location_id, type, bags, animal_id, donation_id, order_id, reason, recorded_by)
  VALUES (p_product, p_location, p_type, delta, p_animal, p_donation, p_order, p_reason, p_user);
  IF p_type = 'OPEN' THEN                                           -- FD-6
    UPDATE open_bag SET finished_at = now() WHERE food_product_id = p_product AND finished_at IS NULL;
    INSERT INTO open_bag(food_product_id) VALUES (p_product);
  END IF;
  RETURN left_n;
END $$ LANGUAGE plpgsql;

-- PL-1  Record an adoption: every step or none.
CREATE FUNCTION place_animal(p_application BIGINT, p_user BIGINT, p_fee NUMERIC,
                             p_payment TEXT, p_fee_reason TEXT DEFAULT NULL, p_vet_clinic TEXT DEFAULT NULL)
RETURNS INT AS $$
DECLARE
  app      application%ROWTYPE;
  baseline NUMERIC := (SELECT value::NUMERIC FROM setting WHERE key = 'fee.baseline');
  pet      RECORD;
  n        INT := 0;
BEGIN
  SELECT * INTO app FROM application WHERE id = p_application FOR UPDATE;
  IF app.status <> 'APPROVED' THEN
    RAISE EXCEPTION 'Application % is %, not approved. The adoption was not recorded.', p_application, lower(app.status)
      USING ERRCODE = 'check_violation', HINT = 'RULE PL-1';
  END IF;

  FOR pet IN
    SELECT a.* FROM animal a JOIN animal b ON b.id = app.animal_id
    WHERE (a.id = b.id OR (a.bond_group_id IS NOT NULL AND a.bond_group_id = b.bond_group_id))
      AND a.status <> 'DECEASED'
    ORDER BY a.id FOR UPDATE OF a
  LOOP
    -- PL-3. The vetting block is a guessed default (open question Q3), so it is a setting, and
    -- §7 lets the DIRECTOR override it. The override is announced for one transaction only, by
    -- the API, after it has checked the role and taken a written reason and the director's
    -- password; the reason is on the audit row for the placement.
    IF pet.vet_status = 'NOT_VETTED'
       AND (SELECT value FROM setting WHERE key = 'placement.require_vetted') = 'true'
       AND coalesce(current_setting('rhrms.override_vetting', true), '') <> 'true' THEN
      RAISE EXCEPTION '% has not been vetted yet. The adoption was not recorded.', coalesce(pet.name, pet.animal_code)
        USING ERRCODE = 'check_violation', HINT = 'RULE PL-3';
    END IF;
    INSERT INTO placement(animal_id, application_id, person_id, placed_on, fee_baseline, fee_charged,
                          fee_reason, payment_method, vet_clinic_told, recorded_by)
    VALUES (pet.id, app.id, app.person_id, CURRENT_DATE, CASE WHEN n = 0 THEN baseline ELSE 0 END,
            CASE WHEN n = 0 THEN p_fee ELSE 0 END,
            CASE WHEN n = 0 THEN p_fee_reason ELSE 'Bonded partner: one fee covers the pair' END,
            CASE WHEN n = 0 THEN p_payment ELSE 'WAIVED' END, p_vet_clinic, p_user);
    UPDATE animal SET status = 'ADOPTED', kennel_id = NULL, version = version + 1 WHERE id = pet.id;
    UPDATE stay   SET ended_on = CURRENT_DATE, end_reason = 'PLACED' WHERE animal_id = pet.id AND ended_on IS NULL;
    UPDATE diet   SET valid_to = CURRENT_DATE WHERE animal_id = pet.id AND valid_to IS NULL;
    UPDATE application SET status = 'CLOSED', closed_reason = 'Animal placed with another applicant', version = version + 1
     WHERE animal_id = pet.id AND id <> app.id AND status IN ('SUBMITTED','UNDER_REVIEW');
    n := n + 1;
  END LOOP;

  UPDATE application SET status = 'PLACED', version = version + 1 WHERE id = app.id;
  RETURN n;   -- number of animals placed (2 for a bonded pair)
END $$ LANGUAGE plpgsql;

-- IN-4  Record a return: closes the placement with a reason, starts a new
--       stay on the SAME animal, puts the animal back in a kennel.
CREATE FUNCTION return_animal(p_animal BIGINT, p_user BIGINT, p_reason TEXT, p_kennel SMALLINT)
RETURNS BIGINT AS $$
DECLARE
  pl placement%ROWTYPE;
  new_stay BIGINT;
BEGIN
  SELECT * INTO pl FROM placement WHERE animal_id = p_animal AND returned_on IS NULL FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Animal % has no active adoption to return.', p_animal
      USING ERRCODE = 'check_violation', HINT = 'RULE IN-4';
  END IF;
  UPDATE placement SET returned_on = CURRENT_DATE, return_reason = p_reason, return_recorded_by = p_user
   WHERE id = pl.id;
  INSERT INTO stay(animal_id, intake_date, intake_source, prior_placement_id, intake_notes, received_by)
  VALUES (p_animal, CURRENT_DATE, 'RETURN', pl.id, p_reason, p_user)
  RETURNING id INTO new_stay;
  UPDATE animal SET status = 'NOT_ADOPTABLE',
                    not_adoptable_reason = 'Returned: review restrictions before listing again',
                    kennel_id = p_kennel, version = version + 1
   WHERE id = p_animal;
  RETURN new_stay;
END $$ LANGUAGE plpgsql;
