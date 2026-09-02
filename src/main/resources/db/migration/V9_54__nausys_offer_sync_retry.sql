-- Durable retry queue for the nightly NauSys offer sync (429 plan P5, 2.9.2026).
-- One row per (agency, week interval) whose getFreeYachts call failed (429 budget
-- exhausted, 5xx, parse error). Until now such a failure aborted the WHOLE agency
-- for the night (all remaining intervals skipped, prices stale 24 h+; agency 132
-- Dream Yacht Charter = 692 yachts). Rows are drained at the end of the nightly run
-- and in the 06:15 / 10:15 / 15:15 NauSys backup slots, deleted on success and
-- given up after 6 attempts. A table (not an in-memory list) so a scheduler
-- restart mid-night (unattended-upgrades) does not lose the backlog.

CREATE TABLE IF NOT EXISTS nausys_offer_sync_retry (
    id                 BIGSERIAL    PRIMARY KEY,
    agency_id          BIGINT       NOT NULL REFERENCES agency (id) ON DELETE CASCADE,
    period_from        DATE         NOT NULL,
    period_to          DATE         NOT NULL,
    -- NauSys yacht ids of the reservation-options group, comma separated.
    yacht_external_ids TEXT         NOT NULL,
    skip_disappearance BOOLEAN      NOT NULL DEFAULT false,
    attempts           INT          NOT NULL DEFAULT 0,
    last_error         VARCHAR(500),
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    next_attempt_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_nausys_offer_sync_retry UNIQUE (agency_id, period_from, period_to)
);

CREATE INDEX IF NOT EXISTS idx_nausys_offer_sync_retry_next ON nausys_offer_sync_retry (next_attempt_at);
