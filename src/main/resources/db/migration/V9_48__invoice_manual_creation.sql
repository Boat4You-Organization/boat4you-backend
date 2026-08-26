-- Manual invoices (admin-issued, e.g. agency services or ad-hoc client bills)
-- can exist without a reservation. Auto-generated commission invoices keep the
-- FK populated as before.
ALTER TABLE invoice ALTER COLUMN reservation_flow_id DROP NOT NULL;
