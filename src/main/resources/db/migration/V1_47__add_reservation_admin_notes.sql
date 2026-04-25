-- Free-form internal notes admins can write against a reservation ("customer
-- needs airport transfer", "call before check-in", "paid in cash", etc.).
-- Never exposed to the customer; admin panel only.

ALTER TABLE reservation ADD COLUMN admin_notes TEXT;
