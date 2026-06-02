-- Over-availability bug catch-up.
--
-- NauSysAvailabilitySyncService / MmkAvailabilitySyncService used to mark an offer week
-- UNAVAILABLE only when a reservation's dates EXACTLY matched the offer (dateFrom AND dateTo).
-- Multi-week (e.g. Sep 12 -> Sep 26) and non-Saturday-aligned reservations never matched a
-- 7-day slot, so the overlapping weeks stayed bookable (FREE / OPTION) while the yacht was in
-- fact reserved — the customer hit "OPERATION_NOT_ALLOWED" at checkout. The sync services now
-- match on OVERLAP; this backfills the offers that the old exact-match logic already left wrong.
--
-- Half-open overlap (o.date_from < er.date_to AND o.date_to > er.date_from) is turnaround-safe:
-- a charter that ends the same day the next begins is NOT a conflict.
--
-- Idempotent: only flips rows still wrongly FREE / OPTION. The code fix keeps this correct on
-- every subsequent availability sync, so this migration is a one-time catch-up + a safety net
-- that re-applies on any future rebuild.
--
-- NOTE: yacht_search_view is a materialized view carrying offer_status; it must be refreshed
-- after this runs (handled by the periodic refresh job / manual REFRESH on deploy).
UPDATE offer o
SET status = 'UNAVAILABLE'
WHERE o.status IN ('FREE', 'OPTION')
  AND o.date_to >= CURRENT_DATE
  AND EXISTS (
      SELECT 1
      FROM external_reservations er
      WHERE er.yacht_id = o.yacht_id
        AND er.status IN ('RESERVATION', 'SERVICE')
        AND o.date_from < er.date_to
        AND o.date_to > er.date_from
  );
