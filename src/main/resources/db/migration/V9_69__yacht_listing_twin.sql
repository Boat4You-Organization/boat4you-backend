-- One card per physical boat on the undated listings (26.9.2026 audit B17).
--
-- The same boat is often imported twice (two partner systems, or an owner and a broker agency): the Seychelles landing
-- showed 5 pairs in its first 18 cards (Pampero 5371 / 18795, Modjo 5374 / 18785, Zil 2 5395 / ZIL 2 18805 ...), 69
-- pairs on 33 of 150 sampled landings, 56 of them at the same marina. yacht_listing_twin lists every copy that is NOT
-- the one to show, with the copy to show: same name (case and punctuation folded), same base (same-spelling marina
-- of the same country), same vessel type, length within 0.5 m and build year within 1 (partners disagree by a year
-- and on the model variant: "Lagoon 380 S2" / "Lagoon 380"), AND a different channel - another agency or another
-- partner system. One agency on one system naming several boats alike is a fleet, not a duplicate: The Moorings lists
-- several "Moorings 41.3 Exclusive" at one base (replay on a real catalogue: 259 such pairs, all distinct boats). A
-- name that is only a model, a number or a placeholder ("Bavaria Cruiser 46", "(2021)", "no name") never pairs.
-- The copy shown follows a STABLE rule, never a count that moves every day (review 26.9.2026: "most future offers"
-- swapped the shown copy - and with it the sitemap entry and the boat page's canonical - in 10 of 496 pairs within a
-- month and 31 within three months of a real catalogue, from the passage of time alone):
--   1. a copy with a week left to sell (FREE / OPTION / OPTION_WAITING from today) before one without - a sold-out copy
--      never hides the one that can still be booked;
--   2. a directly bookable copy (agency not inquiry-only, no option approval: Yacht.isInquireOnly) before an
--      inquiry-only one;
--   3. the older (lower) id.
-- Only a copy the undated listing can show (an offer from today that is not UNAVAILABLE) is ever the copy shown, so
-- hiding the other never takes the boat off a landing. Each flag changes only when a copy sells out, gets weeks
-- again or changes channel. The undated search (landings, sitemap, counts), its facets and the charter facts skip
-- the listed copies; dated searches keep every copy (availability can differ between two channels).
--
-- Refreshed CONCURRENTLY right after yacht_search_view (YachtSearchViewRefresher). Created WITH DATA here: one pass over
-- yacht + the future offers, well under a second; a new relation, so no lock on existing ones. Reads
-- agency.inquiry_only (V9_36) and yacht.option_approval.
SET LOCAL lock_timeout = '5s';

CREATE MATERIALIZED VIEW IF NOT EXISTS public.yacht_listing_twin AS
WITH future AS (
    -- what the undated listing can show (offer from today, not UNAVAILABLE) and whether a week is left to sell
    SELECT o.yacht_id,
           bool_or(o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')) AS sellable
    FROM offer o
    WHERE o.date_from >= CURRENT_DATE
      AND o.status <> 'UNAVAILABLE'
    GROUP BY o.yacht_id
),
source AS (
    SELECT em.system_id AS yacht_id, min(em.external_system_id) AS system
    FROM external_mapping em
    WHERE em.type = 'Yacht'
    GROUP BY em.system_id
),
named AS (
    SELECT y.*,
           regexp_replace(lower(btrim(y.name)), '[^[:alnum:]]', '', 'g') AS name_key,
           regexp_replace(lower(COALESCE(m.name, '')), '[^[:alnum:]]', '', 'g') AS model_key,
           regexp_replace(lower(COALESCE(mf.name, '') || COALESCE(m.name, '')), '[^[:alnum:]]', '', 'g') AS full_model_key
    FROM yacht y
    LEFT JOIN model m         ON m.id = y.model_id
    LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
),
listed AS (
    SELECT y.id,
           y.agency_id,
           s.system,
           y.vessel_type,
           y.length,
           y.build_year,
           y.name_key,
           l.country_code || ':' || translate(lower(btrim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS base_key,
           f.yacht_id IS NOT NULL AS listable,
           COALESCE(f.sellable, false) AS sellable,
           NOT (COALESCE(y.option_approval, false) OR COALESCE(a.inquiry_only, false)) AS bookable
    FROM named y
    JOIN agency a      ON a.id = y.agency_id AND a.active AND NOT a.availability_blocked
    JOIN location l    ON l.id = y.location_id
    LEFT JOIN future f ON f.yacht_id = y.id
    LEFT JOIN source s ON s.yacht_id = y.id
    WHERE y.entry_type = 'EXTERNAL'
      AND y.sys_active
      -- a real boat name: letters, 3+ characters, not the model, not a placeholder
      AND length(y.name_key) >= 3
      AND y.name_key ~ '[a-z]'
      AND y.name_key NOT IN (y.model_key, y.full_model_key, 'noname', 'unnamed', 'tba', 'tbd', 'new', 'yacht', 'boat')
),
better AS (
    SELECT y.id AS yacht_id,
           c.id AS canonical_yacht_id,
           row_number() OVER (PARTITION BY y.id ORDER BY c.sellable DESC, c.bookable DESC, c.id) AS rn
    FROM listed y
    JOIN listed c
      ON c.name_key = y.name_key
     AND c.base_key = y.base_key
     -- another channel: a different agency, or the same agency through the other partner system
     AND (c.agency_id <> y.agency_id OR c.system IS DISTINCT FROM y.system)
     AND c.vessel_type = y.vessel_type
     AND c.id <> y.id
     AND (c.length IS NULL) = (y.length IS NULL)
     AND (c.length IS NULL OR abs(c.length - y.length) <= 0.5)
     AND (c.build_year IS NULL) = (y.build_year IS NULL)
     AND (c.build_year IS NULL OR abs(c.build_year - y.build_year) <= 1)
     -- the copy shown: listable, and first by the stable rule above (false < true; the lower id wins a tie)
     AND c.listable
     AND (c.sellable, c.bookable, -c.id) > (y.sellable, y.bookable, -y.id)
),
pick AS (
    SELECT yacht_id, canonical_yacht_id
    FROM better
    WHERE rn = 1
)
-- Pairing is not transitive (length within 0.5 m, year within 1): when A~B and B~C but not A~C, C's best copy B is
-- itself hidden behind A. One more hop names the copy that is shown.
SELECT p.yacht_id, COALESCE(q.canonical_yacht_id, p.canonical_yacht_id) AS canonical_yacht_id
FROM pick p
LEFT JOIN pick q ON q.yacht_id = p.canonical_yacht_id;

CREATE UNIQUE INDEX IF NOT EXISTS yacht_listing_twin_yacht_uidx ON public.yacht_listing_twin (yacht_id);

GRANT SELECT ON public.yacht_listing_twin TO boat4you_app;
