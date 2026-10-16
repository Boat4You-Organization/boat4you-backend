-- V9_90 (16.10.2026) - yacht.public_slug: the boat's public URL slug, frozen. Owner rule 16.10.2026 (Mario, "ovo mijenjanje
-- URL-a ubija nas"): a boat's URL never changes again. Until now the slug was computed on every read from manufacturer +
-- model + name + id (SlugUtils.toSlugWithId), so a partner rename, a model merge or a naming rule (R31, V9_87) moved it.
-- The jar reads it through YachtPublicSlug (public_slug, else the computed slug - byte-for-byte today's while NULL) and
-- writes it only in three places: the MMK / NauSys sync and the admin custom-yacht create (once, for a new boat), and
-- the SYSTEM_ADMIN maintenance endpoints (backfill of NULL rows, one-off refresh). Hibernate never writes the column
-- (insertable/updatable = false), so a sync re-saving a boat cannot put NULL back over a frozen slug.
-- No data backfill here: the Kotlin slug rule is the only source (an SQL twin would be fragile) - POST
-- /admin/yachts/public-slug/backfill fills the NULL rows after the deploy. Not in the V9_71 / V9_88 lastmod trigger: a
-- frozen slug equal to the computed one is no change of the page. Table-level grants (V1_04: GRANT ... ON ALL TABLES +
-- DEFAULT PRIVILEGES) cover the new column; no GRANT needed.
--
-- ADD COLUMN (nullable, no default) is catalog-only but needs ACCESS EXCLUSIVE on yacht for an instant, and CREATE INDEX
-- takes SHARE (writes wait while one btree over yacht builds, a fraction of a second). A sync transaction still writing yacht
-- holds both off, and Flyway runs before the API serves anything on cusma2 (the only API node): giving up = the API does
-- not start. Same recipe as V9_85: PRE-APPLY by hand as boat4you_owner in a quiet moment, outside the sync windows,
-- before the restart
--     psql -1 -v ON_ERROR_STOP=1 -f V9_90__yacht_public_slug.sql
-- (harmless for the running old jars: ddl-auto validate checks mapped columns only) - Flyway then finds the column and the
-- index and takes no lock at all. Otherwise each attempt waits at most 3 s and it tries 10 times, 3 s apart, before
-- giving up. Rollback: not needed for the old jar (it never reads the column). If the column must go:
--     ALTER TABLE yacht DROP COLUMN public_slug; DELETE FROM flyway_schema_history WHERE version = '9.90';
-- (the index goes with the column; without the DELETE a later deploy of this jar skips V9_90 and ddl validate stops the API).
-- Deploy order: this version goes live in the same jar as V9_89 (feat/catalogue-images) or after it - recorded before 9.89
-- with out-of-order off, the jar that brings 9.89 does not start (DEPLOY_NOTES).
DO $$
DECLARE
    attempt INT := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'yacht' AND column_name = 'public_slug')
       AND to_regclass('public.yacht_public_slug_idx') IS NOT NULL THEN
        RAISE NOTICE 'V9_90: yacht.public_slug and its index already present (pre-applied), nothing to do';
        RETURN;
    END IF;
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'ALTER TABLE public.yacht ADD COLUMN IF NOT EXISTS public_slug VARCHAR(255)';
            EXECUTE 'CREATE INDEX IF NOT EXISTS yacht_public_slug_idx ON public.yacht (public_slug)';
            EXECUTE $c$COMMENT ON COLUMN public.yacht.public_slug IS
                'Frozen public URL slug (owner rule 16.10.2026): set once (sync on creation / admin backfill), never recomputed from name/model. NULL = not frozen yet, the computed slug is served.'$c$;
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_90: yacht is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
    RAISE NOTICE 'V9_90: yacht.public_slug added';
END $$;
