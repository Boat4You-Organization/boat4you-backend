-- Review COLLECTION (25.9.2026, phase 1 of the review plan; nothing is displayed publicly yet, vouchers come later).
--
-- Two kinds per reservation:
--   BOOKING = the Boat4You booking experience, requested by e-mail after the customer's FIRST successful payment;
--   YACHT   = the boat / charter, requested by e-mail 3 days after the charter ends.
-- The customer opens a magic link (no login): /review/{token}. Only the SHA-256 of the 32-byte token is stored, so a
-- database leak does not hand out working links. One request row per (reservation, kind) is also the "never
-- double-send" guard: the sender claims the row with INSERT ... ON CONFLICT DO NOTHING before it e-mails.
--
-- Additive only (two new tables, no change to existing rows), idempotent.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS review_request (
    id              BIGSERIAL    PRIMARY KEY,
    -- CASCADE: the admin spam purge (ReservationMutationService.purgeReservation) deletes reservations natively.
    reservation_id  BIGINT       NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    kind            VARCHAR(16)  NOT NULL,
    -- hex SHA-256 of the raw token that went out in the e-mail; the raw token is never stored.
    token_hash      CHAR(64)     NOT NULL,
    -- e-mail / form language (en, de, hr, ...), from users.language at send time.
    locale          VARCHAR(5)   NOT NULL DEFAULT 'en',
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_review_request_kind CHECK (kind IN ('BOOKING', 'YACHT')),
    CONSTRAINT uq_review_request_reservation_kind UNIQUE (reservation_id, kind),
    CONSTRAINT uq_review_request_token_hash UNIQUE (token_hash)
);

CREATE TABLE IF NOT EXISTS reservation_review (
    id                         BIGSERIAL     PRIMARY KEY,
    reservation_id             BIGINT        NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    request_id                 BIGINT        REFERENCES review_request (id) ON DELETE SET NULL,
    kind                       VARCHAR(16)   NOT NULL,
    -- The boat at the time of the charter (YACHT kind; also filled for BOOKING for admin context).
    yacht_id                   BIGINT        REFERENCES yacht (id) ON DELETE SET NULL,
    user_id                    BIGINT        REFERENCES users (id) ON DELETE SET NULL,
    -- YACHT review -> the same reservation's BOOKING review, when the customer left one (not required).
    booking_review_id          BIGINT        REFERENCES reservation_review (id) ON DELETE SET NULL,
    rating                     SMALLINT      NOT NULL,
    -- BOOKING sub-scores (optional, 1-5)
    score_ease_of_booking      SMALLINT,
    score_communication        SMALLINT,
    score_value_transparency   SMALLINT,
    -- YACHT sub-scores (optional, 1-5)
    score_boat_condition       SMALLINT,
    score_cleanliness          SMALLINT,
    score_check_in_out         SMALLINT,
    score_charter_company      SMALLINT,
    score_value                SMALLINT,
    title                      VARCHAR(120),
    text                       TEXT,
    locale                     VARCHAR(5)    NOT NULL DEFAULT 'en',
    -- users.country at submit time (free text, as the profile stores it).
    guest_country              VARCHAR(100),
    -- first day of the charter's month (reservation.date_from).
    charter_month              DATE,
    -- The customer ticked "you may publish my review with my first name and country". No auto-publish either way.
    publish_consent            BOOLEAN       NOT NULL DEFAULT FALSE,
    -- NEW until an admin moderates it (auto-publish is a later decision).
    status                     VARCHAR(16)   NOT NULL DEFAULT 'NEW',
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status_changed_at          TIMESTAMPTZ,
    status_changed_by_user_id  BIGINT        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_reservation_review_kind CHECK (kind IN ('BOOKING', 'YACHT')),
    CONSTRAINT chk_reservation_review_status CHECK (status IN ('NEW', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT chk_reservation_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_reservation_review_scores CHECK (
        (score_ease_of_booking IS NULL OR score_ease_of_booking BETWEEN 1 AND 5)
        AND (score_communication IS NULL OR score_communication BETWEEN 1 AND 5)
        AND (score_value_transparency IS NULL OR score_value_transparency BETWEEN 1 AND 5)
        AND (score_boat_condition IS NULL OR score_boat_condition BETWEEN 1 AND 5)
        AND (score_cleanliness IS NULL OR score_cleanliness BETWEEN 1 AND 5)
        AND (score_check_in_out IS NULL OR score_check_in_out BETWEEN 1 AND 5)
        AND (score_charter_company IS NULL OR score_charter_company BETWEEN 1 AND 5)
        AND (score_value IS NULL OR score_value BETWEEN 1 AND 5)
    ),
    CONSTRAINT chk_reservation_review_text_length CHECK (text IS NULL OR char_length(text) <= 3000),
    -- One BOOKING and one YACHT review per reservation; edits (24 h) update the same row.
    CONSTRAINT uq_reservation_review_reservation_kind UNIQUE (reservation_id, kind)
);

-- Admin moderation list: filter by kind / status, newest first.
CREATE INDEX IF NOT EXISTS idx_reservation_review_status_created ON reservation_review (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reservation_review_kind_status_created ON reservation_review (kind, status, created_at DESC);
-- Later: per-boat aggregates for the public display.
CREATE INDEX IF NOT EXISTS idx_reservation_review_yacht ON reservation_review (yacht_id) WHERE kind = 'YACHT';
CREATE INDEX IF NOT EXISTS idx_reservation_review_user ON reservation_review (user_id);
