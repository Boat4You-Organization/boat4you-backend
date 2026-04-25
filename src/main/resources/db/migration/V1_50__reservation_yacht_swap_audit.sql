-- V1_50: durable record of yacht-swap events detected by ReservationSyncService
-- INK1 writes rows with action='LOGGED_ONLY' when auto-update is disabled;
-- INK2 writes 'AUTO_UPDATED' after reconciling reservation_flow.yacht_id, and
-- 'MANUAL_REVIEW' when the partner yacht has no local mapping yet.

CREATE TABLE reservation_yacht_swap_audit (
    id                          BIGSERIAL PRIMARY KEY,
    reservation_id              BIGINT        NOT NULL,
    reservation_flow_id         BIGINT        NOT NULL,
    previous_yacht_id           BIGINT        NOT NULL,
    previous_external_yacht_id  BIGINT        NOT NULL,
    new_yacht_id                BIGINT,
    new_external_yacht_id       BIGINT        NOT NULL,
    external_system_id          INT           NOT NULL,
    detected_at                 TIMESTAMP     NOT NULL,
    action                      VARCHAR(30)   NOT NULL,
    notes                       VARCHAR(1000),
    acknowledged_at             TIMESTAMP
);

CREATE INDEX idx_yacht_swap_audit_reservation_id
    ON reservation_yacht_swap_audit(reservation_id);

CREATE INDEX idx_yacht_swap_audit_detected_at
    ON reservation_yacht_swap_audit(detected_at DESC);

-- Partial index for the admin-alert query (unacknowledged swaps per reservation)
CREATE INDEX idx_yacht_swap_audit_unacknowledged
    ON reservation_yacht_swap_audit(reservation_id, detected_at DESC)
    WHERE acknowledged_at IS NULL;

-- Grants so the application role can read/write the new table. Dev env uses
-- separate `boat4you_owner` (Flyway) and `boat4you_app` (runtime) users; the
-- owner-created table is otherwise invisible to the app role, and Hibernate's
-- INSERT ... RETURNING fails with "permission denied for sequence ..._id_seq".
-- Safe no-op in prod if the app role is identical to the owner.
GRANT SELECT, INSERT, UPDATE, DELETE ON reservation_yacht_swap_audit TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE reservation_yacht_swap_audit_id_seq TO boat4you_app;
