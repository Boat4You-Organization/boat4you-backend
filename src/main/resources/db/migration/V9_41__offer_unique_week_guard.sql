-- Duplicate-offer guard (20.7.2026). The offer table had accumulated 1.37M
-- byte-identical duplicate rows (same yacht/week/status/product/route/price,
-- ids 168k-3.4M — long-standing sync upsert misses), exposed on the boat
-- detail page once V9_38 made 7-day OTHER offers STANDARD. A one-time batched
-- cleanup ran on 20.7 (keeper per group = reservation_flow-referenced row if
-- any, else min(id); child offer_extras/offer_payment_plan rows of strays
-- deleted — the sync regenerates them for the keeper).
--
-- This index makes recurrence IMPOSSIBLE at the database level regardless of
-- which sync path misbehaves: a colliding INSERT now throws, the per-yacht
-- sync try/catch logs it, and the next run finds + updates the existing row.
-- One-way variants stay legal — the key includes the route.
CREATE UNIQUE INDEX IF NOT EXISTS uq_offer_yacht_week_product_route
    ON offer (yacht_id, date_from, date_to,
              coalesce(product, '-'), coalesce(location_from, -1), coalesce(location_to, -1));
