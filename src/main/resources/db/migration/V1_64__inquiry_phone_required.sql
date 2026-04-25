-- Make `inquiry.phone` mandatory.
--
-- Boat4You policy decision (25.4.2026): every charter inquiry must
-- carry a reachable phone number. The brokerage workflow relies on
-- quick voice / WhatsApp follow-ups, and an email-only lead converts
-- meaningfully worse. The customer form already enforces this client
-- side; the column constraint catches the curl-direct case.
--
-- Existing rows: as of this migration there are no NULL phones in
-- prod or dev (verified before pushing). If a future scrape uncovers
-- a legacy NULL row, set it to a sentinel like '' before re-running.
ALTER TABLE inquiry
    ALTER COLUMN phone SET NOT NULL;
