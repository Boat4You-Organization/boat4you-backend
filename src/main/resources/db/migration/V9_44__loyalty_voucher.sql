-- EUR 100 loyalty voucher (Mario 10.8.2026): issued ONCE per customer after
-- their FIRST confirmed booking, sent by email, redeemable on any FUTURE
-- booking with a client total >= 1500 EUR within 18 months. Transferable —
-- the code itself is the credential. The discount is absorbed entirely by the
-- first payment phase, so Stripe amounts, wire-instruction emails and the
-- charter agreement all pick it up from the phase rows unchanged.

CREATE TABLE IF NOT EXISTS voucher (
    id                          BIGSERIAL PRIMARY KEY,
    -- "B4Y-XXXX-XXXX", uppercase, ambiguity-free alphabet (no 0/O/1/I).
    code                        VARCHAR(20)   NOT NULL,
    value                       NUMERIC(10,2) NOT NULL,
    currency                    VARCHAR(3)    NOT NULL DEFAULT 'EUR',
    -- ACTIVE / USED / EXPIRED / REVOKED
    status                      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    valid_from                  DATE          NOT NULL,
    valid_to                    DATE          NOT NULL,
    issued_to_user_id           BIGINT        NOT NULL REFERENCES users (id),
    -- Source booking. ON DELETE SET NULL so the admin spam purge
    -- (ReservationMutationService.purgeReservation, native SQL) keeps working.
    issued_for_reservation_id   BIGINT        REFERENCES reservation (id) ON DELETE SET NULL,
    used_on_reservation_flow_id BIGINT        REFERENCES reservation_flow (id) ON DELETE SET NULL,
    used_by_user_id             BIGINT        REFERENCES users (id),
    used_at                     TIMESTAMP,
    revoked_at                  TIMESTAMP,
    revoked_reason              VARCHAR(500),
    created_at                  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_voucher_code UNIQUE (code),
    CONSTRAINT chk_voucher_status CHECK (status IN ('ACTIVE', 'USED', 'EXPIRED', 'REVOKED'))
);

-- Once-per-user-ever guard, race-safe across concurrent confirmations.
-- Partial on status <> 'REVOKED' so a voucher revoked because the SOURCE
-- booking was cancelled in cooling-off does not burn the customer's
-- entitlement forever — their next real first booking issues again.
CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_issued_to_user
    ON voucher (issued_to_user_id) WHERE status <> 'REVOKED';

CREATE INDEX IF NOT EXISTS idx_voucher_status_valid_to ON voucher (status, valid_to);
CREATE INDEX IF NOT EXISTS idx_voucher_used_on_flow ON voucher (used_on_reservation_flow_id);
