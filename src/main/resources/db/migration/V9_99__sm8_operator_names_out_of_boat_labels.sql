-- V9_99 SM8 (27.10.2026, Mario „sredi sve“): no charter agency's name in a boat's public label or URL. The SEO regression
-- check SM8 (27.10. 10:07 UTC) found the agency in live sitemap slugs; a full scan of the b4y sitemap (9,970 boats) found 8:
--   * the partner sends the agency as the builder: "Odisej Ltd" (868 M/S Aurum Sky, 869 Anthea, 870 Black Swan) and
--     "Kolotura" (867 M/S Ad Astra) -> their models move to the builder our other custom-built boats carry, "Custom Made",
--     and so do the partner builder mappings: a new model of that partner builder is born under "Custom Made" (both syncs
--     resolve a builder by its mapping and never rename a manufacturer row, NauSys keeps an existing model's manufacturer,
--     MMK sets it on a new model only);
--   * the boat is named after its agency: 4202 "Sea novels" (NauSys agency Sea Novels, Athens) -> public name "Boat4You",
--     the raw name stays internal in partner_name (R02, V9_87). partner/yacht_name_brand.txt carries it from this jar on,
--     so the syncs keep writing "Boat4You"; r02_yacht_public_name_v2 below is the SQL twin of YachtNamePolicy.publicName;
--   * a charter brand in the model label (owner flag F3, left open 15.10.): "Sunsail 41" (20128 C's The Day) and
--     "Moorings 5000" (20131 Diamond Girl, 20133 Good Vibrations) -> "Boat4You 41" / "Boat4You 5000", rule F1 of 14.10.
--     („Di piše NSS ili SUNSAIL stavi BOAT4YOU tu i gotovo“). partner/brand_model_alias.txt maps the partner label to the
--     renamed row, so a new partner model id with that label lands there instead of creating "Sunsail 41" again.
-- URLs: yacht.public_slug is frozen (V9_90, owner rule 16.10.); this is the owner-approved exception. The new slug of each
-- boat is the one SlugUtils gives the new labels (Sm8OperatorLabelsMigrationTest checks every row against the Kotlin rule;
-- no SQL twin of the slug rule). A row is written only while it still holds its old slug (or none), so a re-run, or a
-- slug changed by hand meanwhile, writes nothing. The old URLs keep working: all 7 sites resolve /boat/{slug} (sisters
-- /yachts/{slug}) by the trailing id and 308 to the slug the API returns. lastmod (yacht_content_modified) moves for every
-- boat whose label or slug changed here (model / builder / slug are not in the V9_71 trigger).
--
-- Locks: model rows (a catalogue sync), external_mapping rows and yacht rows (an agency sync holds its boats for minutes)
-- - each step retries 10 x 3 s on lock_not_available inside an exception block, as V9_87. PRE-APPLY by hand as
-- boat4you_owner outside the sync windows, before the restart (psql -1 -v ON_ERROR_STOP=1 -f <this file>); Flyway on
-- the API start then finds every marker and changes nothing. Idempotent; a database without these rows (dev, tests)
-- only gets the function and the empty tables.
-- Both nodes in one gate window: the old jar's NauSys sync on cusma3 writes "Sea novels" back over 4202's public name
-- (its URL stays the new one).
-- Backup: ops_sm8_backup_20261027 (kind, row_id, name, partner_name, public_slug, ref_id) - never overwritten.
-- Rollback (both nodes back on the old jar first):
--   UPDATE model m SET manufacturer_id = b.ref_id FROM ops_sm8_backup_20261027 b WHERE b.kind = 'model_builder' AND b.row_id = m.id;
--   UPDATE external_mapping e SET system_id = b.ref_id FROM ops_sm8_backup_20261027 b WHERE b.kind = 'builder_mapping' AND b.row_id = e.id;
--   (a mapping deleted as a duplicate: re-INSERT it from the backup row: ref_id = old system_id, name = external_system_id:external_id)
--   UPDATE model m SET name = b.name FROM ops_sm8_backup_20261027 b WHERE b.kind = 'model_name' AND b.row_id = m.id;
--   UPDATE yacht y SET name = b.name FROM ops_sm8_backup_20261027 b WHERE b.kind = 'yacht_name' AND b.row_id = y.id;
--   UPDATE yacht y SET public_slug = b.public_slug FROM ops_sm8_backup_20261027 b WHERE b.kind = 'yacht_slug' AND b.row_id = y.id;
--   DELETE FROM flyway_schema_history WHERE version = '9.99'; DELETE FROM _sm8_v9_99_done;
CREATE TABLE IF NOT EXISTS public.ops_sm8_backup_20261027 (
    kind         TEXT        NOT NULL,
    row_id       BIGINT      NOT NULL,
    name         TEXT,
    partner_name TEXT,
    public_slug  TEXT,
    ref_id       BIGINT,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, row_id)
);
CREATE TABLE IF NOT EXISTS public._sm8_v9_99_done (
    step    TEXT        PRIMARY KEY,
    rows    INTEGER     NOT NULL,
    done_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- r02_yacht_public_name_v1 (V9_87) plus the names curated since (yacht_name_brand.txt). Same fold as v1 / YachtNamePolicy.fold.
CREATE OR REPLACE FUNCTION public.r02_yacht_public_name_v2(raw TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$f$
SELECT CASE
           WHEN raw IS NOT NULL
            AND btrim(regexp_replace(translate(lower(raw), 'šžčćđáàâäãåéèêëíìîïóòôöõúùûüçñ', 'szccdaaaaaaeeeeiiiiooooouuuucn'),
                                     '\s+', ' ', 'g'))
                IN ('sea novels')
               THEN 'Boat4You'
           ELSE public.r02_yacht_public_name_v1(raw)
       END
$f$;

DO $$
DECLARE
    n         INTEGER;
    n2        INTEGER;
    attempt   INT;
    custom_id BIGINT;
    agency_builders BIGINT[];
    -- id, the slug it was frozen with, the slug SlugUtils gives its new labels
    slugs     JSONB := $j$[
        {"id": 867, "old": "kolotura-ms-ad-astra-867", "new": "custom-made-ms-ad-astra-867"},
        {"id": 868, "old": "odisej-ltd-ms-custom-aurum-sky-868", "new": "custom-made-ms-custom-aurum-sky-868"},
        {"id": 869, "old": "odisej-ltd-my-custom-anthea-869", "new": "custom-made-my-custom-anthea-869"},
        {"id": 870, "old": "odisej-ltd-my-custom-black-swan-870", "new": "custom-made-my-custom-black-swan-870"},
        {"id": 4202, "old": "lagoon-42-sea-novels-4202", "new": "lagoon-42-boat4you-4202"},
        {"id": 20128, "old": "beneteau-sunsail-41-cs-the-day-20128", "new": "beneteau-boat4you-41-cs-the-day-20128"},
        {"id": 20131, "old": "beneteau-moorings-5000-diamond-girl-20131", "new": "beneteau-boat4you-5000-diamond-girl-20131"},
        {"id": 20133, "old": "beneteau-moorings-5000-good-vibrations-20133", "new": "beneteau-boat4you-5000-good-vibrations-20133"}
    ]$j$;
BEGIN
    -- 1. agency-as-builder -> "Custom Made" (models + the partner builder mappings)
    IF NOT EXISTS (SELECT 1 FROM public._sm8_v9_99_done WHERE step = 'builders') THEN
        SELECT min(id) INTO custom_id FROM public.manufacturer WHERE btrim(name) = 'Custom Made' HAVING count(*) = 1;
        agency_builders := ARRAY(SELECT id FROM public.manufacturer WHERE btrim(name) IN ('Odisej Ltd', 'Kolotura') ORDER BY id);
        IF custom_id IS NULL OR cardinality(agency_builders) = 0 THEN
            RAISE NOTICE 'V9_99 builders: "Custom Made" (exactly one) or the agency builders not found - nothing to move';
            INSERT INTO public._sm8_v9_99_done (step, rows) VALUES ('builders', 0);
        ELSE
            attempt := 0;
            LOOP
                BEGIN
                    PERFORM set_config('lock_timeout', '3s', true);
                    INSERT INTO public.ops_sm8_backup_20261027 (kind, row_id, name, ref_id)
                    SELECT 'model_builder', m.id, m.name, m.manufacturer_id FROM public.model m WHERE m.manufacturer_id = ANY (agency_builders)
                    ON CONFLICT (kind, row_id) DO NOTHING;
                    INSERT INTO public.ops_sm8_backup_20261027 (kind, row_id, name, ref_id)
                    SELECT 'builder_mapping', e.id, e.external_system_id || ':' || e.external_id, e.system_id
                    FROM public.external_mapping e WHERE e.type = 'Manufacturer' AND e.system_id = ANY (agency_builders)
                    ON CONFLICT (kind, row_id) DO NOTHING;
                    -- lastmod of the boats on those models (the label and the slug change below)
                    INSERT INTO public.yacht_content_modified AS c (yacht_id, modified_at)
                    SELECT y.id, clock_timestamp() FROM public.yacht y JOIN public.model m ON m.id = y.model_id
                    WHERE m.manufacturer_id = ANY (agency_builders)
                    ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at WHERE c.modified_at < EXCLUDED.modified_at;
                    UPDATE public.model SET manufacturer_id = custom_id WHERE manufacturer_id = ANY (agency_builders);
                    GET DIAGNOSTICS n = ROW_COUNT;
                    -- a partner builder id already mapped to "Custom Made" keeps that mapping; the agency one goes
                    DELETE FROM public.external_mapping e
                    WHERE e.type = 'Manufacturer' AND e.system_id = ANY (agency_builders)
                      AND EXISTS (SELECT 1 FROM public.external_mapping d
                                  WHERE d.type = 'Manufacturer' AND d.system_id = custom_id
                                    AND d.external_id = e.external_id AND d.external_system_id = e.external_system_id);
                    UPDATE public.external_mapping SET system_id = custom_id
                    WHERE type = 'Manufacturer' AND system_id = ANY (agency_builders);
                    GET DIAGNOSTICS n2 = ROW_COUNT;
                    RAISE NOTICE 'V9_99 builders % -> "Custom Made" %: % models, % partner mappings', agency_builders, custom_id, n, n2;
                    INSERT INTO public._sm8_v9_99_done (step, rows) VALUES ('builders', n);
                    EXIT;
                EXCEPTION
                    WHEN lock_not_available THEN
                        attempt := attempt + 1;
                        IF attempt >= 10 THEN
                            RAISE;
                        END IF;
                        RAISE NOTICE 'V9_99 builders: rows locked (attempt % of 10), retrying in 3 s', attempt;
                        PERFORM pg_sleep(3);
                END;
            END LOOP;
        END IF;
    END IF;

    -- 2. charter-brand model labels -> "Boat4You ..." (F1)
    IF NOT EXISTS (SELECT 1 FROM public._sm8_v9_99_done WHERE step = 'model_names') THEN
        attempt := 0;
        LOOP
            BEGIN
                PERFORM set_config('lock_timeout', '3s', true);
                INSERT INTO public.ops_sm8_backup_20261027 (kind, row_id, name)
                SELECT 'model_name', m.id, m.name FROM public.model m WHERE m.name IN ('Sunsail 41', 'Moorings 5000')
                ON CONFLICT (kind, row_id) DO NOTHING;
                INSERT INTO public.yacht_content_modified AS c (yacht_id, modified_at)
                SELECT y.id, clock_timestamp() FROM public.yacht y JOIN public.model m ON m.id = y.model_id
                WHERE m.name IN ('Sunsail 41', 'Moorings 5000')
                ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at WHERE c.modified_at < EXCLUDED.modified_at;
                UPDATE public.model SET name = public.r02_yacht_public_name_v1(name) WHERE name IN ('Sunsail 41', 'Moorings 5000');
                GET DIAGNOSTICS n = ROW_COUNT;
                RAISE NOTICE 'V9_99 model labels renamed: %', n;
                INSERT INTO public._sm8_v9_99_done (step, rows) VALUES ('model_names', n);
                EXIT;
            EXCEPTION
                WHEN lock_not_available THEN
                    attempt := attempt + 1;
                    IF attempt >= 10 THEN
                        RAISE;
                    END IF;
                    RAISE NOTICE 'V9_99 model labels: rows locked (attempt % of 10), retrying in 3 s', attempt;
                    PERFORM pg_sleep(3);
            END;
        END LOOP;
    END IF;

    -- 3. boats named after their agency -> "Boat4You" (the V9_71 trigger moves their lastmod)
    IF NOT EXISTS (SELECT 1 FROM public._sm8_v9_99_done WHERE step = 'yacht_names') THEN
        attempt := 0;
        LOOP
            BEGIN
                PERFORM set_config('lock_timeout', '3s', true);
                INSERT INTO public.ops_sm8_backup_20261027 (kind, row_id, name, partner_name)
                SELECT 'yacht_name', y.id, y.name, y.partner_name FROM public.yacht y
                WHERE public.r02_yacht_public_name_v2(COALESCE(y.partner_name, y.name)) = 'Boat4You'
                  AND public.r02_yacht_public_name_v1(COALESCE(y.partner_name, y.name)) IS DISTINCT FROM 'Boat4You'
                  AND y.name IS DISTINCT FROM 'Boat4You'
                ON CONFLICT (kind, row_id) DO NOTHING;
                UPDATE public.yacht y
                SET partner_name = COALESCE(y.partner_name, y.name),
                    name         = 'Boat4You'
                WHERE public.r02_yacht_public_name_v2(COALESCE(y.partner_name, y.name)) = 'Boat4You'
                  AND public.r02_yacht_public_name_v1(COALESCE(y.partner_name, y.name)) IS DISTINCT FROM 'Boat4You'
                  AND y.name IS DISTINCT FROM 'Boat4You';
                GET DIAGNOSTICS n = ROW_COUNT;
                RAISE NOTICE 'V9_99 yacht public names written: %', n;
                INSERT INTO public._sm8_v9_99_done (step, rows) VALUES ('yacht_names', n);
                EXIT;
            EXCEPTION
                WHEN lock_not_available THEN
                    attempt := attempt + 1;
                    IF attempt >= 10 THEN
                        RAISE;
                    END IF;
                    RAISE NOTICE 'V9_99 yacht names: rows locked (attempt % of 10), retrying in 3 s', attempt;
                    PERFORM pg_sleep(3);
            END;
        END LOOP;
    END IF;

    -- 4. the new frozen slugs (SlugUtils on the new labels; the test recomputes every row)
    IF NOT EXISTS (SELECT 1 FROM public._sm8_v9_99_done WHERE step = 'slugs') THEN
        attempt := 0;
        LOOP
            BEGIN
                PERFORM set_config('lock_timeout', '3s', true);
                INSERT INTO public.ops_sm8_backup_20261027 (kind, row_id, public_slug)
                SELECT 'yacht_slug', y.id, y.public_slug FROM public.yacht y JOIN jsonb_to_recordset(slugs) AS s(id BIGINT, old TEXT, new TEXT) ON s.id = y.id
                WHERE y.public_slug IS NULL OR y.public_slug = s.old
                ON CONFLICT (kind, row_id) DO NOTHING;
                INSERT INTO public.yacht_content_modified AS c (yacht_id, modified_at)
                SELECT y.id, clock_timestamp() FROM public.yacht y JOIN jsonb_to_recordset(slugs) AS s(id BIGINT, old TEXT, new TEXT) ON s.id = y.id
                WHERE y.public_slug IS NULL OR y.public_slug = s.old
                ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at WHERE c.modified_at < EXCLUDED.modified_at;
                UPDATE public.yacht y SET public_slug = s.new
                FROM jsonb_to_recordset(slugs) AS s(id BIGINT, old TEXT, new TEXT)
                WHERE s.id = y.id AND (y.public_slug IS NULL OR y.public_slug = s.old);
                GET DIAGNOSTICS n = ROW_COUNT;
                RAISE NOTICE 'V9_99 public slugs written: % (old URLs 308 by the trailing id)', n;
                INSERT INTO public._sm8_v9_99_done (step, rows) VALUES ('slugs', n);
                EXIT;
            EXCEPTION
                WHEN lock_not_available THEN
                    attempt := attempt + 1;
                    IF attempt >= 10 THEN
                        RAISE;
                    END IF;
                    RAISE NOTICE 'V9_99 slugs: rows locked (attempt % of 10), retrying in 3 s', attempt;
                    PERFORM pg_sleep(3);
            END;
        END LOOP;
    END IF;
END $$;
