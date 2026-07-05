-- Boat4You Trip phase 2 (push + day-1 analytics).
--
-- trip_push_subscription: one row per browser/device subscribed to trip
-- reminders through the /trip/{token} hub. The endpoint is globally unique
-- per push service, so re-subscribing the same device (or the same device
-- opening another trip) upserts the row instead of duplicating it. is_owner
-- marks subscriptions created while the booking owner's web session was
-- active — installment reminders go ONLY to those (crew never learns about
-- payments, Mario rule 4.7.2026).
CREATE TABLE IF NOT EXISTS trip_push_subscription (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT       NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    endpoint       TEXT         NOT NULL,
    p256dh         VARCHAR(255) NOT NULL,
    auth           VARCHAR(255) NOT NULL,
    is_owner       BOOLEAN      NOT NULL DEFAULT FALSE,
    user_agent     VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_trip_push_subscription_endpoint UNIQUE (endpoint)
);

CREATE INDEX IF NOT EXISTS idx_trip_push_subscription_reservation
    ON trip_push_subscription (reservation_id);

-- trip_event: append-only analytics (HUB_VIEW / SITE_CLICK / PUSH_SUBSCRIBE /
-- PUSH_OPEN / DOC_OPEN). No PII — just the reservation, the event and a short
-- free-form meta ("standalone", "push:day_of", …). Queried ad hoc via psql
-- until the admin console lands in phase 3.
CREATE TABLE IF NOT EXISTS trip_event (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT      NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    event_type     VARCHAR(40) NOT NULL,
    meta           VARCHAR(255),
    created_at     TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trip_event_reservation ON trip_event (reservation_id);
CREATE INDEX IF NOT EXISTS idx_trip_event_type_created ON trip_event (event_type, created_at);
