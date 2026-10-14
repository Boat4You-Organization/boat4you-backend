-- V9_84 R02 (owner rule 14.10.2026, „Di piše NSS ili SUNSAIL stavi BOAT4YOU tu i gotovo"; flag F3): charter-brand model labels
-- -> the builder model they are, for the CERTAIN pairs only. Same merge pattern as V9_45: repoint yacht.model_id and
-- external_mapping(type='Model') from the brand row to the builder row; the empty brand row stays (no rename in place - a
-- second "Leopard 42" row would bring back the V9_45 duplicate-model crash).
--   4596 "Sunsail 424"   (no maker, MMK) -> 2234 "Leopard 42"   (Leopard Catamarans) - partner layout file "sunsail_424_(leopard_42)"
--   4593 "Moorings 42.3" (no maker, MMK) -> 2716 "Oceanis 40.1" (Beneteau)           - the partner's other "Moorings 42.3" boats
--   4594 "Moorings 352"  (no maker, MMK) -> 2701 "Oceanis 35.1" (Beneteau)           - its only boat is named "Oceanis 35.1 Club"
-- Uncertain labels (5542 Sunsail 41, 5543 Moorings 5000, 1069/1070 Dream 60/82) are NOT touched (owner flag F3, waits for Mario).
--
-- Guards: a pair runs only when the source still has its name, the target its name and maker, and the source has no NauSys
-- mapping (NauSys modelsSync would otherwise write its partner builder onto the target - code guard G2 ships in the same jar,
-- still skipped here on purpose). Yacht ids differ between databases, so nothing is pinned to a yacht id.
-- What keeps it fixed: the MMK yacht sync resolves the model through external_mapping (repointed here) and, for a NEW MMK model
-- id with the same label, through partner/brand_model_alias.txt (G3) - never by creating a "Sunsail 424" row again. The MMK
-- model lookup is cached 60 min (ModelQueryingService), so cusma3 must run the new jar in the same gate window (G1, Wave-2 rule).
--
-- yacht.model_id changes fire the V9_71 trigger (lastmod) - the slug changes ("sunsail-424-..." -> "leopard-42-..."), every old
-- URL keeps its id and 308s. Backups without column defaults (no dependency on the live sequence):
--   ops_model_relabel_backup_20261014 (yacht_id, old_model_id, new_model_id), ops_model_mapping_backup_20261014 (external_mapping rows).
-- Idempotent: a second run finds no yacht and no mapping on the source id (0 rows) and never overwrites a backup row.
-- Rollback (same window as the jar rollback, both nodes):
--   UPDATE yacht y SET model_id = b.old_model_id FROM ops_model_relabel_backup_20261014 b WHERE b.yacht_id = y.id;
--   UPDATE external_mapping em SET system_id = b.system_id FROM ops_model_mapping_backup_20261014 b WHERE b.id = em.id;
--   INSERT INTO external_mapping SELECT b.* FROM ops_model_mapping_backup_20261014 b
--    WHERE NOT EXISTS (SELECT 1 FROM external_mapping em WHERE em.id = b.id);
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS public.ops_model_relabel_backup_20261014 (
    yacht_id     BIGINT      PRIMARY KEY,
    old_model_id BIGINT      NOT NULL,
    new_model_id BIGINT      NOT NULL,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- LIKE without INCLUDING DEFAULTS: the copy has external_mapping's columns and NOT NULLs, no nextval() default.
CREATE TABLE IF NOT EXISTS public.ops_model_mapping_backup_20261014 (LIKE public.external_mapping);

DO $$
DECLARE
    r       RECORD;
    n_y     INTEGER;
    n_m     INTEGER;
    n_d     INTEGER;
    total_y INTEGER := 0;
BEGIN
    FOR r IN
        SELECT *
        FROM (VALUES
              (4596::bigint, 'Sunsail 424',   2234::bigint, 'Leopard 42',   ARRAY['Leopard Catamarans', 'Leopard Catamarans / Robertson & Caine']),
              (4593::bigint, 'Moorings 42.3', 2716::bigint, 'Oceanis 40.1', ARRAY['Beneteau', 'Bénéteau']),
              (4594::bigint, 'Moorings 352',  2701::bigint, 'Oceanis 35.1', ARRAY['Beneteau', 'Bénéteau'])
             ) AS v (src, src_name, tgt, tgt_name, tgt_makers)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM public.model WHERE id = r.src AND name = r.src_name) THEN
            RAISE NOTICE 'V9_84 skip % "%": source renamed or gone', r.src, r.src_name;
            CONTINUE;
        END IF;
        IF NOT EXISTS (SELECT 1
                       FROM public.model m
                       JOIN public.manufacturer mf ON mf.id = m.manufacturer_id
                       WHERE m.id = r.tgt AND m.name = r.tgt_name AND mf.name = ANY (r.tgt_makers)) THEN
            RAISE NOTICE 'V9_84 skip % "%": target % "%" not as expected (name or maker)', r.src, r.src_name, r.tgt, r.tgt_name;
            CONTINUE;
        END IF;
        IF EXISTS (SELECT 1 FROM public.external_mapping
                   WHERE type = 'Model' AND system_id = r.src AND external_system_id = 2) THEN
            RAISE NOTICE 'V9_84 skip % "%": NauSys mapping present', r.src, r.src_name;
            CONTINUE;
        END IF;

        INSERT INTO public.ops_model_relabel_backup_20261014 (yacht_id, old_model_id, new_model_id)
        SELECT y.id, y.model_id, r.tgt FROM public.yacht y WHERE y.model_id = r.src
        ON CONFLICT (yacht_id) DO NOTHING;
        INSERT INTO public.ops_model_mapping_backup_20261014
        SELECT em.* FROM public.external_mapping em
        WHERE em.type = 'Model' AND em.system_id = r.src
          AND NOT EXISTS (SELECT 1 FROM public.ops_model_mapping_backup_20261014 b WHERE b.id = em.id);

        -- repoint; a mapping the target already owns (same partner id) is a leftover and goes
        UPDATE public.external_mapping em SET system_id = r.tgt
        WHERE em.type = 'Model' AND em.system_id = r.src
          AND NOT EXISTS (SELECT 1 FROM public.external_mapping t
                          WHERE t.type = 'Model' AND t.system_id = r.tgt
                            AND t.external_id = em.external_id AND t.external_system_id = em.external_system_id);
        GET DIAGNOSTICS n_m = ROW_COUNT;
        DELETE FROM public.external_mapping WHERE type = 'Model' AND system_id = r.src;
        GET DIAGNOSTICS n_d = ROW_COUNT;
        UPDATE public.yacht SET model_id = r.tgt WHERE model_id = r.src;
        GET DIAGNOSTICS n_y = ROW_COUNT;
        total_y := total_y + n_y;
        RAISE NOTICE 'V9_84 % "%" -> % "%": % yachts, % mappings repointed, % duplicate mappings removed',
            r.src, r.src_name, r.tgt, r.tgt_name, n_y, n_m, n_d;
    END LOOP;
    RAISE NOTICE 'V9_84 total yachts moved to the builder model: %', total_y;
END $$;
