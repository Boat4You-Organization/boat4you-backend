-- Marina Frapa (Rogoznica) was wrongly grouped under the Dubrovnik + Kornati
-- regions in the destination filter. It sits in the Šibenik / Split area.
--
-- Root cause: NauSysCatalogueSyncService.locationsSync() does
-- `location.regions.add(region)` for the location's current NauSys regionId and
-- NEVER removes — so when a location's region changes (or NauSys region IDs are
-- renumbered) stale region mappings accumulate and are never cleaned up. That is
-- why this regressed after a previous manual fix ("OPET").
--
-- This removes the two stale mappings (Dubrovnik / Kornati) for the Rogoznica
-- Frapa, keeping only Šibenik + Split. The separate location
-- "Marina Frapa Dubrovnik" (genuinely in Dubrovnik) is untouched.
--
-- Resolve by NAME, never by hardcoded IDs — region IDs renumber across syncs and
-- differ between environments (see prior region-id-renumber incidents).
DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND l.city ILIKE 'Rogoznica'
  AND l.name ILIKE 'Marina Frapa%'
  AND r.name IN ('Dubrovnik region', 'Kornati', 'Dubrovnik / Montenegro');

-- Disambiguate the name from "Marina Frapa Dubrovnik" (a separate, genuinely-Dubrovnik
-- marina). Sticks now that NauSysCatalogueSyncService.locationsSync no longer overwrites
-- existing location names on every sync. Idempotent (re-run is a no-op).
UPDATE location
SET name = 'Marina Frapa, Rogoznica'
WHERE city ILIKE 'Rogoznica'
  AND name ILIKE 'Marina Frapa%';
