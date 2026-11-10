-- Custom card payments through the secret /pay/<linkKey> page of the 7 sites (planned 13.10.2026 for Revolut as
-- V9_80; provider switched to Mollie 10.11.2026, Mario).
--
-- A client of a contract made outside boat4you (e.g. 100116/2027) opens the link printed in the contract, enters the
-- contract / booking confirmation number as FREE TEXT, the amount from the contract's credit-card box and an e-mail,
-- and pays on Mollie's hosted checkout. Record + notify only: one row per attempt here, nothing in reservations or
-- payment phases changes. reservation_id is an optional, informational link when the reference happens to equal a
-- web reservation number.
--
-- public_token = metadata.customPaymentToken of the Mollie payment and the ?ref= of the success page (no PII in the
-- URL). provider_order_id = the Mollie payment id (tr_…). Status: PENDING -> COMPLETED | FAILED | CANCELLED;
-- NEEDS_REVIEW = Mollie says paid but something does not
-- match (amount, currency, reference), or the attempt stayed pending for 48 h — an admin looks at it.
--
-- Additive only (one new, empty table), idempotent. The FK to reservation takes SHARE ROW EXCLUSIVE on reservation for
-- the CREATE TABLE: start this jar outside a sync window (lock_timeout 5s; DEPLOY_NOTES).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS custom_payment (
    id                     BIGSERIAL     PRIMARY KEY,
    public_token           UUID          NOT NULL,
    site                   VARCHAR(32)   NOT NULL,
    -- As typed by the client (trimmed); not looked up — manual contracts are not in our database.
    reference              VARCHAR(64)   NOT NULL,
    -- Cents. The 5 % card fee is already inside the contract amount: nothing is added.
    amount_minor           BIGINT        NOT NULL,
    currency               VARCHAR(3)    NOT NULL DEFAULT 'EUR',
    provider               VARCHAR(16)   NOT NULL DEFAULT 'MOLLIE',
    provider_order_id      VARCHAR(64),
    provider_payment_id    VARCHAR(64),
    checkout_url           TEXT,
    status                 VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    payer_email            VARCHAR(255)  NOT NULL,
    client_ip              VARCHAR(64),
    locale                 VARCHAR(5)    NOT NULL DEFAULT 'en',
    terms_version          VARCHAR(32)   NOT NULL,
    accepted_at            TIMESTAMPTZ   NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    -- Last time the status endpoint / reconcile job asked Mollie (claimCheck: at most once per 10 s per row).
    last_checked_at        TIMESTAMPTZ,
    last_webhook_event     VARCHAR(64),
    last_webhook_at        TIMESTAMPTZ,
    refunded_amount_minor  BIGINT        NOT NULL DEFAULT 0,
    -- Mollie's running chargeback total (a disputed card payment): alerts the office, never decreases.
    charged_back_amount_minor BIGINT     NOT NULL DEFAULT 0,
    -- Whitelisted copy of the Mollie payment (id, status, method, amounts, timestamps, card label + last 4) — no payer data.
    order_snapshot         JSONB,
    reservation_id         BIGINT        REFERENCES reservation (id) ON DELETE SET NULL,
    admin_note             TEXT,
    CONSTRAINT uq_custom_payment_public_token UNIQUE (public_token),
    CONSTRAINT uq_custom_payment_provider_order_id UNIQUE (provider_order_id),
    CONSTRAINT chk_custom_payment_site CHECK (site IN (
        'boat4you', 'catamaran-charter-greece', 'catamaran-croatia-charter', 'catamarancharteritaly',
        'catamaran-charter-caribbean', 'europe-yachts', 'croatia-yachting'
    )),
    CONSTRAINT chk_custom_payment_reference CHECK (reference = btrim(reference) AND reference ~ '^[A-Za-z0-9/_. -]{3,64}$'),
    CONSTRAINT chk_custom_payment_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_custom_payment_currency CHECK (currency = 'EUR'),
    CONSTRAINT chk_custom_payment_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_custom_payment_refunded CHECK (refunded_amount_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT chk_custom_payment_admin_note CHECK (admin_note IS NULL OR char_length(admin_note) <= 2000)
);

-- Admin list (newest first), reconcile job (PENDING by age), per-e-mail cap, reservation link.
CREATE INDEX IF NOT EXISTS idx_custom_payment_created ON custom_payment (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_custom_payment_status_created ON custom_payment (status, created_at);
CREATE INDEX IF NOT EXISTS idx_custom_payment_email_created ON custom_payment (lower(payer_email), created_at);
CREATE INDEX IF NOT EXISTS idx_custom_payment_reservation ON custom_payment (reservation_id) WHERE reservation_id IS NOT NULL;

-- Explicit grants like V1_58 (payment_refund_audit) — the sequence grant too (V1_84: missing it broke every INSERT).
-- The app never deletes payment records (accounting); the admin only adds a note.
GRANT SELECT, INSERT, UPDATE ON custom_payment TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE custom_payment_id_seq TO boat4you_app;
