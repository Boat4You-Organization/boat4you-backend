-- Customer-visible "Charter update" note (Mario 5.7.2026): the broker arranges
-- extras with the agency at negotiated prices (e.g. "Skipper: 1470 €", "Stand Up
-- Paddle 200 €") and writes them here. Unlike admin_notes (internal-only), this
-- text IS shown to the customer on /my-bookings/{id} below the Pay-now action.
ALTER TABLE reservation
    ADD COLUMN charter_update TEXT;
