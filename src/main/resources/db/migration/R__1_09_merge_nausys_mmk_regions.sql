-- Clean up the Caribbean-into-Croatian region pollution this migration caused
-- (repeatable, self-healing).
--
-- HISTORY / why this changed
-- --------------------------
-- An earlier version of this migration copied location_region rows between
-- HARD-CODED region IDs (r-100 -> r-5, r-99 -> r-6, r-103/r-101 -> r-3,
-- r-104 -> r-1/r-2) to merge NauSYS sailing areas into the MMK regions shown
-- in the UI. Region.id is a local SERIAL (not a partner id) and was renumbered
-- by a later region reseed, so the old source IDs 99-104 now point at the six
-- CARIBBEAN regions (Antigua, Guadeloupe, Martinique, St. Vincent, Grenada).
-- The merge therefore copied Caribbean marinas into Croatian regions -- e.g.
-- "Guadeloupe, La Marina Bas du Fort" (l-303) ended up under "Split region"
-- (r-5) and surfaced in Split catamaran searches. Because this file is a
-- REPEATABLE migration it re-injected the pollution on every Flyway run.
--
-- FIX
-- ---
-- 1. The hard-coded-ID merge is removed entirely (after the renumber it no
--    longer does anything correct, and the FE handles dual-source coverage via
--    the "popular" bundling + autocomplete region dedup). No more inserts =
--    no more re-pollution.
-- 2. Remove the leftover rows this migration created: Caribbean (country_code
--    'BQ') locations attached to non-Caribbean regions. Scoped to 'BQ' on
--    purpose -- that is exactly what the broken merge produced. It deliberately
--    does NOT touch other cross-country memberships (e.g. an Italian marina in
--    a Greek "Ionian" sailing area), which are a separate question and may be
--    intentional cross-border areas.
-- The EXISTS guard only deletes a row when the location also belongs to a
-- region in its own country, so nothing is ever orphaned (every Caribbean
-- marina keeps its real Caribbean region).

DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND l.country_code = 'BQ'
  AND r.country_code <> 'BQ'
  AND EXISTS (
    SELECT 1
    FROM location_region lr2
    JOIN region r2 ON r2.id = lr2.region_id
    WHERE lr2.location_id = l.id
      AND r2.country_code = l.country_code
  );
