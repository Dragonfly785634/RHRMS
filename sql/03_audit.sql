-- =====================================================================
-- RHRMS  Stage 1 · 03_audit.sql
-- L4 "It explains itself": every insert/update on every business table is
-- copied into audit_log with WHO, WHEN, OLD row, NEW row and WHY.
--
-- How the client tells the database who is acting (once per transaction):
--     BEGIN;
--     SET LOCAL rhrms.user_id      = '3';          -- staff_user.id (required)
--     SET LOCAL rhrms.reason       = 'Intake typo'; -- optional, required by some screens
--     SET LOCAL rhrms.on_behalf_of = 'Sam R.';      -- optional: volunteer without a login
--     ... changes ...
--     COMMIT;
-- =====================================================================
SET search_path = rhrms;

-- SECURITY DEFINER: runs with the owner's rights, so the app role can make
-- audit rows ONLY through this trigger and never write audit_log directly.
CREATE FUNCTION audit_trigger() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = rhrms AS $$
DECLARE
  uid      TEXT := NULLIF(current_setting('rhrms.user_id', true), '');
  rid      TEXT;
  r        JSONB;
  old_json JSONB;
  new_json JSONB;
  pw_changed BOOLEAN;
BEGIN
  -- Every change made by the application must say who made it.
  IF uid IS NULL AND session_user <> 'rhrms_owner' THEN
    RAISE EXCEPTION 'Change refused: no user is set for this transaction (SET LOCAL rhrms.user_id).'
      USING ERRCODE = 'insufficient_privilege', HINT = 'RULE L4';
  END IF;

  -- read the row as JSON so one function works for every table's shape
  old_json := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END;
  new_json := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END;

  -- Never copy password material into the audit log. audit_log is readable by every
  -- application role (the History screen), so a stored hash there would undo the point of
  -- keeping staff_user.password_hash unreadable. The marker records THAT it changed.
  IF TG_TABLE_NAME = 'staff_user' THEN
    -- Work out whether the hash changed BEFORE redacting either copy. Compare through the
    -- JSON rather than OLD.password_hash: on an INSERT there is no OLD record to read.
    pw_changed := new_json IS NOT NULL
                  AND (old_json ->> 'password_hash') IS DISTINCT FROM (new_json ->> 'password_hash');
    IF old_json IS NOT NULL THEN
      old_json := jsonb_set(old_json, '{password_hash}', '"(not recorded)"');
    END IF;
    IF new_json IS NOT NULL THEN
      new_json := jsonb_set(new_json, '{password_hash}',
        to_jsonb(CASE WHEN pw_changed THEN '(set or changed, not recorded)'
                      ELSE '(unchanged, not recorded)' END));
    END IF;
  END IF;

  r := coalesce(new_json, old_json);
  rid := CASE TG_TABLE_NAME
           WHEN 'stock_level' THEN (r->>'food_product_id') || ':' || (r->>'location_id')
           WHEN 'setting'     THEN r->>'key'
           ELSE r->>'id'
         END;

  INSERT INTO audit_log(user_id, on_behalf_of, action, table_name, row_id, old_row, new_row, reason)
  VALUES (uid::BIGINT,
          NULLIF(current_setting('rhrms.on_behalf_of', true), ''),
          TG_OP, TG_TABLE_NAME, rid,
          old_json, new_json,
          NULLIF(current_setting('rhrms.reason', true), ''));
  RETURN NULL;   -- AFTER trigger
END $$;

-- AU-2: records are never deleted. Removal is a status change with a reason.
CREATE FUNCTION forbid_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Records are never deleted in RHRMS (table %). Change the status instead.', TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege', HINT = 'RULE AU-2';
END $$;

