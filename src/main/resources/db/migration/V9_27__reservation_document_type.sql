-- Travel-documents feature (Mario 3.7.2026): admin-uploaded reservation
-- documents get an identity so the customer my-bookings sidebar can render
-- "Boarding pass" / "Crew list" instead of a wall of raw filenames, and the
-- admin can label what he uploads. Values: BOARDING_PASS, CREW_LIST, CONTRACT,
-- OTHER (default — all existing rows become OTHER, display unchanged for them).
ALTER TABLE reservation_document
    ADD COLUMN IF NOT EXISTS document_type VARCHAR(31) NOT NULL DEFAULT 'OTHER';
