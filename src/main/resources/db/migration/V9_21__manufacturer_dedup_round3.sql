-- Manufacturer dedup round 3 — Mario rule 27.6.2026.
--
-- Same class of duplicate as V1_99 (one builder under several manufacturer
-- rows) but for the remaining brands still visible in the filter (both rows
-- carry yachts): Northman, Ferretti, Vektor, Sunseeker, Dominator, Balt.
-- Two of them (Vektor, Balt) are pure whitespace/spacing dups — the sync
-- compared names lowercased but NOT trimmed, so a trailing space forked a new
-- row. The same commit adds .trim() to the sync lookup so this stops
-- happening; this migration is the one-time cleanup of the existing rows.
--
-- Canonical target per group = the cleanest existing brand name (Mario):
--   Northman Shipyard       → Northman
--   Ferretti Yachts Group   → Ferretti Yachts
--   SAS - Vektor            → SAS-Vektor
--   Sunseeker International  → Sunseeker
--   DOMINATOR SHIPYARD      → Dominator
--   Balt Yacht              → Balt Yachts
--
-- Proven V1_88/V1_99 merge body (remap external_mapping collision-safe,
-- repoint model.manufacturer_id, delete source). Matched by TRIM(name) so
-- trailing/leading-space variants are caught robustly. Idempotent (NULL/<>
-- guards) and a no-op on any environment lacking the source rows. FK rows
-- migrated before the parent is deleted; whole migration in one transaction.
--
-- Numbered V9_21 (next in the V9_* data-hygiene track after V9_20) so it sorts
-- above all schema migrations and applies in order on cusma2 (cusma3 stays
-- Flyway-pinned and skips it, like every V__ > 1.43).

DO $$
DECLARE
  pair RECORD;
  src_id BIGINT;
  dst_id BIGINT;
BEGIN
  FOR pair IN (
    SELECT * FROM (VALUES
      ('Northman Shipyard',      'Northman'),
      ('Ferretti Yachts Group',  'Ferretti Yachts'),
      ('SAS - Vektor',           'SAS-Vektor'),
      ('Sunseeker International', 'Sunseeker'),
      ('DOMINATOR SHIPYARD',     'Dominator'),
      ('Balt Yacht',             'Balt Yachts')
    ) AS p(src_name, dst_name)
  ) LOOP
    SELECT id INTO src_id FROM manufacturer WHERE TRIM(name) = pair.src_name ORDER BY id LIMIT 1;
    SELECT id INTO dst_id FROM manufacturer WHERE TRIM(name) = pair.dst_name ORDER BY id LIMIT 1;
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
  END LOOP;
END $$;
