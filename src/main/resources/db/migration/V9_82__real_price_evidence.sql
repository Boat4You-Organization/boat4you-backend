-- V9_82 (14.10.2026): real-price search rule D1-D7 (Mario 26.9.2026 "only boats with a real MMK/NauSys offer and price",
-- decisions confirmed 14.10.2026) - partner evidence, status-flip log and reverify run log, plus the frozen payment type
-- of a booked extra. Design: real-price-search DESIGN.md section 1.1.
--
--   partner_period_check   probe bookkeeping for BOTH partners, one row per (partner, yacht, exact period): when the
--                          partner last answered for exactly these dates, what it said, NauSys two-day strikes, and
--                          the hide record that the search treats as evidence. MMK strikes stay in mmk_free_week_strike.
--   reverify_run           one row per reverify / D3 job run (counters, LOG or WRITE mode, abort reason, escalations).
--   partner_offer_flip_log every offer status flip written by the reverifiers and D3, in the same statement as the
--                          flip, so a run can be audited and undone exactly (old status / ext_status / price).
--   reservation_extras.payment_type  the payment type an extra was booked with, so a later classifier change never
--                          rewrites the labels of an existing or confirmed booking (D6).
--
-- Additive only: three new tables and one nullable column without default (metadata-only), nothing on offer,
-- offer_extras, yacht_extras or the search matview. Idempotent (IF NOT EXISTS everywhere). Column types follow the real
-- DDL: offer.status VARCHAR(31) (V1_90), offer.ext_status VARCHAR(50) (V1_42), offer.client_price unconstrained
-- NUMERIC (V1_03). No foreign key on yacht: an FK would take SHARE ROW EXCLUSIVE on yacht, which waits behind any
-- open catalogue / offer sync transaction (one @Transactional pass per agency) and could fail the boot on the only API
-- node; the rows are keyed by our own yacht ids and reaped by the retention job, nothing depends on a cascade. The
-- reservation_extras column takes a short ACCESS EXCLUSIVE lock (metadata-only); a lock timeout fails the boot cleanly
-- (the whole migration rolls back) and a plain restart retries it.
-- Rows are purged by RealPriceEvidenceRetentionJob (periods ended > 30 days ago, runs and flips older than 180 days).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS partner_period_check (
    external_system_id SMALLINT    NOT NULL,                  -- 1 MMK, 2 NauSys (ExternalSystemEnum)
    yacht_id           BIGINT      NOT NULL,                  -- our yacht id; no FK on purpose (see the header)
    date_from          DATE        NOT NULL,
    date_to            DATE        NOT NULL,
    priority           SMALLINT    NOT NULL DEFAULT 5,        -- 1 synthetic, 2 stale option, 3 on-demand/booking, 4 off-grid/stale UNAVAILABLE, 5 sample
    last_checked_on    DATE,                                  -- any decided answer (QUOTED_* / EMPTY / UNPRICED / ROUTE_GONE / MINSTAY)
    last_quoted_on     DATE,
    last_result        VARCHAR(16),                           -- QUOTED_FREE | QUOTED_OPTION | QUOTED_OTHER | EMPTY | UNPRICED | ROUTE_GONE | MINSTAY
    first_empty_on     DATE,                                  -- NauSys strikes only; MMK strikes stay in mmk_free_week_strike
    hidden_on          DATE,
    hidden_rows        INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (external_system_id, yacht_id, date_from, date_to)
);
CREATE INDEX IF NOT EXISTS idx_ppc_due    ON partner_period_check (external_system_id, priority, last_checked_on);
CREATE INDEX IF NOT EXISTS idx_ppc_hidden ON partner_period_check (hidden_on) WHERE hidden_on IS NOT NULL;

CREATE TABLE IF NOT EXISTS reverify_run (
    id                   BIGSERIAL PRIMARY KEY,
    job                  VARCHAR(40) NOT NULL,                -- MMK_FREE | MMK_OPTION | NAUSYS_REVERIFY | NAUSYS_D3 | UNDO | ...
    mode                 VARCHAR(8)  NOT NULL,                -- LOG | WRITE
    started_at           TIMESTAMP   NOT NULL,
    ended_at             TIMESTAMP,
    calls INT NOT NULL DEFAULT 0, quoted INT NOT NULL DEFAULT 0, empty INT NOT NULL DEFAULT 0,
    unknown INT NOT NULL DEFAULT 0, upserts INT NOT NULL DEFAULT 0, first_strikes INT NOT NULL DEFAULT 0,
    hidden_periods INT NOT NULL DEFAULT 0, hidden_rows INT NOT NULL DEFAULT 0, would_flip_rows INT NOT NULL DEFAULT 0,
    aborted_reason       VARCHAR(200),
    escalated_agency_ids BIGINT[]
);
CREATE INDEX IF NOT EXISTS idx_reverify_run_started ON reverify_run (started_at);

CREATE TABLE IF NOT EXISTS partner_offer_flip_log (           -- every status flip by B1/B2/D3, for audit and exact undo
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT      REFERENCES reverify_run (id),
    job                VARCHAR(40) NOT NULL,
    reason             VARCHAR(24) NOT NULL,                  -- STEP0_OCCUPANCY | TWO_STRIKE_EMPTY | ROUTE_GONE | UNPRICED_TWO_DAY | UNDO
    external_system_id SMALLINT    NOT NULL,
    offer_id           BIGINT      NOT NULL,                  -- no FK: the retention reaper may delete old offers
    yacht_id           BIGINT      NOT NULL,
    date_from          DATE        NOT NULL,
    date_to            DATE        NOT NULL,
    old_status         VARCHAR(31) NOT NULL,                  -- = offer.status
    old_ext_status     VARCHAR(50),                           -- = offer.ext_status
    old_client_price   NUMERIC,                               -- = offer.client_price (unconstrained, no rounding)
    new_status         VARCHAR(31) NOT NULL,
    first_empty_on     DATE,
    decided_on         DATE        NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_flip_log_run     ON partner_offer_flip_log (run_id);
CREATE INDEX IF NOT EXISTS idx_flip_log_offer   ON partner_offer_flip_log (offer_id);
CREATE INDEX IF NOT EXISTS idx_flip_log_created ON partner_offer_flip_log (created_at);

-- D6: freeze the payment type a booking was made with, so later classifier changes never rewrite history.
-- Small table, written only at booking time; ADD COLUMN without default = metadata-only.
ALTER TABLE reservation_extras ADD COLUMN IF NOT EXISTS payment_type VARCHAR(31);
