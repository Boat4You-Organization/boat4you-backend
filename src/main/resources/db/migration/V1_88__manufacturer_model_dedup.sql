-- Production data hygiene: manufacturer + model dedup performed on dev DB
-- 27.4-4.5.2026, ported here so prod converges to the same canonical state.
--
-- Why this is one Flyway migration instead of three: prod cutover should be
-- atomic — partial dedup (e.g. manufacturers merged but models still bear
-- cabin-config suffixes) leaves the search filter in an awkward middle state
-- the customer would notice immediately.
--
-- Idempotency: every block uses CTE/HAVING > 1 + EXISTS guards. Re-running
-- on a clean DB is a no-op. Local dev DB has already been deduped manually,
-- so the first time Flyway picks this up locally it should report 0 row
-- changes. Production starts from the legacy state and gets the full sweep.
--
-- Coverage:
--   1) Lagoon-Bénéteau → Lagoon  (id 1738 → 1084)
--   2) Catana Group   → Bali     (id 1608 → 1062)
--   3) 27 generic dup groups (Aicon Yachts vs Aicon, Beneteau vs Bénéteau,
--      Elan vs Elan Marine, Leopard Yachts vs Leopard, Nimbus vs Nimbus
--      Group, Prestige Yachts vs Prestige, etc.) — see ManufacturerAlias-
--      Resolver.kt for the full alias list mirrored in code.
--   4) Model cabin-config suffix dedup ( "- N + M cab.*" stripped, rows
--      with the same (manufacturer, base_name) collapsed into the cleanest
--      surviving variant).
--
-- Each block runs in the implicit Flyway transaction; a failure rolls the
-- whole migration back. Yacht.model_id and external_mapping rows are
-- migrated before the parent rows are deleted so foreign keys stay valid
-- throughout.

-- ===========================================================================
-- 1. Lagoon-Bénéteau (1738) → Lagoon (1084)
-- ===========================================================================
DO $$
DECLARE
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  SELECT id INTO src_id FROM manufacturer WHERE name = 'Lagoon-Bénéteau' LIMIT 1;
  SELECT id INTO dst_id FROM manufacturer WHERE name = 'Lagoon' LIMIT 1;
  IF src_id IS NOT NULL AND dst_id IS NOT NULL AND src_id <> dst_id THEN
    -- Drop external_mapping rows that would collide with the canonical row.
    DELETE FROM external_mapping em1
      WHERE em1.type = 'Manufacturer' AND em1.system_id = src_id
      AND EXISTS (
        SELECT 1 FROM external_mapping em2
        WHERE em2.type = 'Manufacturer' AND em2.system_id = dst_id
          AND em2.external_id = em1.external_id
          AND em2.external_system_id = em1.external_system_id
      );
    UPDATE external_mapping SET system_id = dst_id
      WHERE type = 'Manufacturer' AND system_id = src_id;
    UPDATE model SET manufacturer_id = dst_id WHERE manufacturer_id = src_id;
    DELETE FROM manufacturer WHERE id = src_id;
  END IF;
END $$;

-- ===========================================================================
-- 2. Catana Group (1608) → Bali (1062)
-- ===========================================================================
DO $$
DECLARE
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  SELECT id INTO src_id FROM manufacturer WHERE name = 'Catana Group' LIMIT 1;
  SELECT id INTO dst_id FROM manufacturer WHERE name = 'Bali' LIMIT 1;
  IF src_id IS NOT NULL AND dst_id IS NOT NULL AND src_id <> dst_id THEN
    DELETE FROM external_mapping em1
      WHERE em1.type = 'Manufacturer' AND em1.system_id = src_id
      AND EXISTS (
        SELECT 1 FROM external_mapping em2
        WHERE em2.type = 'Manufacturer' AND em2.system_id = dst_id
          AND em2.external_id = em1.external_id
          AND em2.external_system_id = em1.external_system_id
      );
    UPDATE external_mapping SET system_id = dst_id
      WHERE type = 'Manufacturer' AND system_id = src_id;
    UPDATE model SET manufacturer_id = dst_id WHERE manufacturer_id = src_id;
    DELETE FROM manufacturer WHERE id = src_id;
  END IF;
END $$;

-- ===========================================================================
-- 3. Mass dedup of 27 manufacturer groups (suffix-noise / accent variants).
--    Canonical row per group = highest yacht-count, tie-break MIN(id).
--    Same algorithm executed on dev DB 4.5.2026.
-- ===========================================================================
DO $$
DECLARE
  plan_count BIGINT;
