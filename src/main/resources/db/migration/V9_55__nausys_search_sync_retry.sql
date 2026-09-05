-- Durable retry queue for the on-demand NauSys SEARCH warm (Mario, 5.9.2026: search is
-- served from the DB — the nightly offer grid + the scheduler's near-term refresh keep it
-- fresh; the API node must not hammer NauSys itself). Weekly ranges (7/14/21/28 d) no
-- longer warm at all; a NON-weekly dated search still fires one live freeYachtsSearch
-- from the API node (cusma2), and when that call fails with 429 / 5xx / timeout the
-- (dates, NauSys country/region/location filter) request is parked here instead of
-- being retried in the request path (681 of 1,362 warms/day failed and were only
-- logged). Rows are drained ONLY on the scheduler (cusma3) every 15 min, deleted on
-- success and given up after 6 attempts (15 min × attempts back-off).
--
-- The three filter columns are NOT NULL DEFAULT '' on purpose: Postgres treats NULLs as
-- distinct inside a UNIQUE constraint, so a nullable "no filter" would create a new row on
-- every failure instead of bumping `attempts`. '' is read back as null (= no filter), so
-- the replayed request is identical to the original one. TIMESTAMP without time zone =
-- same JVM-bound Instant convention as V9_54.

CREATE TABLE IF NOT EXISTS nausys_search_sync_retry (
    id              BIGSERIAL    PRIMARY KEY,
    period_from     DATE         NOT NULL,
    period_to       DATE         NOT NULL,
    -- NauSys external ids, sorted, comma separated; '' = no filter on that dimension.
    countries       TEXT         NOT NULL DEFAULT '',
    regions         TEXT         NOT NULL DEFAULT '',
    locations       TEXT         NOT NULL DEFAULT '',
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_nausys_search_sync_retry UNIQUE (period_from, period_to, countries, regions, locations)
);

CREATE INDEX IF NOT EXISTS idx_nausys_search_sync_retry_next ON nausys_search_sync_retry (next_attempt_at);
