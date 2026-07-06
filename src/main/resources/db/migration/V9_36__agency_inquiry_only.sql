-- Inquiry-only agencies (Mario 6.7.2026): some charter partners must not be
-- directly bookable — the customer can only send an inquiry on their yachts,
-- exactly like CUSTOM boats. Toggled per agency from the admin /agencies modal
-- ("Inquiry mode"). Drives Yacht.isInquireOnly() → the web shows the inquiry
-- form instead of the reserve flow, and the createReservationFlow guard rejects
-- a direct booking. Existing agencies stay bookable (default false).
ALTER TABLE agency
    ADD COLUMN IF NOT EXISTS inquiry_only BOOLEAN NOT NULL DEFAULT false;
