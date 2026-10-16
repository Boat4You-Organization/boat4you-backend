-- V9_91 (16.10.2026) - yacht_image.hidden / hidden_reason / layout_override. Owner incident 16.10.2026 (Mario,
-- /boat/hanse-yachts-hanse-388-fufu-4345): the main image of a boat was the partner's placeholder "Picture is not shown
-- due to possible legal issues". NauSys flags its MODEL picture (rest/yachtModel/{id}/pictures/main.jpg) as the boat's
-- mainPicture; for some models that URL answers 307 to the generic blockedPhoto.png (1930x1306), and we stored it like
-- a photo. On 16.10.2026: 21 such files on 11 active boats, 6 of them with no other picture.
--
-- hidden: the image is never served to the public API (detail, reservations, trip hub, model donors) and is never the
--   main image (MainImageRules). Set by the image download (KnownPlaceholderImages: blocked-photo URL or SHA-256 of the
--   downloaded bytes), by POST /admin/yachts/images/detect-blocked (files already stored) or PATCH
--   /admin/yacht-images/{id}. The partner syncs never write it: the row keeps its external_url, so the next sync matches
--   it and leaves it alone (Hibernate maps the three columns read-only, insertable/updatable = false).
-- hidden_reason: why (PARTNER_BLOCKED_PHOTO, ADMIN), admin-only.
-- layout_override: NULL = the file-name rule decides (LayoutImageRules); true / false = an admin or the visual detector
--   (POST /admin/yachts/images/detect-layout) said so - e.g. a deck plan the partner uploaded as "main.png".
--
-- No data here. The repair of main images (yacht.main_image_id pointing at a hidden image or a layout drawing) is the
-- Kotlin endpoint POST /admin/yachts/main-image/fix-layout, after detect-blocked has hidden the stored placeholders:
-- right after this file no row is hidden yet, and an SQL twin of LayoutImageRules would drift from the Kotlin rule.
-- yacht_search_view (R__1_03) reads yacht.main_image_id, never yacht_image.main_image, so it needs no change: the sync
-- and the repair never write a hidden or layout image there while the boat has a visible photo.
--
-- ADD COLUMN ... NOT NULL DEFAULT false is catalog-only on PostgreSQL 11+ (no table rewrite), but it needs ACCESS
-- EXCLUSIVE on yacht_image for an instant, and the partial index takes SHARE while it scans the table (~360,000 rows,
-- the index itself starts empty). An MMK / NauSys agency sync holds RowExclusive on yacht_image until it commits
-- (minutes), and Flyway runs before the API serves anything on cusma2 (the only API node). Same recipe as V9_90:
-- PRE-APPLY by hand as boat4you_owner in a quiet moment, outside the sync windows, before the restart
--     psql -1 -v ON_ERROR_STOP=1 -f V9_91__yacht_image_hidden_layout_override.sql
-- (harmless for the running old jars: ddl-auto validate checks mapped columns only, and the default fills new rows) -
-- Flyway then finds the columns and the index and takes no lock at all. Otherwise each attempt waits at most 3 s and
-- it tries 10 times, 3 s apart, before giving up. Table-level grants (V1_04) cover the new columns.
-- Rollback: not needed for the old jar (it never reads the columns). If they must go:
--     ALTER TABLE yacht_image DROP COLUMN hidden, DROP COLUMN hidden_reason, DROP COLUMN layout_override;
--     DELETE FROM flyway_schema_history WHERE version = '9.91';
-- (the index goes with the column; without the DELETE a later deploy of this jar skips V9_91 and ddl validate stops the API).
DO $$
DECLARE
    attempt INT := 0;
BEGIN
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'yacht_image'
          AND column_name IN ('hidden', 'hidden_reason', 'layout_override')) = 3
       AND to_regclass('public.yacht_image_hidden_idx') IS NOT NULL THEN
        RAISE NOTICE 'V9_91: yacht_image.hidden / hidden_reason / layout_override and the index already present (pre-applied), nothing to do';
        RETURN;
    END IF;
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'ALTER TABLE public.yacht_image
                         ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT false,
                         ADD COLUMN IF NOT EXISTS hidden_reason TEXT,
                         ADD COLUMN IF NOT EXISTS layout_override BOOLEAN';
            EXECUTE 'CREATE INDEX IF NOT EXISTS yacht_image_hidden_idx ON public.yacht_image (yacht_id) WHERE hidden';
            EXECUTE $c$COMMENT ON COLUMN public.yacht_image.hidden IS
                'Never served to the public and never the main image (partner placeholder such as the NauSys blocked photo, or an admin decision). Partner syncs never write it.'$c$;
            EXECUTE $c$COMMENT ON COLUMN public.yacht_image.hidden_reason IS
                'Why the image is hidden: PARTNER_BLOCKED_PHOTO, ADMIN. Admin-only.'$c$;
            EXECUTE $c$COMMENT ON COLUMN public.yacht_image.layout_override IS
                'NULL = the file-name rule (LayoutImageRules) decides whether the image is the layout drawing; true / false = set by an admin or the visual layout detector.'$c$;
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_91: yacht_image is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
    RAISE NOTICE 'V9_91: yacht_image.hidden / hidden_reason / layout_override added';
END $$;
