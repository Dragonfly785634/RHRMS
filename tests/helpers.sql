-- Test helpers (installed by run_tests.sh, never in production).
CREATE SCHEMA rhrms_test;
SET search_path = rhrms_test;

-- ok(condition, label): PASS if true, FAIL otherwise
CREATE FUNCTION ok(cond BOOLEAN, label TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
  IF cond IS TRUE THEN RAISE NOTICE 'PASS: %', label;
  ELSE RAISE EXCEPTION 'FAIL: %', label; END IF;
END $$;

-- expect_error(label, sql, hint, sqlstate): PASS if the statement is refused
-- with the given rule hint and/or SQLSTATE. The statement is rolled back.
CREATE FUNCTION expect_error(label TEXT, stmt TEXT, want_hint TEXT DEFAULT NULL, want_state TEXT DEFAULT NULL)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE st TEXT; h TEXT; m TEXT;
BEGIN
  BEGIN
    EXECUTE stmt;
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS st = RETURNED_SQLSTATE, h = PG_EXCEPTION_HINT, m = MESSAGE_TEXT;
    IF (want_hint IS NULL OR h = want_hint) AND (want_state IS NULL OR st = want_state) THEN
      RAISE NOTICE 'PASS: %  ->  "%"', label, m;
      RETURN;
    END IF;
    RAISE EXCEPTION 'FAIL: % (got sqlstate %, hint %, message "%")', label, st, coalesce(h, '-'), m;
  END;
  RAISE EXCEPTION 'FAIL: % (statement was allowed but should have been refused)', label;
END $$;

-- id lookups that keep the tests readable
CREATE FUNCTION animal_id(n TEXT) RETURNS BIGINT LANGUAGE sql STABLE AS
  $$ SELECT id FROM rhrms.animal WHERE name = n $$;
CREATE FUNCTION app_of(last TEXT, pet TEXT) RETURNS BIGINT LANGUAGE sql STABLE AS
  $$ SELECT a.id FROM rhrms.application a JOIN rhrms.person p ON p.id = a.person_id
     JOIN rhrms.animal an ON an.id = a.animal_id
     WHERE p.last_name = last AND an.name = pet ORDER BY a.id DESC LIMIT 1 $$;

-- Build an application that is ready to approve: person + application + home visit + APPROVE decision.
CREATE FUNCTION ready_to_approve(first TEXT, last TEXT, pet TEXT) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE pid BIGINT; aid BIGINT; vid BIGINT;
BEGIN
  INSERT INTO rhrms.person(first_name, last_name) VALUES (first, last) RETURNING id INTO pid;
  INSERT INTO rhrms.application(person_id, animal_id, received_by)
    VALUES (pid, animal_id(pet), 4) RETURNING id INTO aid;
  INSERT INTO rhrms.home_visit(application_id, visited_on, visitor_name, entered_by, children_present, yard,
                               yard_fenced, elderly_residents, adult_men, adult_women, housing_confirmed,
                               recommendation, notes)
    VALUES (aid, CURRENT_DATE, 'Sam', 4, FALSE, TRUE, TRUE, FALSE, 1, 1, TRUE, 'APPROVE',
            'Quiet home, fenced yard, no children; good fit.') RETURNING id INTO vid;
  INSERT INTO rhrms.decision(application_id, outcome, rationale, home_visit_id, decided_by)
    VALUES (aid, 'APPROVE', 'Good fit after home visit; approved by director.', vid, 1);
  RETURN aid;
END $$;

-- helpers must find rhrms tables no matter what search_path the caller has
DO $$ DECLARE f regprocedure; BEGIN
  FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
           WHERE n.nspname = 'rhrms_test' LOOP
    EXECUTE format('ALTER FUNCTION %s SET search_path = rhrms_test, rhrms', f);
  END LOOP; END $$;

GRANT USAGE ON SCHEMA rhrms_test TO rhrms_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rhrms_test TO rhrms_app;
