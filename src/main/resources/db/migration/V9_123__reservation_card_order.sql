-- Online-booking card payments through Mollie (planned 13.10.2026 for Revolut as V9_81, switched to Mollie 10.11.2026;
-- plan Part 2, 2A.5 + second review M3/M4).
--
-- Provider swap only: the phases a checkout pays and the amount (card surcharge included) are computed exactly as for
-- Stripe (CardChargeCalculator). What is new is WHERE the payment is recorded:
--
-- 1. reservation_payment_phase gets three provider-neutral columns, filled when a Mollie order pays the phase:
--    payment_provider ('MOLLIE'), provider_order_id, provider_payment_id. The old stripe_* columns stay (read-only
--    history of Stripe payments; V1_56's UNIQUE index on stripe_session_id is untouched). provider_order_id is NOT
--    unique: a pay-full checkout stamps ONE order on several phases.
--
-- 2. reservation_card_order = the ledger of every Mollie booking order: the expected charge frozen at creation
--    (phase ids, base + surcharge, amount_minor), the dedupe source ("reuse before create": the web regenerates its
--    idempotency key on every page mount), the reconcile source (missed webhooks) and the idempotent claim of the
--    completion (status PENDING -> COMPLETED under FOR UPDATE in the same transaction as the phase marking).
--    expires_at = our checkout lifetime (application.mollie.booking.checkout-ttl, default 15 min; Mollie decides the
--    real expiry of a card payment and it cannot be set); the reuse window is computed from it. promote_state tracks
--    the rare first-payment "promote to booking" after the payment commit (M4): NULL = not needed, PENDING -> DONE |
--    FAILED. checkout_token = our token in the redirect URL (?paymentRef=) and the metadata.
--    Status: PENDING -> COMPLETED | FAILED | CANCELLED | SUPERSEDED (a newer checkout replaced it on our side while
--    Mollie keeps it payable: NOT final, still read until Mollie closes it); NEEDS_REVIEW = Mollie says paid but
--    something does not match (amount, currency, metadata, a phase already paid = double payment, cancelled
--    reservation) — an admin looks at it. refunded / charged_back_amount_minor = Mollie's running totals (alerts).
--
-- Additive only (three nullable columns, one new empty table), idempotent. ADD COLUMN without a default is a catalog
-- change, but it needs a short ACCESS EXCLUSIVE lock on reservation_payment_phase, and the FK takes SHARE ROW
-- EXCLUSIVE on reservation: start this jar outside a sync window (lock_timeout 5s; DEPLOY_NOTES).
SET LOCAL lock_timeout = '5s';

ALTER TABLE reservation_payment_phase
    ADD COLUMN IF NOT EXISTS payment_provider    VARCHAR(16),
    ADD COLUMN IF NOT EXISTS provider_order_id   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS provider_payment_id VARCHAR(64);

-- Webhook / reconcile lookup "which phases did this order pay". Partial and NON-unique on purpose (see 1. above).
CREATE INDEX IF NOT EXISTS idx_reservation_payment_phase_provider_order_id
    ON reservation_payment_phase (provider_order_id)
    WHERE provider_order_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS reservation_card_order (
    id                   BIGSERIAL      PRIMARY KEY,
    provider             VARCHAR(16)    NOT NULL DEFAULT 'MOLLIE',
    provider_order_id    VARCHAR(64)    NOT NULL,
    -- Our own token in the Mollie redirectUrl (?paymentRef=) and metadata: the payment-success page asks the status
    -- with it (Mollie also sends payers who did NOT pay back to the redirectUrl).
    checkout_token       UUID           NOT NULL,
    -- SET NULL: the admin spam purge deletes reservations natively; the money trail stays.
    reservation_id       BIGINT         REFERENCES reservation (id) ON DELETE SET NULL,
    -- No FK (informational, like the purge above): the flow whose phases the order pays.
    reservation_flow_id  BIGINT         NOT NULL,
    -- Sorted, comma-separated reservation_payment_phase ids the order pays; '' = legacy pay-full without phase rows.
    phase_ids            VARCHAR(512)   NOT NULL DEFAULT '',
    -- The request as the web sent it (= Stripe's session metadata); payment_phase_id NULL = the rare pay-full path.
    pay_full_amount      BOOLEAN,
    payment_phase_id     BIGINT,
    base_amount          NUMERIC(12, 2) NOT NULL,
    surcharge_amount     NUMERIC(12, 2) NOT NULL,
    -- Cents actually charged = toCentsLong(base + surcharge); compared with Mollie's amount on completion.
    amount_minor         BIGINT         NOT NULL,
    currency             VARCHAR(3)     NOT NULL DEFAULT 'EUR',
    -- merchant_order_data.reference = reservation number (sanitised) or id: dashboard search by booking number.
    reference            VARCHAR(64)    NOT NULL,
    idempotency_key      VARCHAR(128),
    checkout_url         TEXT           NOT NULL,
    status               VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    review_reason        TEXT,
    provider_payment_id  VARCHAR(64),
    refunded_amount_minor     BIGINT NOT NULL DEFAULT 0,
    charged_back_amount_minor BIGINT NOT NULL DEFAULT 0,
    promote_state        VARCHAR(16),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ    NOT NULL,
    completed_at         TIMESTAMPTZ,
    -- Last time the status endpoint / reconcile job asked Mollie (claimCheck: at most once per 10 s per row).
    last_checked_at      TIMESTAMPTZ,
    last_webhook_at      TIMESTAMPTZ,
    last_webhook_event   VARCHAR(64),
    CONSTRAINT uq_reservation_card_order_provider_order_id UNIQUE (provider_order_id),
    CONSTRAINT uq_reservation_card_order_checkout_token UNIQUE (checkout_token),
    CONSTRAINT chk_reservation_card_order_provider CHECK (provider = 'MOLLIE'),
    CONSTRAINT chk_reservation_card_order_phase_ids CHECK (phase_ids ~ '^([0-9]+(,[0-9]+)*)?$'),
    CONSTRAINT chk_reservation_card_order_amounts CHECK (amount_minor > 0 AND base_amount >= 0 AND surcharge_amount >= 0),
    CONSTRAINT chk_reservation_card_order_currency CHECK (currency = 'EUR'),
    CONSTRAINT chk_reservation_card_order_status CHECK (status IN ('PENDING', 'SUPERSEDED', 'COMPLETED', 'FAILED', 'CANCELLED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_reservation_card_order_promote_state CHECK (promote_state IS NULL OR promote_state IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT chk_reservation_card_order_review_reason CHECK (review_reason IS NULL OR char_length(review_reason) <= 1000)
);

-- Admin / alerts per reservation, reconcile job (PENDING by age), reuse-before-create per reservation flow.
CREATE INDEX IF NOT EXISTS idx_reservation_card_order_reservation_status ON reservation_card_order (reservation_id, status);
CREATE INDEX IF NOT EXISTS idx_reservation_card_order_pending_created ON reservation_card_order (created_at) WHERE status IN ('PENDING', 'SUPERSEDED');
CREATE INDEX IF NOT EXISTS idx_reservation_card_order_flow_status ON reservation_card_order (reservation_flow_id, status);

-- Explicit grants like V9_80 — the sequence grant too (V1_84: missing it broke every INSERT). Payment records are never
-- deleted by the app (accounting).
GRANT SELECT, INSERT, UPDATE ON reservation_card_order TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE reservation_card_order_id_seq TO boat4you_app;
