-- Boat4You Trip (PWA trip companion, phase 1): every reservation gets an
-- unguessable trip token — the /trip/{token} hub URL the customer (and the
-- crew he invites) opens on the phone. 32 hex chars from gen_random_uuid()
-- (~122 random bits); pages are noindex and the token is the only key, so
-- uniqueness + entropy matter. Backfill covers existing reservations (incl.
-- Zen #100183/2026 for Mario's first live test); new ones get the token in
-- ReservationMutationService.createReservation.
ALTER TABLE reservation ADD COLUMN IF NOT EXISTS trip_token VARCHAR(64);

UPDATE reservation
SET trip_token = replace(gen_random_uuid()::text, '-', '')
WHERE trip_token IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_reservation_trip_token ON reservation (trip_token);