BEGIN
  CREATE TEMP TABLE _manuf_dedup_plan ON COMMIT DROP AS
  WITH normalized AS (
    SELECT m.id, m.name,
      LOWER(
        REGEXP_REPLACE(
          REGEXP_REPLACE(
            TRANSLATE(m.name,
              'áàäâãéèëêíìïîóòöôõúùüûñçÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇ',
              'aaaaaeeeeiiiioooooouuuunc' || 'AAAAAEEEEIIIIOOOOOUUUUNC'),
            '\s+(yachts?|yachtbau|catamarans?|group|composites?|charter|boats?|marine|sailing|design|dynamics)$',
            '', 'i'
          ),
          '\s+', ' ', 'g'
        )
      ) AS base
    FROM manufacturer m
  ),
  counts AS (
    SELECT n.id, n.name, n.base, COALESCE(COUNT(DISTINCT y.id), 0) AS yachts
    FROM normalized n
    LEFT JOIN model mod ON mod.manufacturer_id = n.id
    LEFT JOIN yacht y ON y.model_id = mod.id
    GROUP BY n.id, n.name, n.base
  ),
  groups AS (
    SELECT base FROM counts GROUP BY base HAVING COUNT(*) > 1
  ),
  canonical AS (
    SELECT DISTINCT ON (c.base)
      c.base, c.id AS canonical_id, c.name AS canonical_name
    FROM counts c
    JOIN groups g ON g.base = c.base
    ORDER BY c.base, c.yachts DESC, c.id ASC
  )
  SELECT c.id AS old_id, can.canonical_id
  FROM counts c
  JOIN canonical can ON can.base = c.base
  WHERE c.id <> can.canonical_id;

  SELECT COUNT(*) INTO plan_count FROM _manuf_dedup_plan;
  RAISE NOTICE 'Manufacturer mass-dedup: % rows to merge', plan_count;

  IF plan_count > 0 THEN
    UPDATE model SET manufacturer_id = p.canonical_id
      FROM _manuf_dedup_plan p
      WHERE model.manufacturer_id = p.old_id;

    DELETE FROM external_mapping em1
      WHERE em1.type = 'Manufacturer'
        AND em1.system_id IN (SELECT old_id FROM _manuf_dedup_plan)
        AND EXISTS (
          SELECT 1 FROM external_mapping em2
          JOIN _manuf_dedup_plan p ON p.old_id = em1.system_id
          WHERE em2.type = 'Manufacturer'
            AND em2.external_id = em1.external_id
            AND em2.external_system_id = em1.external_system_id
            AND em2.system_id = p.canonical_id
        );

    UPDATE external_mapping em SET system_id = p.canonical_id
      FROM _manuf_dedup_plan p
      WHERE em.type = 'Manufacturer' AND em.system_id = p.old_id;

    DELETE FROM manufacturer WHERE id IN (SELECT old_id FROM _manuf_dedup_plan);
  END IF;
END $$;

-- ===========================================================================
-- 4. Model cabin-config suffix dedup. Strip "- N cab." / "- N + M cab.*"
--    from model.name; for each (manufacturer, stripped_name) group with
--    >1 row, keep the one already matching the stripped form (or MIN(id)
--    if none match), redirect yacht.model_id + external_mapping, delete
--    the rest, and rename the canonical row to the stripped form.
-- ===========================================================================
DO $$
DECLARE
  plan_count BIGINT;
BEGIN
  CREATE TEMP TABLE _model_dedup_plan ON COMMIT DROP AS
  WITH stripped AS (
    SELECT id, manufacturer_id, name,
      REGEXP_REPLACE(TRIM(name), ' - \d+(\s*\+\s*\d+)?\s*cab\.?\*?\s*$', '', 'i') AS base
    FROM model
  ),
  groups AS (
    SELECT manufacturer_id, base
    FROM stripped GROUP BY manufacturer_id, base HAVING COUNT(*) > 1
  ),
  canonical AS (
    SELECT s.manufacturer_id, s.base,
      COALESCE(
        (SELECT s2.id FROM stripped s2
          WHERE s2.manufacturer_id = s.manufacturer_id AND s2.base = s.base
            AND TRIM(s2.name) = s.base
          ORDER BY s2.id LIMIT 1),
        MIN(s.id)
      ) AS canonical_id
    FROM stripped s
    JOIN groups g ON s.manufacturer_id = g.manufacturer_id AND s.base = g.base
    GROUP BY s.manufacturer_id, s.base
  )
  SELECT s.id AS old_id, c.canonical_id, s.base
  FROM stripped s
  JOIN canonical c ON s.manufacturer_id = c.manufacturer_id AND s.base = c.base
  WHERE s.id <> c.canonical_id;

  SELECT COUNT(*) INTO plan_count FROM _model_dedup_plan;
  RAISE NOTICE 'Model cabin-suffix dedup: % rows to merge', plan_count;

  IF plan_count > 0 THEN
    UPDATE yacht SET model_id = p.canonical_id
      FROM _model_dedup_plan p
      WHERE yacht.model_id = p.old_id;

    DELETE FROM external_mapping em1
      WHERE em1.type = 'Model' AND em1.system_id IN (SELECT old_id FROM _model_dedup_plan)
        AND EXISTS (
          SELECT 1 FROM external_mapping em2
          JOIN _model_dedup_plan p ON p.old_id = em1.system_id
          WHERE em2.type = 'Model'
            AND em2.external_id = em1.external_id
            AND em2.external_system_id = em1.external_system_id
            AND em2.system_id = p.canonical_id
        );

    UPDATE external_mapping em SET system_id = p.canonical_id
      FROM _model_dedup_plan p
      WHERE em.type = 'Model' AND em.system_id = p.old_id;

    -- Rename surviving canonical rows to the stripped form (if not already).
    UPDATE model SET name = sub.base
      FROM (SELECT DISTINCT canonical_id, base FROM _model_dedup_plan) sub
      WHERE model.id = sub.canonical_id AND TRIM(model.name) <> sub.base;

    DELETE FROM model WHERE id IN (SELECT old_id FROM _model_dedup_plan);
  END IF;
END $$;
