-- Boat4You Trip phases 3+4: crew (closed group), chat and the photo album.
--
-- trip_participant: everyone inside a trip's closed group. The trip link is
-- the group credential; joining hands the device a participant_key (secret,
-- stored client-side) that gates chat/album/crew-list reads. The booking
-- owner is claimed through the authenticated web session (role OWNER); guests
-- join with just a name — no registration by design (Mario 4.7.2026).
-- Removal is a soft flag so the chat history keeps the sender's name.
CREATE TABLE IF NOT EXISTS trip_participant (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT       NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    participant_key VARCHAR(64)  NOT NULL,
    name            VARCHAR(120) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'GUEST',
    removed         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_trip_participant_key UNIQUE (participant_key)
);

CREATE INDEX IF NOT EXISTS idx_trip_participant_reservation
    ON trip_participant (reservation_id);

-- trip_chat_message: PG-backed group chat (SSE fan-out lives in memory on
-- the API node; the table is the source of truth). participant_id is NULL
-- for concierge/system posts. automation_tag makes scheduled concierge posts
-- (itinerary T-14, ready T-1) idempotent across job re-runs.
CREATE TABLE IF NOT EXISTS trip_chat_message (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT        NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    participant_id BIGINT        REFERENCES trip_participant (id) ON DELETE SET NULL,
    sender_name    VARCHAR(120)  NOT NULL,
    sender_role    VARCHAR(20)   NOT NULL,
    body           VARCHAR(2000) NOT NULL,
    automation_tag VARCHAR(40),
    created_at     TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trip_chat_message_reservation
    ON trip_chat_message (reservation_id, id);

-- trip_photo: crew album. Files live on the NFS upload dir (webp, converted
-- on ingest like yacht images) — only metadata here. marketing_consent is
-- the per-upload GDPR checkbox; only consented photos may be used in
-- marketing material after the charter.
CREATE TABLE IF NOT EXISTS trip_photo (
    id                BIGSERIAL PRIMARY KEY,
    reservation_id    BIGINT       NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    participant_id    BIGINT       REFERENCES trip_participant (id) ON DELETE SET NULL,
    uploader_name     VARCHAR(120),
    file_path         VARCHAR(511) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    marketing_consent BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trip_photo_reservation ON trip_photo (reservation_id);

-- Unread-chat badge for the admin bookings list: messages newer than this
-- stamp light the badge. Updated whenever the admin opens the booking's chat.
ALTER TABLE reservation
    ADD COLUMN IF NOT EXISTS admin_chat_seen_at TIMESTAMP;
