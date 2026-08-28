-- Booking date (Mario, 28.8.2026): the date the operator ISSUED the booking
-- confirmation / contract — the statistic he wants is "when do inquiries and
-- reservations concentrate", which is this date, not the departure.
ALTER TABLE invoice
    ADD COLUMN booking_date DATE;

-- Linked invoices: the platform knows exactly when the reservation was made.
UPDATE invoice i
SET booking_date = v.reservation_created_at::date
FROM reservation_view v
WHERE v.reservation_flow_id = i.reservation_flow_id
  AND i.reservation_flow_id IS NOT NULL
  AND i.booking_date IS NULL;

-- Standalone commission invoices are backfilled separately (V9_53) from the
-- dates on the operators' paper confirmations once extracted.
