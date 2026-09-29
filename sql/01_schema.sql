-- =====================================================================
-- RHRMS  Stage 1 · 01_schema.sql
-- Tables, keys, CHECK constraints and partial unique indexes.
-- Runs as rhrms_owner inside schema "rhrms".
-- Rule IDs in comments refer to RHRMS_BUILD_SPEC.md §6.
-- =====================================================================
SET search_path = rhrms;

-- ---------- security / reference ----------
CREATE TABLE staff_user (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(40)  NOT NULL UNIQUE,
  display_name  VARCHAR(80)  NOT NULL,
  role          VARCHAR(12)  NOT NULL CHECK (role IN ('DIRECTOR','STAFF','VOLUNTEER')),
  password_hash VARCHAR(100) NOT NULL,
  active        BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  version       INT          NOT NULL DEFAULT 0
);

CREATE TABLE setting (
  key        VARCHAR(60) PRIMARY KEY,
  value      TEXT        NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version    INT         NOT NULL DEFAULT 0
);

CREATE TABLE kennel (
  id                    SMALLINT PRIMARY KEY CHECK (id BETWEEN 1 AND 99),
  in_service            BOOLEAN NOT NULL DEFAULT TRUE,
  out_of_service_reason TEXT,
  version               INT NOT NULL DEFAULT 0,
  CHECK (in_service OR out_of_service_reason IS NOT NULL)
);

