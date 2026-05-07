-- Dual-source region dedup for Sardinia.
--
-- Backend has two REGION rows for Sardinia:
--   r-85  "Sardinia / Corsica" (countryCode=NULL, MMK source — covers IT + FR)
--   r-135 "Sardinia"           (countryCode=IT,   NauSys source)
--
-- The FE autocomplete dedupeRegionDuplicates collapses them into a single
-- "Sardinia" chip and emits did=r-135. But Italian Sardinian marinas (e.g.
-- Marina di Olbia, location 27 hosting yacht NOAH) were imported only under
-- r-85, so they're invisible to the customer-facing search even though they
-- show up in admin search (which queries by country, not region).
--
-- Mirror every IT location currently linked to r-85 into r-135 as well, so
-- did=r-135 returns the full Sardinian fleet. French locations (Corsica)
-- stay out of r-135 — they are a different geographic + brand promise even
-- though the legacy MMK region bundles them together.
--
-- Idempotent: ON CONFLICT DO NOTHING so re-running the migration after
-- partner re-sync (which can re-introduce r-85-only mappings) is safe.

INSERT INTO location_region (region_id, location_id)
SELECT 135, l.id
FROM location l
JOIN location_region lr ON lr.location_id = l.id
WHERE lr.region_id = 85
  AND l.country_code = 'IT'
  AND NOT EXISTS (
    SELECT 1 FROM location_region
    WHERE location_id = l.id AND region_id = 135
  );
