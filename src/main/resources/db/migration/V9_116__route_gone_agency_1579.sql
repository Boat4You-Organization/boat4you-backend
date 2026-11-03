-- V9_116 (3.11.2026, Mario "sredi"): stale round-trip offers of a former base on three MMK yachts of agency 1579.
--
-- 18643 ISLAND BLUE V moved from Pula to Propriano, 18707 and 18753 from Montenegro to Pula. MMK now sends only the
-- new home base, but the MMK offer sync is upsert-only with the route in the natural key
-- (uq_offer_yacht_week_product_route), so the old route's rows were never touched again: 18643 stayed in Croatian
-- lists and dated searches at the old price while its page showed Propriano (infra docs/yacht-18643-search-view-
-- 2026-10-28.md, §7.1). Measured 3.11.2026: 159 FREE + 12 OPTION rows, none with a reservation flow or an overlapping
-- partner reservation.
--
-- A row qualifies only when, for the same dates and product, a NEWER round-trip row of the yacht's current home base
-- exists and is not UNAVAILABLE. Status -> UNAVAILABLE, ext_status -> ROUTE_GONE, one partner_offer_flip_log row each
-- (job MIGRATION, run_id NULL) with the old status, ext_status and price for an exact undo. Nothing is deleted and
-- OPTION_WAITING is never touched. If MMK ever sends that route again, the route-aware upsert finds the row by its
-- natural key and writes it back. The search matview picks the change up on its next refresh (<= 10 min).
SET LOCAL lock_timeout = '5s';

WITH cand AS (
    SELECT b.id, b.yacht_id, b.date_from, b.date_to, b.status, b.ext_status, b.client_price
    FROM offer b
    JOIN yacht y ON y.id = b.yacht_id
    WHERE b.yacht_id IN (18643, 18707, 18753)
      AND b.date_from >= CURRENT_DATE
      AND b.status IN ('FREE', 'OPTION')
      AND b.location_from = b.location_to
      AND b.location_from <> y.location_id
      AND EXISTS (SELECT 1 FROM offer a
                  WHERE a.yacht_id = b.yacht_id AND a.date_from = b.date_from AND a.date_to = b.date_to
                    AND COALESCE(a.product, '-') = COALESCE(b.product, '-')
                    AND a.location_from = y.location_id AND a.location_to = y.location_id
                    AND a.status <> 'UNAVAILABLE' AND a.id > b.id)
      AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = b.id)
      AND NOT EXISTS (SELECT 1 FROM external_reservations r
                      WHERE r.yacht_id = b.yacht_id AND r.status IN ('OPTION', 'RESERVATION', 'SERVICE')
                        AND r.date_from < b.date_to AND r.date_to > b.date_from)
    FOR UPDATE OF b
), upd AS (
    UPDATE offer o SET status = 'UNAVAILABLE', ext_status = 'ROUTE_GONE' FROM cand WHERE o.id = cand.id RETURNING o.id
)
INSERT INTO partner_offer_flip_log (run_id, job, reason, external_system_id, offer_id, yacht_id, date_from, date_to,
                                    old_status, old_ext_status, old_client_price, new_status, first_empty_on, decided_on)
SELECT NULL, 'MIGRATION', 'ROUTE_GONE', 1, cand.id, cand.yacht_id, cand.date_from, cand.date_to,
       cand.status, cand.ext_status, cand.client_price, 'UNAVAILABLE', NULL, CURRENT_DATE
FROM cand JOIN upd USING (id);
