-- V1_58: Audit trail for payment refunds / reversals.
--
-- Context (23.4.2026 audit finding F12): the payment flow handles Stripe
-- webhook success but there is no code path that issues refunds — if an admin
-- triggers one externally in the Stripe dashboard, or if we later add a
-- refund button, there's no place recording WHO / WHY / HOW MUCH. Adding a
-- dedicated audit table now (cheap, idempotent) means when the feature lands
-- the schema is already ready.
--
-- Pattern mirrors `reservation_yacht_swap_audit` (V1_50) — append-only, never
-- UPDATEd. Soft FK via id columns so a reservation hard-delete doesn't
-- cascade away the audit trail.

CREATE TABLE IF NOT EXISTS payment_refund_audit (
    id                        BIGSERIAL PRIMARY KEY,
    reservation_flow_id       BIGINT        NOT NULL,
    payment_phase_id          BIGINT        NOT NULL,
    stripe_session_id         VARCHAR(511),
    stripe_payment_intent_id  VARCHAR(511),
    stripe_refund_id          VARCHAR(511),
    refund_amount             NUMERIC(15,2) NOT NULL,
    refund_currency           VARCHAR(3)    NOT NULL,
    refund_reason             TEXT,
    initiated_by_user_id      BIGINT,
    initiated_by_source       VARCHAR(32)   NOT NULL,  -- 'ADMIN_UI' | 'STRIPE_DASHBOARD_WEBHOOK' | 'SYSTEM'
    initiated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    status                    VARCHAR(32)   NOT NULL,  -- 'PENDING' | 'SUCCEEDED' | 'FAILED'
    stripe_response_raw       TEXT
);

CREATE INDEX IF NOT EXISTS idx_payment_refund_audit_flow
    ON payment_refund_audit (reservation_flow_id);

CREATE INDEX IF NOT EXISTS idx_payment_refund_audit_phase
    ON payment_refund_audit (payment_phase_id);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_payment_refund_audit_stripe_refund
    ON payment_refund_audit (stripe_refund_id)
    WHERE stripe_refund_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE ON payment_refund_audit TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE payment_refund_audit_id_seq TO boat4you_app;
