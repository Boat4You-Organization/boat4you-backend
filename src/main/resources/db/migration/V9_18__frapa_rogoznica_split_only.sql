-- Marina Frapa, Rogoznica (location.id = 2029) must appear ONLY under the
-- Split region (region.id = 5).
--
-- The partner catalogues classify this marina under Dubrovnik (6), Kornati
-- (190) and Šibenik (4). Both locationsSync paths (NauSys + MMK) call
-- `location.regions.add(...)` and never remove, so every manual cleanup of this
-- row was silently re-added on the next nightly catalogue sync — the marina
-- kept resurfacing under "Dubrovnik region" across all sister sites.
--
-- The permanent guard is now in code: LocationRegionOverrides pins this
-- location to {Split} on every sync, discarding the partner classification.
-- This migration removes the polluting rows that already exist so the fix is
-- visible immediately on deploy (before the next sync runs). location_region
-- has no unique constraint, hence the NOT EXISTS guard on the (re)insert.
-- (Mario 9.6.2026)

DELETE FROM location_region
WHERE location_id = 2029
  AND region_id <> 5;

INSERT INTO location_region (location_id, region_id)
SELECT 2029, 5
WHERE NOT EXISTS (
    SELECT 1 FROM location_region WHERE location_id = 2029 AND region_id = 5
);
