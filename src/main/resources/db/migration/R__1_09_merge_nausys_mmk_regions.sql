-- Clean up cross-country location_region pollution (repeatable, self-healing).
--
-- HISTORY / why this changed
-- --------------------------
-- An earlier version of this migration copied location_region rows between
-- HARD-CODED region IDs (r-100 -> r-5, r-99 -> r-6, r-103/r-101 -> r-3,
-- r-104 -> r-1/r-2 ...) to merge NauSYS sailing areas into the MMK regions
-- shown in the UI, so a single region filter returned both providers' yachts.
--
-- Region.id is a SERIAL / local id (NOT a partner id) and was renumbered by a
-- later region reseed. The old source IDs 99-104 now point at CARIBBEAN
-- regions (Antigua, Guadeloupe, Martinique, St. Vincent, Grenada), so the
-- merge was copying Caribbean marinas into Croatian regions — e.g.
-- "Guadeloupe, La Marina Bas du Fort" (l-303) ended up under "Split region"
-- (r-5) and surfaced in Split catamaran searches. Because this file is a
-- REPEATABLE migration it re-injected the pollution on every Flyway run.
--
-- FIX
-- ---
-- Drop the hard-coded-ID merge entirely (after the renumber it no longer does
-- anything correct) and instead remove any location_region row whose location
-- country does not match its region country. This is ID-AGNOSTIC, so it stays
-- correct across future region renumbers, and re-runs harmlessly every time.
--
-- The EXISTS guard only deletes a cross-country row when the location ALSO
-- belongs to a region in its OWN country, so we never orphan a location that
-- has no same-country region (e.g. "Marina Izola" / SI has no Slovenian region
-- of its own, so it stays in the adjacent MMK "Istria / Kvarner" area instead
-- of disappearing from every region search).
--
-- Cross-provider (NauSYS <-> MMK) coverage for a region is handled on the
-- frontend (dual-source "popular" bundling + autocomplete region dedup). If a
-- DB-level by-name merge is wanted later, it MUST resolve region IDs by name,
-- never by frozen numeric ID.

DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND l.country_code IS NOT NULL
  AND r.country_code IS NOT NULL
  AND l.country_code <> r.country_code
  AND EXISTS (
    SELECT 1
    FROM location_region lr2
    JOIN region r2 ON r2.id = lr2.region_id
    WHERE lr2.location_id = l.id
      AND r2.country_code = l.country_code
  );
