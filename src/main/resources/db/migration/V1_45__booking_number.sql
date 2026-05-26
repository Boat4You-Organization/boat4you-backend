-- Per-year booking number counter. Customer-facing format is
-- "1001{sequence}/{charter_year}" (e.g. "100176/2026"). Each charter year has
-- its own independent sequence — a reservation made today for 2027 and then
-- for 2026 draws from separate counters, not a single global sequence.

CREATE TABLE booking_sequence (
    charter_year  INT PRIMARY KEY,
    last_sequence INT NOT NULL
);

-- Seed the counters. Local DB already contains 75 historical reservations for
-- 2026 and 2 for 2027 that do NOT carry a formal booking number (feature is
-- new); new reservations pick up from the next value — 76 for 2026, 3 for 2027.
INSERT INTO booking_sequence (charter_year, last_sequence) VALUES
    (2026, 75),
    (2027, 2);

-- The customer-facing booking number is stored on `reservation.reservation_number`.
-- Column already exists (V1_03) as VARCHAR(9), but the new format can exceed
-- that (e.g. "1001100/2027" = 12 chars), so widen it to 32.
--
-- `reservation_view` (repeatable R__1_02_reservation_view.sql) references this
-- column, so we must drop the view before ALTER; Flyway will recreate it on
-- the next boot automatically because R__ migrations re-apply on checksum
-- change. No manual recreate needed.
-- IF EXISTS guards a fresh deploy: on first install the R__ migrations
-- haven't run yet so reservation_view doesn't exist when this V_ migration
-- runs. Without IF EXISTS the entire app fails to start.
DROP VIEW IF EXISTS reservation_view;

ALTER TABLE reservation
    ALTER COLUMN reservation_number TYPE VARCHAR(32);
