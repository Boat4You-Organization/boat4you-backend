-- 23.8.2026: the NauSys availability synthesizer (not covered by the 22.8 MMK
-- guard) re-created same-day OPTION rows overnight (yachts 5403, 3631, ...)
-- and tripped uq_offer_yacht_week_product_route for 4 agencies. The guard is
-- now on both partner paths; drop whatever 0-night rows slipped in again.
-- Idempotent, never touches an offer a booking references.
DELETE FROM offer o
WHERE o.date_from = o.date_to
  AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id);
