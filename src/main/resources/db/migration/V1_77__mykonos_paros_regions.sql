-- Add Mykonos and Paros as standalone regions inside Greece. Both groups
-- of marinas were previously only mapped to "Cyclades" (region 95) which
-- is geographically correct but UX-confusing — the search dropdown showed
-- all Cycladic marinas mixed together (e.g. Mykonos appearing alongside
-- Paros) instead of letting customers narrow to "all Mykonos marinas" or
-- "all Paros marinas" first. Cyclades mapping is preserved (many-to-many)
-- so SEO category pages and country-level overviews still see them.
--
-- ON CONFLICT-style guards because Flyway runs once per deployment
-- environment but the dev DB already has these rows (applied manually
-- before the migration was written); the IF NOT EXISTS pattern keeps
-- this idempotent for prod where rows don't exist yet.

-- ── Regions ──
INSERT INTO region (name, country_id, country_code)
SELECT 'Mykonos', 86, 'GR'
WHERE NOT EXISTS (SELECT 1 FROM region WHERE name = 'Mykonos' AND country_id = 86);

INSERT INTO region (name, country_id, country_code)
SELECT 'Paros', 86, 'GR'
WHERE NOT EXISTS (SELECT 1 FROM region WHERE name = 'Paros' AND country_id = 86);

-- ── Mykonos cluster: Mykonos, Port of Mykonos, Tourlos Marina ──
INSERT INTO location_region (region_id, location_id)
SELECT r.id, l.id
FROM region r, location l
WHERE r.name = 'Mykonos' AND r.country_id = 86
  AND l.id IN (1667, 1415, 1032)
  AND NOT EXISTS (
      SELECT 1 FROM location_region lr
      WHERE lr.region_id = r.id AND lr.location_id = l.id
  );

-- ── Paros cluster: Paros, Paros port, Marina Aliki, Naousa Paros,
--     Paroikia Marina, Piso Livadi, Antiparos, Antiparos Port ──
INSERT INTO location_region (region_id, location_id)
SELECT r.id, l.id
FROM region r, location l
WHERE r.name = 'Paros' AND r.country_id = 86
  AND l.id IN (1660, 1342, 248, 1926, 2046, 1918, 1643, 513)
  AND NOT EXISTS (
      SELECT 1 FROM location_region lr
      WHERE lr.region_id = r.id AND lr.location_id = l.id
  );
