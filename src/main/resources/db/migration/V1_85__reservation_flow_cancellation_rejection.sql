-- Records the admin "we couldn't approve this cancellation" outcome — used
-- when the customer requested cancellation via /my-bookings but the partner
-- agency's policy doesn't allow it (or the partner reservation status doesn't
-- support cancellation any more). Stamping these two columns does NOT flip
-- the reservation to CANCELLED — the booking stays in BOOKING/CONFIRMED and
-- the charter goes ahead. The original `cancelation_request` +
-- `cancelation_request_at` columns are preserved as immutable history of
-- what the customer originally asked for.
--
-- Both columns are nullable: legacy rows + currently-pending requests have
-- no rejection. No default values, no index — the columns are read together
-- with the row, never queried on their own.
--
-- No new GRANTs needed: `reservation_flow` already has full DML privileges
-- for the runtime app role (boat4you_app). New columns inherit the existing
-- table grants — only new tables and sequences need the explicit GRANT
-- pattern shown in V1_84__reservation_document_grants.sql.
ALTER TABLE reservation_flow ADD COLUMN cancelation_rejected_at TIMESTAMP NULL;
ALTER TABLE reservation_flow ADD COLUMN cancelation_rejected_reason TEXT NULL;
