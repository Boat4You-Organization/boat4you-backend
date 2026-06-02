-- "Sardinia / Corsica" (MMK region, external_system 1) had no country_code, so it was
-- excluded from the /public/regions?countryCode=IT list — its ~18.5k offers were orphaned
-- (no country) and the admin "Sardinia / Corsica" merge option couldn't pick it up. It is
-- Sardinia-anchored → assign Italy.
--
-- Safe/durable: region.country is written ONLY by NauSysCatalogueSyncService.regionsSync,
-- which iterates NauSys regions; this is an MMK region with no NauSys counterpart, so the
-- sync never overwrites it. Resolve by NAME (region IDs renumber). Idempotent.
UPDATE region
SET country_code = 'IT',
    country_id = (SELECT id FROM country WHERE code2 = 'IT')
WHERE name = 'Sardinia / Corsica'
  AND (country_code IS NULL OR country_code = '');
