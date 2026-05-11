-- V1_91: Event-level idempotency for Stripe webhook delivery.
--
-- F3-022 (F1-019 concretization). Stripe explicitly designs webhook delivery
-- as at-least-once with retries. Without an event-id store, every redelivery
-- of e.g. `checkout.session.completed` re-fires the full
-- `promoteReservationToBooking` chain (partner confirm + DB save + email),
-- causing duplicate partner bookings and duplicate customer emails.
--
-- This table is the source of truth for "have we processed this Stripe
-- event yet?". `StripePaymentService.handleWebhookEvent` does an atomic
-- `INSERT ... ON CONFLICT (event_id) DO NOTHING` as its first step; the
-- insert commits in a REQUIRES_NEW transaction so even if the surrounding
-- handler rolls back, the event remains marked processed (subsequent Stripe
-- retries become no-ops). Trade-off: partial-failure scenarios still need
-- manual reconciliation, but that is preferable to silently double-charging
-- or double-confirming.
--
-- Retention: keep rows indefinitely for now. Stripe documentation suggests
-- pruning after the longest webhook retry window (~3 days), but the table
-- is small (one row per event ~150 bytes), so retention is operationally
-- cheap and helps post-mortem of webhook flow.
CREATE TABLE processed_stripe_events (
    event_id      VARCHAR(255) PRIMARY KEY,
    event_type    VARCHAR(64)  NOT NULL,
    processed_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX processed_stripe_events_processed_at_idx
    ON processed_stripe_events (processed_at);

GRANT SELECT, INSERT ON processed_stripe_events TO boat4you_app;
