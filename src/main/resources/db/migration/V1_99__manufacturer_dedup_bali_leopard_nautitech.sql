-- Manufacturer dedup round 2 — Mario rule 27.6.2026.
--
-- The website filter listed the SAME catamaran brand under several rows
-- because partner feeds (MMK + NauSys) name the builder differently and
-- V1_88's Catana→Bali block was a no-op on prod: it looked up name='Bali',
-- but the surviving prod row is 'Bali Catamarans' (NauSys also kept a large
-- legacy 'Catana Group' row). Same shape for Leopard / Robertson & Caine and
-- Nautitech. This migration converges every variant onto the canonical row
-- Mario chose, using the proven V1_88 merge pattern (remap external_mapping
-- collision-safe, repoint model.manufacturer_id, delete the empty source row).
--
-- Canonical targets (Mario 27.6.2026):
--   • Catana Group                            → Bali Catamarans
--   • Leopard Catamarans / Robertson & Caine  → Leopard Catamarans
--   • Robertson & Caine                       → Leopard Catamarans
--   • Nautitech Rochefort                     → Catamarans Nautitech,
--     then the surviving row is renamed to "Nautitech Catamarans" so all
--     three brands read consistently ("<Brand> Catamarans").
--
-- ManufacturerAliasResolver.kt is updated in the same commit so a later
-- partner sync re-lands these variants on the canonical row instead of
-- re-forking them (the alias is the steady-state guard; this migration is
-- the one-time cleanup).
--
-- By-name (not hard-coded id) so it runs correctly on prod AND is a no-op on
-- any environment that lacks the source rows. Every block is idempotent
-- (NULL/<> guards), so re-running on an already-merged DB changes nothing.
-- model_id / external_mapping rows are migrated before the parent row is
-- deleted, so foreign keys stay valid throughout. Each DO block runs inside
-- the implicit Flyway transaction; a failure rolls the whole migration back.

-- Reusable merge body is inlined per pair (kept verbatim from V1_88 so the
-- two dedup migrations can't drift).

-- ===========================================================================
-- 1. Catana Group → Bali Catamarans
-- ===========================================================================
DO $$
DECLARE
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  SELECT id INTO src_id FROM manufacturer WHERE name = 'Catana Group' LIMIT 1;
  SELECT id INTO dst_id FROM manufacturer WHERE name = 'Bali Catamarans' LIMIT 1;
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
-- 2. Leopard Catamarans / Robertson & Caine → Leopard Catamarans
-- ===========================================================================
DO $$
DECLARE
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  SELECT id INTO src_id FROM manufacturer WHERE name = 'Leopard Catamarans / Robertson & Caine' LIMIT 1;
  SELECT id INTO dst_id FROM manufacturer WHERE name = 'Leopard Catamarans' LIMIT 1;
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
-- 3. Robertson & Caine → Leopard Catamarans
-- ===========================================================================
DO $$
DECLARE
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  SELECT id INTO src_id FROM manufacturer WHERE name = 'Robertson & Caine' LIMIT 1;
  SELECT id INTO dst_id FROM manufacturer WHERE name = 'Leopard Catamarans' LIMIT 1;
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
-- 4. Nautitech Rochefort → Catamarans Nautitech
-- ===========================================================================
DO $$
DECLARE
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  SELECT id INTO src_id FROM manufacturer WHERE name = 'Nautitech Rochefort' LIMIT 1;
  SELECT id INTO dst_id FROM manufacturer WHERE name = 'Catamarans Nautitech' LIMIT 1;
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

-- ---------------------------------------------------------------------------
-- 4b. Rename the surviving Nautitech row so it reads "Nautitech Catamarans"
--     (consistent with "Bali Catamarans" / "Leopard Catamarans"). Idempotent:
--     no-op once the row is already renamed, and skipped if a target-named
--     row already exists (avoids a duplicate name).
-- ---------------------------------------------------------------------------
UPDATE manufacturer SET name = 'Nautitech Catamarans'
  WHERE name = 'Catamarans Nautitech'
  AND NOT EXISTS (SELECT 1 FROM manufacturer WHERE name = 'Nautitech Catamarans');
