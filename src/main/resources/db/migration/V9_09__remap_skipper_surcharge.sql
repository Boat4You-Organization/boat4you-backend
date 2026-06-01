-- V9_09: detach the "Additional fee for Skipper in forepeak & shared bathroom"
-- surcharge from the canonical Skipper label (extras_id = 1).
--
-- Root cause (Lagoon 38 "Dschubba" 4658, Navigare/NauSys): the surcharge
-- (50 €/night) and the real "Skipper" service (1640 €/week) both matched our
-- "skipper" Extra label, so they shared extras_id = 1 and therefore the same
-- extrasKey ("1"). The detail grouping query (MIN(price) over the label) and
-- the pricing service (associateBy { extrasKey() }, plus offer_extras
-- overriding yacht_extras on the same key) both collapsed the two into the
-- cheapest raw number -> 50, hiding the real skipper and mis-pricing it.
--
-- The matcher fix in R__1_04 (not:additional fee) stops new/re-synced rows
-- from re-claiming the label; this migration remaps the rows already in the
-- DB so the surcharge becomes a distinct, separately selectable extra
-- (extras_id NULL -> extrasKey falls back to its name). Affects ~816
-- yacht_extras rows / 408 yachts and ~3450 offer_extras rows.
--
-- extras_id is nullable (many extras already have no canonical label), so
-- NULL-ing it is safe and changes only the dedup/selection key, not the price.

UPDATE yacht_extras
   SET extras_id = NULL
 WHERE extras_id = 1
   AND name ILIKE 'Additional fee for Skipper%';

UPDATE offer_extras
   SET extras_id = NULL
 WHERE extras_id = 1
   AND name ILIKE 'Additional fee for Skipper%';
