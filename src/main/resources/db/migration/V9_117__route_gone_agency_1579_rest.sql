-- V9_117 (3.11.2026, Mario "sredi"): the rest of the stale old-base rows of 18643, 18707 and 18753, after V9_116.
--
-- V9_116 flipped only the rows that had a newer home-base row for the same dates and product. Left on sale were the
-- 14/21/28-night packages and three single weeks that have no home-base twin (88 FREE + 3 OPTION on 3.11.2026), so 18643
-- still showed in the Pula listing and in 14-night Pula searches. MMK confirmed on 3.11.2026 (read-only /offers per
-- yacht: the exact 14-, 28- and 7-night periods and the whole of 2027) that it no longer sends any round trip from the
-- old bases: 18643 only Propriano, 18707 only its new base (one one-way ends in Montenegro), 18753 only Pula.
--
-- Same guards and flip log as V9_116, without the home-base twin requirement: a future FREE or OPTION round trip of the
-- yacht's NAMED old base (18643: 1932 Pula; 18707 and 18753: 1466, MMK base 498501610000100000), which is also not its
-- current home, no reservation flow, no overlapping partner option / reservation / service. OPTION_WAITING is never
-- touched; nothing is deleted; a route MMK sends again is written back by the upsert.
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
      AND ((b.yacht_id = 18643 AND b.location_from = 1932) OR (b.yacht_id IN (18707, 18753) AND b.location_from = 1466))
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
