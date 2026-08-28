-- Charter period on invoices (Mario, 28.8.2026): departure/return dates as
-- real DATE columns so the admin listing can show and SORT/FILTER by when the
-- charter starts, independent of the free-text item description.
ALTER TABLE invoice
    ADD COLUMN charter_date_from DATE,
    ADD COLUMN charter_date_to   DATE;

-- Backfill 1 — invoices linked to a reservation take the booking's own dates.
UPDATE invoice i
SET charter_date_from = v.reservation_date_from::date,
    charter_date_to   = v.reservation_date_to::date
FROM reservation_view v
WHERE v.reservation_flow_id = i.reservation_flow_id
  AND i.reservation_flow_id IS NOT NULL
  AND i.charter_date_from IS NULL;

-- Backfill 2 — standalone commission invoices embed the period in the item
-- text: HR "u periodu od 08 Aug 2026 do 15 Aug 2026",
--       EN "for the period 20 Jun 2026 to 27 Jun 2026".
UPDATE invoice
SET charter_date_from = to_date(substring(invoice_item FROM 'u periodu od ([0-9]{2} [A-Za-z]{3} [0-9]{4}) do'), 'DD Mon YYYY'),
    charter_date_to   = to_date(substring(invoice_item FROM ' do ([0-9]{2} [A-Za-z]{3} [0-9]{4})'), 'DD Mon YYYY')
WHERE charter_date_from IS NULL
  AND invoice_item ~ 'u periodu od [0-9]{2} [A-Za-z]{3} [0-9]{4} do [0-9]{2} [A-Za-z]{3} [0-9]{4}';

UPDATE invoice
SET charter_date_from = to_date(substring(invoice_item FROM 'for the period ([0-9]{2} [A-Za-z]{3} [0-9]{4}) to'), 'DD Mon YYYY'),
    charter_date_to   = to_date(substring(invoice_item FROM ' to ([0-9]{2} [A-Za-z]{3} [0-9]{4})'), 'DD Mon YYYY')
WHERE charter_date_from IS NULL
  AND invoice_item ~ 'for the period [0-9]{2} [A-Za-z]{3} [0-9]{4} to [0-9]{2} [A-Za-z]{3} [0-9]{4}';
