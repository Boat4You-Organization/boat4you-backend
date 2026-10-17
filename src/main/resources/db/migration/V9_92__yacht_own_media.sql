-- V9_92 (17.10.2026) - our own photos, film and crew profile on any boat. Owner request 17.10.2026 (Mario, gulet Allure
-- 11769, a partner-synced EXTERNAL boat with 3 partner images): our 54 photos, the Vimeo film and the crew profile PDF on
-- all 7 sites. Until now an admin could add images to CUSTOM boats only, and every MMK / NauSys sync deleted each
-- yacht_image row whose external_url the partner no longer lists - an uploaded photo (no external_url) included.
--
-- yacht_image.source: PARTNER (the default: every existing row and every row a partner sync inserts) or OWN (an admin
--   upload, POST /admin/yachts/{id}/images and /main-image). The syncs never delete, re-position below the partner's or
--   re-flag an OWN row; the partner's pictures follow the OWN block in the partner's order, and an OWN main stays the
--   card photo (MainImageRules). Hibernate inserts the column and never updates it (updatable = false). The partial index
--   serves the per-boat OWN lookups and stays tiny (no OWN row exists right after this file).
-- yacht.video_url: the boat's film (YouTube / Vimeo; PATCH /admin/yachts/{id}/media validates it), public as videoUrl
--   before custom_yacht_details.video_url.
-- yacht.crew_profile_url: stored path of the crew profile PDF under the upload dir (documents/y-{id}/{uuid}.pdf), served
--   by GET /public/yachts/{id}/crew-profile.pdf. Both yacht columns are read-only for Hibernate (insertable / updatable =
--   false) and written by the admin endpoints only (SQL): a partner sync re-saving the boat can never put back the NULL it
--   loaded. Not in the V9_71 / V9_88 lastmod trigger: body content, like the gallery.
--
-- No data. Images of CUSTOM boats (external_url NULL, uploaded through /admin/custom-yachts) stay PARTNER: no sync touches
-- a custom boat and the custom endpoints do not read the column.
--
-- Locks: ADD COLUMN with a constant default (NOT NULL DEFAULT 'PARTNER') and a nullable ADD COLUMN are catalog-only on
-- PostgreSQL 11+ (no table rewrite), but each takes ACCESS EXCLUSIVE on its table, and the partial index is built in the
-- same transaction while that lock is held: READS of yacht_image and yacht (boat detail, /public/image lookups) wait as
-- well as writes, until COMMIT - for one scan of yacht_image (~360,000 rows, well under a second). An MMK / NauSys agency
-- sync holds RowExclusive on both tables until it commits (minutes), and Flyway runs before the API serves anything on
-- cusma2 (the only API node). Same recipe as V9_90 / V9_91: PRE-APPLY by hand as boat4you_owner in a quiet moment,
-- outside the sync windows, before the restart
--     psql -1 -v ON_ERROR_STOP=1 -f V9_92__yacht_own_media.sql
-- (harmless for the running old jars: ddl-auto validate checks mapped columns only, and the default fills the rows they
-- insert) - Flyway then finds the columns and the index and takes no lock at all. Otherwise each attempt waits at most
-- 3 s and it tries 10 times, 3 s apart, before giving up; a deadlock with a writer that holds the second table (the
-- image download job: yacht_image, then yacht) is retried the same way. Table-level grants (V1_04) cover the new columns.
-- Rollback: not needed for the old jar (it never reads the columns) - but an old jar on the sync node (cusma3) deletes the
-- OWN rows again on the boat's next sync. If the columns must go:
--     ALTER TABLE yacht_image DROP COLUMN source; ALTER TABLE yacht DROP COLUMN video_url, DROP COLUMN crew_profile_url;
--     DELETE FROM flyway_schema_history WHERE version = '9.92';
-- (the index goes with the column; without the DELETE a later deploy of this jar skips V9_92 and ddl validate stops the API).
DO $$
DECLARE
    attempt INT := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'yacht_image' AND column_name = 'source')
       AND (SELECT count(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'yacht'
              AND column_name IN ('video_url', 'crew_profile_url')) = 2
       AND to_regclass('public.yacht_image_own_idx') IS NOT NULL THEN
        RAISE NOTICE 'V9_92: yacht_image.source, yacht.video_url / crew_profile_url and the index already present (pre-applied), nothing to do';
        RETURN;
    END IF;
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'ALTER TABLE public.yacht_image ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT ''PARTNER''';
            EXECUTE 'CREATE INDEX IF NOT EXISTS yacht_image_own_idx ON public.yacht_image (yacht_id) WHERE source = ''OWN''';
            EXECUTE 'ALTER TABLE public.yacht
                         ADD COLUMN IF NOT EXISTS video_url VARCHAR(512),
                         ADD COLUMN IF NOT EXISTS crew_profile_url VARCHAR(255)';
            EXECUTE $c$COMMENT ON COLUMN public.yacht_image.source IS
                'PARTNER = a partner picture (MMK / NauSys sync, the default); OWN = our own photo uploaded by an admin: the partner syncs never delete, re-position below the partner''s or re-flag it.'$c$;
            EXECUTE $c$COMMENT ON COLUMN public.yacht.video_url IS
                'The boat''s film (YouTube / Vimeo URL), set by an admin (PATCH /admin/yachts/{id}/media). Partner syncs never write it.'$c$;
            EXECUTE $c$COMMENT ON COLUMN public.yacht.crew_profile_url IS
                'Stored path of the crew profile PDF under the upload dir (documents/y-{id}/...), served by GET /public/yachts/{id}/crew-profile.pdf. Partner syncs never write it.'$c$;
            EXIT;
        EXCEPTION
            WHEN lock_not_available OR deadlock_detected THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_92: yacht_image / yacht is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
    RAISE NOTICE 'V9_92: yacht_image.source, yacht.video_url and yacht.crew_profile_url added';
END $$;
