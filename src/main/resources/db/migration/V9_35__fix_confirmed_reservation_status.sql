-- Fix confirmed bookings whose OfferStatus was left at OPTION (Mario 6.7.2026).
-- confirmReservationManually (the bank-transfer/admin confirm path) set
-- sys_status=RESERVATION and flipped the OFFER to RESERVED, but never set the
-- reservation's own status=RESERVED. The option-expiry reminder job selects on
-- status=OPTION, so a confirmed, deposit-paid booking (#100183) kept receiving
-- "your option expires in 24h" emails. The code now sets status=RESERVED on
-- manual confirm; this backfills the rows already stuck in the inconsistent state.
-- A sys_status=RESERVATION booking is confirmed, so its OfferStatus must be RESERVED.
UPDATE reservation
SET status = 'RESERVED'
WHERE sys_status = 'RESERVATION'
  AND status = 'OPTION';