-- ---------- animals ----------
CREATE TABLE bond_group (
  id         BIGSERIAL PRIMARY KEY,
  note       TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE litter (
  id               BIGSERIAL PRIMARY KEY,
  mother_animal_id BIGINT,
  born_on          DATE,
  note             TEXT
);

CREATE SEQUENCE animal_code_seq START 1;

CREATE TABLE animal (
  id                     BIGSERIAL PRIMARY KEY,
  animal_code            VARCHAR(12) NOT NULL UNIQUE
                         DEFAULT ('RH-' || lpad(nextval('animal_code_seq')::text, 6, '0')),   -- IN-5
  name                   VARCHAR(60),                                   -- nullable: unnamed on arrival
  species                VARCHAR(16) NOT NULL
                         CHECK (species IN ('DOG','CAT','FERRET','SMALL_MAMMAL','OTHER')),
  breed                  VARCHAR(60),                                   -- e.g. 'Domestic Short Hair'
  sex                    CHAR(1) CHECK (sex IN ('M','F','U')),
  birth_date             DATE,
  birth_date_is_estimate BOOLEAN NOT NULL DEFAULT TRUE,                 -- IN-8
  color_markings         VARCHAR(120),
  status                 VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
                         CHECK (status IN ('AVAILABLE','NOT_ADOPTABLE','PENDING_ADOPTION','ADOPTED','DECEASED')),
  not_adoptable_reason   TEXT,
  vet_status             VARCHAR(12) NOT NULL DEFAULT 'NOT_VETTED' CHECK (vet_status IN ('NOT_VETTED','VETTED')),
  spay_neuter            VARCHAR(8)  NOT NULL DEFAULT 'UNKNOWN'    CHECK (spay_neuter IN ('INTACT','ALTERED','UNKNOWN')),
  health_notes           TEXT,
  behavior_notes         TEXT,
  general_notes          TEXT,
  kennel_id              SMALLINT REFERENCES kennel(id),
  bond_group_id          BIGINT REFERENCES bond_group(id),
  litter_id              BIGINT REFERENCES litter(id),
  needs_split            BOOLEAN NOT NULL DEFAULT FALSE,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  version                INT NOT NULL DEFAULT 0,
  CONSTRAINT not_adoptable_needs_reason CHECK (status <> 'NOT_ADOPTABLE' OR not_adoptable_reason IS NOT NULL),
  CONSTRAINT gone_animals_have_no_kennel CHECK (status NOT IN ('ADOPTED','DECEASED') OR kennel_id IS NULL)
);
ALTER TABLE litter ADD FOREIGN KEY (mother_animal_id) REFERENCES animal(id);

CREATE TABLE animal_name_history (
  id         BIGSERIAL PRIMARY KEY,
  animal_id  BIGINT NOT NULL REFERENCES animal(id),
  name       VARCHAR(60) NOT NULL,
  valid_from TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE restriction (
  id            BIGSERIAL PRIMARY KEY,
  animal_id     BIGINT NOT NULL REFERENCES animal(id),
  type          VARCHAR(20) NOT NULL CHECK (type IN ('NO_CHILDREN','NO_ADULT_MEN','NO_ADULT_WOMEN',
                  'NO_OTHER_DOGS','NO_CATS','NO_ELDERLY','NEEDS_FENCED_YARD','OTHER')),
  min_child_age SMALLINT CHECK (min_child_age IS NULL OR min_child_age BETWEEN 1 AND 17),
  detail        TEXT,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  version       INT NOT NULL DEFAULT 0,
  CHECK (type <> 'OTHER' OR detail IS NOT NULL)
);

-- ---------- people ----------
CREATE TABLE person (
  id          BIGSERIAL PRIMARY KEY,
  first_name  VARCHAR(60) NOT NULL CHECK (length(trim(first_name)) > 0),
  last_name   VARCHAR(60) NOT NULL CHECK (length(trim(last_name))  > 0),
  phone       VARCHAR(25) CHECK (phone IS NULL OR phone ~ '^\+?[0-9 ()\-]{7,24}$'),  -- string by design
  email       VARCHAR(120),
  address     VARCHAR(200),
  city        VARCHAR(60),
  state       VARCHAR(30),
  postal_code VARCHAR(12),
  notes       TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  version     INT NOT NULL DEFAULT 0
);

-- ---------- stays (intake) ----------
CREATE TABLE stay (
  id                 BIGSERIAL PRIMARY KEY,
  animal_id          BIGINT NOT NULL REFERENCES animal(id),
  intake_date        DATE NOT NULL,
  -- "TIME IN" on form RH-1. Nullable: the paper form is often filled in later in the day and
  -- the box left blank, and a guessed time is worse than no time.
  intake_time        TIME,
  intake_source      VARCHAR(16) NOT NULL
                     CHECK (intake_source IN ('OWNER_SURRENDER','STRAY','CITY_SHELTER','BORN_IN_CARE','RETURN')),
  surrendered_by     BIGINT REFERENCES person(id),
  prior_placement_id BIGINT,
  intake_notes       TEXT,
  received_by        BIGINT NOT NULL REFERENCES staff_user(id),
  ended_on           DATE,
  end_reason         VARCHAR(12) CHECK (end_reason IN ('PLACED','DECEASED','TRANSFERRED')),
  CHECK ((ended_on IS NULL) = (end_reason IS NULL)),
  CHECK (intake_source <> 'RETURN' OR prior_placement_id IS NOT NULL)
);
CREATE UNIQUE INDEX one_open_stay_per_animal ON stay(animal_id) WHERE ended_on IS NULL;

-- ---------- adoption ----------
CREATE TABLE application (
  id                   BIGSERIAL PRIMARY KEY,
  person_id            BIGINT NOT NULL REFERENCES person(id),
  animal_id            BIGINT NOT NULL REFERENCES animal(id),
  status               VARCHAR(12) NOT NULL DEFAULT 'SUBMITTED'
                       CHECK (status IN ('SUBMITTED','UNDER_REVIEW','APPROVED','DENIED','PLACED','WITHDRAWN','CLOSED')),
  submitted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  received_by          BIGINT NOT NULL REFERENCES staff_user(id),
  housing_type         VARCHAR(12) CHECK (housing_type IN ('HOUSE','APARTMENT','OTHER')),
  landlord_allows_pets BOOLEAN,
  has_yard             BOOLEAN,
  yard_fenced          BOOLEAN,
  children_count       SMALLINT CHECK (children_count >= 0),
  youngest_child_age   SMALLINT CHECK (youngest_child_age BETWEEN 0 AND 17),
  elderly_in_home      BOOLEAN,
  adults_in_home       VARCHAR(80),
  other_pets           TEXT,
  reason_for_adopting  TEXT,
  prior_application_id BIGINT REFERENCES application(id),
  reused_home_visit_id BIGINT,
  situation_check_note TEXT,
  closed_reason        TEXT,
  version              INT NOT NULL DEFAULT 0,
  CHECK (status NOT IN ('CLOSED','WITHDRAWN') OR closed_reason IS NOT NULL),
  CHECK (reused_home_visit_id IS NULL OR situation_check_note IS NOT NULL)
);
-- L3: at most one approved application per animal
CREATE UNIQUE INDEX one_approved_per_animal ON application(animal_id) WHERE status = 'APPROVED';
-- AP-3: no duplicate open application for the same person + animal
CREATE UNIQUE INDEX no_duplicate_open_application ON application(person_id, animal_id)
  WHERE status IN ('SUBMITTED','UNDER_REVIEW','APPROVED');

CREATE TABLE home_visit (
  id                 BIGSERIAL PRIMARY KEY,
  application_id     BIGINT NOT NULL REFERENCES application(id),
  visited_on         DATE NOT NULL,
  visitor_user_id    BIGINT REFERENCES staff_user(id),
  visitor_name       VARCHAR(80) NOT NULL CHECK (length(trim(visitor_name)) > 0),
  entered_by         BIGINT NOT NULL REFERENCES staff_user(id),
  children_present   BOOLEAN NOT NULL,
  youngest_child_age SMALLINT CHECK (youngest_child_age BETWEEN 0 AND 17),
  yard               BOOLEAN NOT NULL,
  yard_fenced        BOOLEAN,
  other_pets         TEXT,
  elderly_residents  BOOLEAN NOT NULL,
  adult_men          SMALLINT NOT NULL CHECK (adult_men >= 0),
  adult_women        SMALLINT NOT NULL CHECK (adult_women >= 0),
  housing_confirmed  BOOLEAN NOT NULL,
  other_concerns     TEXT,
  recommendation     VARCHAR(8) NOT NULL CHECK (recommendation IN ('APPROVE','DENY','UNSURE')),
  notes              TEXT NOT NULL CHECK (length(trim(notes)) >= 15),   -- visitor's reasoning, own words
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (NOT children_present OR youngest_child_age IS NOT NULL)
);
ALTER TABLE application ADD FOREIGN KEY (reused_home_visit_id) REFERENCES home_visit(id);

CREATE TABLE decision (
  id                        BIGSERIAL PRIMARY KEY,
  application_id            BIGINT NOT NULL REFERENCES application(id),
  outcome                   VARCHAR(8) NOT NULL CHECK (outcome IN ('APPROVE','DENY')),
  denial_reason             VARCHAR(24) CHECK (denial_reason IN ('HOUSING_NO_PETS','CHILDREN_UNSUITABLE',
                              'YARD_UNSUITABLE','OTHER_PETS_UNSUITABLE','HOUSEHOLD_UNSUITABLE',
                              'BONDED_PAIR_NOT_TAKEN','BETTER_FIT_CHOSEN','APPLICATION_MISMATCH',
                              'WELFARE_CONCERN','OTHER')),
  rationale                 TEXT NOT NULL CHECK (length(trim(rationale)) >= 15),
  conflicts_snapshot        TEXT,
  conflicts_acknowledgement TEXT,
  home_visit_id             BIGINT REFERENCES home_visit(id),
  decided_by                BIGINT NOT NULL REFERENCES staff_user(id),
  decided_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  supersedes_decision_id    BIGINT REFERENCES decision(id),
  CONSTRAINT denial_needs_reason   CHECK (outcome = 'APPROVE' OR denial_reason IS NOT NULL),
  CONSTRAINT approval_has_no_denial_reason CHECK (outcome = 'DENY' OR denial_reason IS NULL),
  CONSTRAINT conflicts_need_acknowledgement CHECK (      -- DE-3 (liability)
      -- coalesce matters: a CHECK that evaluates to NULL counts as passing
      outcome = 'DENY' OR conflicts_snapshot IS NULL OR coalesce(length(trim(conflicts_acknowledgement)), 0) >= 15)
);

CREATE TABLE placement (
  id                 BIGSERIAL PRIMARY KEY,
  animal_id          BIGINT NOT NULL REFERENCES animal(id),
  application_id     BIGINT NOT NULL REFERENCES application(id),
  person_id          BIGINT NOT NULL REFERENCES person(id),
  placed_on          DATE NOT NULL,
  fee_baseline       NUMERIC(8,2) NOT NULL CHECK (fee_baseline >= 0),
  fee_charged        NUMERIC(8,2) NOT NULL CHECK (fee_charged  >= 0),
  fee_reason         TEXT,
  payment_method     VARCHAR(8) NOT NULL CHECK (payment_method IN ('CASH','CHECK','OTHER','WAIVED')),
  vet_clinic_told    VARCHAR(120),
  recorded_by        BIGINT NOT NULL REFERENCES staff_user(id),
  returned_on        DATE,
  return_reason      TEXT,
  return_recorded_by BIGINT REFERENCES staff_user(id),
  CONSTRAINT fee_change_needs_reason CHECK (fee_charged = fee_baseline OR fee_reason IS NOT NULL),     -- PL-2
  CONSTRAINT waived_means_zero       CHECK ((payment_method = 'WAIVED') = (fee_charged = 0)),
  CONSTRAINT return_needs_reason     CHECK (returned_on IS NULL OR (return_reason IS NOT NULL AND return_recorded_by IS NOT NULL)),
  CHECK (returned_on IS NULL OR returned_on >= placed_on)
);
-- L3: an animal can only be in one home at a time
CREATE UNIQUE INDEX one_active_placement_per_animal ON placement(animal_id) WHERE returned_on IS NULL;
ALTER TABLE stay ADD FOREIGN KEY (prior_placement_id) REFERENCES placement(id);

CREATE TABLE vet_visit (
  id          BIGSERIAL PRIMARY KEY,
  animal_id   BIGINT NOT NULL REFERENCES animal(id),
  visit_date  DATE NOT NULL,
  clinic      VARCHAR(120) NOT NULL,
  reason      VARCHAR(120) NOT NULL,
  outcome     TEXT,
  cost        NUMERIC(8,2) CHECK (cost >= 0),
  report_path TEXT,
  recorded_by BIGINT NOT NULL REFERENCES staff_user(id)
);

-- ---------- food ----------
CREATE TABLE food_product (
  id             BIGSERIAL PRIMARY KEY,
  name           VARCHAR(100) NOT NULL UNIQUE,
  kind           VARCHAR(12)  NOT NULL CHECK (kind IN ('REGULAR','PRESCRIPTION')),  -- the TYPE
  species        VARCHAR(16),
  -- The UNIT stock is counted in. Everything the rescue buys arrives as a countable thing:
  -- a bag of kibble, a case of tins, a tub. stock_level.sealed_bags is the QUANTITY of these.
  unit           VARCHAR(20)  NOT NULL DEFAULT 'bag' CHECK (length(trim(unit)) > 0),
  bag_lbs        NUMERIC(5,1) NOT NULL DEFAULT 40   CHECK (bag_lbs > 0),   -- lbs in one unit
  lbs_per_cup    NUMERIC(4,3) NOT NULL DEFAULT 0.25 CHECK (lbs_per_cup > 0),
  lead_time_days SMALLINT     NOT NULL CHECK (lead_time_days >= 0),
  active         BOOLEAN      NOT NULL DEFAULT TRUE,
  version        INT          NOT NULL DEFAULT 0
);

CREATE TABLE prescription (
  id              BIGSERIAL PRIMARY KEY,
  food_product_id BIGINT NOT NULL REFERENCES food_product(id),
  animal_id       BIGINT NOT NULL REFERENCES animal(id),
  start_date      DATE NOT NULL,
  end_date        DATE,
  CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE TABLE diet (
  id              BIGSERIAL PRIMARY KEY,
  animal_id       BIGINT NOT NULL REFERENCES animal(id),
  food_product_id BIGINT NOT NULL REFERENCES food_product(id),
  cups_per_meal   NUMERIC(3,2) NOT NULL CHECK (cups_per_meal > 0 AND cups_per_meal <= 4),
  meals_per_day   SMALLINT     NOT NULL DEFAULT 2 CHECK (meals_per_day BETWEEN 1 AND 4),
  valid_from      DATE NOT NULL DEFAULT CURRENT_DATE,
  valid_to        DATE,
  CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE UNIQUE INDEX one_active_diet_per_food ON diet(animal_id, food_product_id) WHERE valid_to IS NULL;

CREATE TABLE stock_location (
  id   SMALLSERIAL PRIMARY KEY,
  name VARCHAR(40) NOT NULL UNIQUE
);

CREATE TABLE stock_level (
  food_product_id BIGINT   NOT NULL REFERENCES food_product(id),
  location_id     SMALLINT NOT NULL REFERENCES stock_location(id),
  sealed_bags     INT      NOT NULL DEFAULT 0,
  version         INT      NOT NULL DEFAULT 0,
  PRIMARY KEY (food_product_id, location_id),
  CONSTRAINT stock_never_negative CHECK (sealed_bags >= 0)             -- L2
);

CREATE TABLE open_bag (
  id              BIGSERIAL PRIMARY KEY,
  food_product_id BIGINT NOT NULL REFERENCES food_product(id),
  opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX one_open_bag_per_product ON open_bag(food_product_id) WHERE finished_at IS NULL;

CREATE TABLE donation (
  id          BIGSERIAL PRIMARY KEY,
  received_on DATE NOT NULL,
  donor_name  VARCHAR(120),
  kind        VARCHAR(8) NOT NULL CHECK (kind IN ('MONEY','FOOD','OTHER')),
  amount      NUMERIC(10,2) CHECK (amount >= 0),
  description TEXT,
  recorded_by BIGINT NOT NULL REFERENCES staff_user(id),
  CHECK (kind <> 'MONEY' OR amount IS NOT NULL)
);

CREATE TABLE food_order (
  id              BIGSERIAL PRIMARY KEY,
  food_product_id BIGINT NOT NULL REFERENCES food_product(id),
  bags            INT NOT NULL CHECK (bags > 0),
  status          VARCHAR(10) NOT NULL DEFAULT 'REQUESTED'
                  CHECK (status IN ('REQUESTED','ORDERED','RECEIVED','CANCELLED')),
  requested_by    BIGINT NOT NULL REFERENCES staff_user(id),
  requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  ordered_by      BIGINT REFERENCES staff_user(id),
  ordered_at      TIMESTAMPTZ,
  expected_on     DATE,
  received_at     TIMESTAMPTZ,
  cost            NUMERIC(8,2) CHECK (cost >= 0),
  note            TEXT,
  version         INT NOT NULL DEFAULT 0,
  CHECK (status NOT IN ('ORDERED','RECEIVED') OR (ordered_by IS NOT NULL AND ordered_at IS NOT NULL)),
  CHECK (status <> 'RECEIVED' OR received_at IS NOT NULL)
);
CREATE UNIQUE INDEX one_open_order_per_product ON food_order(food_product_id)
  WHERE status IN ('REQUESTED','ORDERED');

CREATE TABLE stock_movement (
  id              BIGSERIAL PRIMARY KEY,
  food_product_id BIGINT   NOT NULL REFERENCES food_product(id),
  location_id     SMALLINT NOT NULL REFERENCES stock_location(id),
  type            VARCHAR(16) NOT NULL CHECK (type IN ('RECEIVE','OPEN','SEND_WITH_ANIMAL',
                    'TRANSFER_OUT','TRANSFER_IN','WRITE_OFF','COUNT_ADJUST')),
  bags            INT NOT NULL CHECK (bags <> 0),       -- signed: + into sealed stock, - out
  animal_id       BIGINT REFERENCES animal(id),
  donation_id     BIGINT REFERENCES donation(id),
  order_id        BIGINT REFERENCES food_order(id),
  reason          TEXT,
  recorded_by     BIGINT NOT NULL REFERENCES staff_user(id),
  at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (type NOT IN ('WRITE_OFF','COUNT_ADJUST') OR reason IS NOT NULL),
  CHECK (type <> 'SEND_WITH_ANIMAL' OR animal_id IS NOT NULL),
  CHECK (type NOT IN ('RECEIVE','TRANSFER_IN') OR bags > 0),
  CHECK (type NOT IN ('OPEN','SEND_WITH_ANIMAL','TRANSFER_OUT','WRITE_OFF') OR bags < 0)
);

-- ---------- audit (L4) ----------
CREATE TABLE audit_log (
  id           BIGSERIAL PRIMARY KEY,
  at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  user_id      BIGINT,
  on_behalf_of VARCHAR(80),
  action       VARCHAR(8)  NOT NULL,
  table_name   VARCHAR(40) NOT NULL,
  row_id       TEXT,
  old_row      JSONB,
  new_row      JSONB,
  reason       TEXT
);
CREATE INDEX audit_by_row  ON audit_log(table_name, row_id, at);
CREATE INDEX audit_by_user ON audit_log(user_id, at);

-- ---------- authentication trail (spec 7: "Every login is logged") ----------
-- Separate from audit_log because these rows are about attempts on the system itself, not
-- changes to rescue data, and because a failed login has no staff_user.id to record. It is
-- append-only and deliberately carries NO password material, not even a hash.
CREATE TABLE auth_event (
  id       BIGSERIAL PRIMARY KEY,
  at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  event    VARCHAR(20) NOT NULL CHECK (event IN (
             'LOGIN_OK','LOGIN_FAILED','LOGIN_BLOCKED','LOGIN_INACTIVE','LOGOUT',
             'SESSION_EXPIRED','CONFIRM_OK','CONFIRM_FAILED','PASSWORD_CHANGED',
             'PASSWORD_RESET','ACCOUNT_CREATED','ACCOUNT_CHANGED')),
  username VARCHAR(60),            -- as typed, so a wrong username is visible in the trail
  user_id  BIGINT REFERENCES staff_user(id),   -- NULL when the username did not match anyone
  action   VARCHAR(60),            -- for CONFIRM_*: which critical action was being confirmed
  client   VARCHAR(80),            -- which terminal, e.g. "rhrms-term/127.0.0.1"
  detail   TEXT
);
CREATE INDEX auth_event_recent   ON auth_event (at DESC);
CREATE INDEX auth_event_by_user  ON auth_event (lower(username), at DESC);

-- ---------- helpful lookup indexes ----------
CREATE INDEX animal_name_idx      ON animal (lower(name));
CREATE INDEX name_history_idx     ON animal_name_history (lower(name));
CREATE INDEX person_name_idx      ON person (lower(last_name), lower(first_name));
CREATE INDEX application_animal   ON application (animal_id, status);
CREATE INDEX application_person   ON application (person_id);
CREATE INDEX decision_application ON decision (application_id);