-- Attach both triggers to every business table.
DO $$
DECLARE t TEXT;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables
           WHERE schemaname = 'rhrms' AND tablename NOT IN ('audit_log', 'auth_event')
           ORDER BY tablename
  LOOP
    EXECUTE format('CREATE TRIGGER zz_audit AFTER INSERT OR UPDATE OR DELETE ON rhrms.%I
                    FOR EACH ROW EXECUTE FUNCTION rhrms.audit_trigger()', t);
    EXECUTE format('CREATE TRIGGER no_delete BEFORE DELETE ON rhrms.%I
                    FOR EACH ROW EXECUTE FUNCTION rhrms.forbid_delete()', t);
  END LOOP;
END $$;

-- The audit log itself is untouchable, even by the owner's own mistakes.
CREATE FUNCTION forbid_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'The audit log cannot be changed or deleted.'
    USING ERRCODE = 'insufficient_privilege', HINT = 'RULE AU-2';
END $$;
CREATE TRIGGER audit_log_is_append_only BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();

-- The login trail has the same rule: you may add to it, never rewrite it.
CREATE TRIGGER auth_event_is_append_only BEFORE UPDATE OR DELETE ON auth_event
  FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();

-- =====================================================================
-- Privileges for the application's login role (rhrms_app)
-- =====================================================================
-- rhrms_app is the broad build-and-test role: the SQL test suite in tests/ and
-- scripts/psql_app.sh log in as this. The API server does NOT use it; the server uses the
-- three narrow roles created in 06_db_roles.sql. See docs/ROLES_AND_PASSWORDS.md.
GRANT USAGE ON SCHEMA rhrms TO rhrms_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA rhrms TO rhrms_app;
REVOKE INSERT, UPDATE ON audit_log FROM rhrms_app;          -- read-only for the History screen
REVOKE UPDATE ON auth_event FROM rhrms_app;                 -- append-only login trail
REVOKE DELETE, TRUNCATE ON ALL TABLES IN SCHEMA rhrms FROM rhrms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA rhrms TO rhrms_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rhrms TO rhrms_app;

-- Password hashes are readable by exactly one role, rhrms_auth (06_db_roles.sql). Column
-- privileges cannot be subtracted from a table-wide GRANT, so staff_user is revoked and then
-- re-granted column by column, leaving password_hash out.
REVOKE ALL ON staff_user FROM rhrms_app;
GRANT SELECT (id, username, display_name, role, active, created_at, version),
      INSERT, UPDATE (username, display_name, role, password_hash, active, version)
  ON staff_user TO rhrms_app;

-- =====================================================================
-- Read-only views the terminal UI can query directly
-- =====================================================================

-- FD-1..FD-4  Food forecast: daily use, days of supply, reorder status
CREATE VIEW food_forecast AS
WITH use AS (
  SELECT d.food_product_id, sum(d.cups_per_meal * d.meals_per_day * p.lbs_per_cup) AS lbs_per_day,
         string_agg(coalesce(a.name, a.animal_code), ', ' ORDER BY a.name) AS eaten_by
  FROM diet d
  JOIN food_product p ON p.id = d.food_product_id
  JOIN animal a       ON a.id = d.animal_id
  JOIN stay s         ON s.animal_id = a.id AND s.ended_on IS NULL
  WHERE d.valid_to IS NULL
  GROUP BY d.food_product_id
), stock AS (
  SELECT p.id AS food_product_id,
         coalesce(sum(l.sealed_bags), 0)            AS sealed_bags,
         coalesce(sum(l.sealed_bags), 0) * p.bag_lbs AS sealed_lbs
  FROM food_product p LEFT JOIN stock_level l ON l.food_product_id = p.id
  GROUP BY p.id, p.bag_lbs
), open_left AS (
  SELECT o.food_product_id,
         greatest(0, p.bag_lbs - coalesce(u.lbs_per_day, 0) *
                  extract(epoch FROM now() - o.opened_at) / 86400.0) AS lbs
  FROM open_bag o
  JOIN food_product p ON p.id = o.food_product_id
  LEFT JOIN use u     ON u.food_product_id = o.food_product_id
  WHERE o.finished_at IS NULL
)
SELECT p.id, p.name, p.kind, p.lead_time_days,
       s.sealed_bags,
       round(s.sealed_lbs + coalesce(ol.lbs, 0), 1)                       AS lbs_on_hand,
       round(coalesce(u.lbs_per_day, 0), 2)                                 AS lbs_per_day,
       CASE WHEN coalesce(u.lbs_per_day, 0) = 0 THEN NULL
            ELSE round((s.sealed_lbs + coalesce(ol.lbs, 0)) / u.lbs_per_day, 1) END AS days_of_supply,
       u.eaten_by,
       o.status AS open_order_status, o.expected_on,
       CASE
         WHEN coalesce(u.lbs_per_day, 0) = 0 THEN 'NO CURRENT USE'
         WHEN o.status IS NOT NULL AND (o.expected_on IS NULL OR o.expected_on >= CURRENT_DATE) THEN 'ON ORDER'
         WHEN (s.sealed_lbs + coalesce(ol.lbs, 0)) / u.lbs_per_day
              <= p.lead_time_days + (SELECT value::INT FROM setting WHERE key = 'food.buffer_days') THEN 'REORDER NOW'
         WHEN (s.sealed_lbs + coalesce(ol.lbs, 0)) / u.lbs_per_day
              <= p.lead_time_days + (SELECT value::INT FROM setting WHERE key = 'food.buffer_days') + 7 THEN 'REORDER SOON'
         ELSE 'OK'
       END AS food_status
FROM food_product p
JOIN stock s          ON s.food_product_id = p.id
LEFT JOIN use u       ON u.food_product_id = p.id
LEFT JOIN open_left ol ON ol.food_product_id = p.id
LEFT JOIN food_order o ON o.food_product_id = p.id AND o.status IN ('REQUESTED','ORDERED')
WHERE p.active;

-- Kennel board for the dashboard (1..40)
CREATE VIEW kennel_board AS
SELECT k.id AS kennel, k.in_service,
       string_agg(coalesce(a.name, a.animal_code) || ' (' || a.animal_code || ')', ', ' ORDER BY a.id) AS occupants,
       count(a.id) AS animals
FROM kennel k LEFT JOIN animal a ON a.kennel_id = k.id
GROUP BY k.id, k.in_service
ORDER BY k.id;

-- DE-2 input: restriction conflicts between an animal and each home visit
CREATE VIEW restriction_conflicts AS
SELECT hv.application_id, hv.id AS home_visit_id, r.animal_id, r.type,
       CASE r.type
         WHEN 'NO_CHILDREN'      THEN 'Children in home (youngest ' || hv.youngest_child_age || ')'
         WHEN 'NO_ADULT_MEN'     THEN hv.adult_men || ' adult man/men in home'
         WHEN 'NO_ADULT_WOMEN'   THEN hv.adult_women || ' adult woman/women in home'
         WHEN 'NO_ELDERLY'       THEN 'Elderly resident in home'
         WHEN 'NEEDS_FENCED_YARD' THEN 'No fenced yard'
         WHEN 'NO_OTHER_DOGS'    THEN 'Other pets: ' || hv.other_pets
         WHEN 'NO_CATS'          THEN 'Other pets: ' || hv.other_pets
         ELSE 'Check: ' || r.detail
       END AS conflict
FROM home_visit hv
JOIN application ap ON ap.id = hv.application_id
JOIN animal an      ON an.id = ap.animal_id
JOIN restriction r  ON r.active
                   AND (r.animal_id = an.id
                        OR (an.bond_group_id IS NOT NULL AND r.animal_id IN
                            (SELECT id FROM animal WHERE bond_group_id = an.bond_group_id)))
WHERE (r.type = 'NO_CHILDREN'       AND hv.children_present
                                    AND (r.min_child_age IS NULL OR hv.youngest_child_age < r.min_child_age))
   OR (r.type = 'NO_ADULT_MEN'      AND hv.adult_men   > 0)
   OR (r.type = 'NO_ADULT_WOMEN'    AND hv.adult_women > 0)
   OR (r.type = 'NO_ELDERLY'        AND hv.elderly_residents)
   OR (r.type = 'NEEDS_FENCED_YARD' AND NOT (hv.yard AND coalesce(hv.yard_fenced, false)))
   OR (r.type = 'NO_OTHER_DOGS'     AND hv.other_pets ~* 'dog|puppy')
   OR (r.type = 'NO_CATS'           AND hv.other_pets ~* 'cat|kitten')
   OR (r.type = 'OTHER');

-- LK-2  One row per decision with everything the director needs to defend it
CREATE VIEW decision_record AS
SELECT d.id AS decision_id, d.decided_at, d.outcome, d.denial_reason, d.rationale,
       d.conflicts_snapshot, d.conflicts_acknowledgement, d.supersedes_decision_id,
       du.display_name AS decided_by,
       ap.id AS application_id, ap.status AS application_status, ap.submitted_at,
       pe.first_name || ' ' || pe.last_name AS applicant, pe.phone,
       an.animal_code, an.name AS animal_name,
       hv.visited_on, hv.visitor_name, eu.display_name AS visit_entered_by,
       hv.recommendation AS visitor_recommendation, hv.notes AS visitor_notes
FROM decision d
JOIN application ap   ON ap.id = d.application_id
JOIN person pe        ON pe.id = ap.person_id
JOIN animal an        ON an.id = ap.animal_id
JOIN staff_user du    ON du.id = d.decided_by
LEFT JOIN home_visit hv ON hv.id = coalesce(d.home_visit_id, ap.reused_home_visit_id)
LEFT JOIN staff_user eu ON eu.id = hv.entered_by;

GRANT SELECT ON food_forecast, kennel_board, restriction_conflicts, decision_record TO rhrms_app;
