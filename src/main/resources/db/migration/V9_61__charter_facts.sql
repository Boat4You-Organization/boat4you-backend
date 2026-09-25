-- Charter facts for landing pages (25.9.2026): one precomputed row per destination (`did` c-/r-/l-) and boat type
-- (vessel_type NULL = all types), written nightly by CharterFactsJob on the scheduler node (cusma3). The public
-- endpoint GET /public/charter-facts only reads one row by this key, so the API node (cusma2, OOM history) never
-- aggregates offers per request. payload = the JSON the landing page renders (weekly price by month, availability,
-- skipper / obligatory extras / deposit, check-in days, build year, top models / bases, boat type mix).
-- The job replaces every row in one transaction, so readers always see a complete snapshot (old or new).
-- Idempotent; a new empty table, so no lock contention.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS charter_facts (
    did          VARCHAR(24) NOT NULL,
    vessel_type  VARCHAR(31),
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload      JSONB       NOT NULL
);

-- The key: one row per (did, type); NULL type = all types. The read path filters on exactly this expression.
CREATE UNIQUE INDEX IF NOT EXISTS charter_facts_did_type_uidx ON charter_facts (did, COALESCE(vessel_type, ''));
