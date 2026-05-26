-- Split reservation documents into customer-visible vs admin-only.
-- Mario rule (3.5.2026): "dokumentacija mi treba za dvije stvari — jedan je
-- interni za nas a drugi je za klijenta". Internal docs (handover notes,
-- agency-only contract scans, accounting receipts) must NEVER reach the
-- customer my-bookings sidebar.
--
-- Default false: every existing document stays customer-visible (matches
-- the pre-3.5 behaviour where ReservationDocument was meant for customer
-- delivery anyway, even though the JPA comment says "admin-only" — we are
-- formally splitting the two paths now).
ALTER TABLE reservation_document
    ADD COLUMN is_internal BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_reservation_document_internal
    ON reservation_document (reservation_id, is_internal);
