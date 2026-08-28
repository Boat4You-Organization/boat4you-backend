-- Contract number on invoices (Mario, 28.8.2026): the paper
-- booking-confirmation number he files charters under. Shown in the admin
-- listing's Booking column and searchable together with recipient name and
-- invoice number.
ALTER TABLE invoice
    ADD COLUMN contract_number VARCHAR(63);

-- Backfill from the item description — every commission invoice's item embeds
-- the contract number ("po ugovoru 1001105/2026 ..." / "for booking ... for").
-- Invoices whose item carries no such phrase simply stay NULL.
UPDATE invoice
SET contract_number = substring(invoice_item FROM 'po ugovoru ([0-9]+/[0-9]{4})')
WHERE contract_number IS NULL
  AND invoice_item ~ 'po ugovoru [0-9]+/[0-9]{4}';

UPDATE invoice
SET contract_number = substring(invoice_item FROM 'for booking ([0-9]+/[0-9]{4})')
WHERE contract_number IS NULL
  AND invoice_item ~ 'for booking [0-9]+/[0-9]{4}';
