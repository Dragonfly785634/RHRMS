-- =====================================================================
-- RHRMS  Stage 1 · 04_seed.sql
-- Reference data every installation needs. No people, no animals.
-- =====================================================================
SET search_path = rhrms;

-- 40 numbered kennels, all the same size (Interviews 3 and 4)
INSERT INTO kennel(id) SELECT g FROM generate_series(1, 40) AS g;

INSERT INTO stock_location(name) VALUES ('Shelter'), ('Garage overflow');

-- Values the interviews left uncertain live here, not in code (DC-6)
INSERT INTO setting(key, value) VALUES
  ('kennel.count',                         '40'),
  ('fee.baseline',                         '250.00'),
  ('decision.allowed_roles',               'DIRECTOR'),
  ('food.buffer_days',                     '5'),
  ('food.default_lead_days.regular',       '4'),
  ('food.default_lead_days.prescription',  '10'),
  ('food.default_lbs_per_cup',             '0.25'),
  ('visit.reuse_max_months',               '12'),
  ('placement.require_vetted',             'true'),
  ('session.idle_minutes',                 '15'),
  -- Stage 2: guesses at how hard to make repeated wrong passwords. Five tries then a short
  -- pause is enough to stop somebody trying the front desk keyboard, without locking Diane
  -- out for the afternoon because caps lock was on. Logged in DECISIONS.md (D-06).
  ('auth.max_failed_logins',               '5'),
  ('auth.lockout_minutes',                 '5');
