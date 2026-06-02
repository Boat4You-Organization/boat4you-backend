-- Two HR locations wrongly grouped under Dubrovnik regions in the destination listing
-- (Frapa-class stale mappings — NauSysCatalogueSyncService.locationsSync adds region
-- mappings and never removes, so old/wrong ones accumulate).
--
--  - Marina Krvavica (Makarska riviera, Split-Dalmatia county) -> drop "Dubrovnik region",
--    keeps "Split".
--  - Vrgada Island (Zadar county, near Biograd) -> drop "Dubrovnik / Montenegro", keeps
--    Zadar / Šibenik / Kornati (all northern Dalmatia).
--
-- Resolve by NAME, never hardcoded region IDs (they renumber across syncs/environments).
-- Idempotent: a second run is a no-op once the rows are gone.
DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND l.name ILIKE 'Marina Krvavica'
  AND r.name = 'Dubrovnik region';

DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND l.name ILIKE 'Vrgada Island'
  AND r.name = 'Dubrovnik / Montenegro';
