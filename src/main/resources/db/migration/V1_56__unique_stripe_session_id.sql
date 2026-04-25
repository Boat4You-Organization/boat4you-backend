-- V1_56: Enforce uniqueness on `reservation_payment_phase.stripe_session_id`.
--
-- Without this constraint two parallel webhook deliveries for the same
-- session can each: (1) read the phase as unpaid, (2) mark it paid, (3) do
-- whatever post-payment work follows — leading to double-confirm of the
-- same booking. The Stripe webhook handler also previously had no
-- pessimistic lock on the phase fetch (see audit finding C5/C6), and the
-- repository's `findByStripeSessionIdOrderByDeadlineAsc` returned a list
-- which suggests historical assumption that multiple phases could share a
-- session id. That's wrong: each Stripe Checkout Session is created for a
-- single phase and the session id is a 1:1 surrogate.
--
-- A partial UNIQUE INDEX (only WHERE stripe_session_id IS NOT NULL) lets
-- unpaid phases keep NULL and only enforces the constraint where a real
-- session id was assigned.

CREATE UNIQUE INDEX IF NOT EXISTS uniq_reservation_payment_phase_stripe_session_id
    ON reservation_payment_phase (stripe_session_id)
    WHERE stripe_session_id IS NOT NULL;
