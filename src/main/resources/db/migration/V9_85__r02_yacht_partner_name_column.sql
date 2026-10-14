-- V9_85 R02 (14.10.2026): yacht.partner_name = the raw partner yacht name (MMK / NauSys). INTERNAL only - never in a public DTO
-- (owner rule 25.9.2026: never show an owner or agency). yacht.name becomes the public name (YachtNamePolicy, V9_87); the syncs
-- write both on every yacht they touch. NULL = the name was never changed by the policy, so yacht.name IS the raw name - twin,
-- successor and twin-resolver matching read COALESCE(partner_name, name). DDL only, no backfill.
--
-- ADD COLUMN (nullable, no default) is catalog-only but needs ACCESS EXCLUSIVE on yacht for an instant. A sync transaction still
-- writing yacht holds it off, and Flyway runs before the API serves anything on cusma2 (the only API node): giving up = the API
-- does not start. Same recipe as V9_71: PRE-APPLY by hand as boat4you_owner in a quiet moment, any day before wave 2
--     psql -1 -v ON_ERROR_STOP=1 -f V9_85__r02_yacht_partner_name_column.sql
-- (harmless for the running old jars: ddl-auto validate checks mapped columns only) - Flyway then finds the column and takes no
-- lock at all. Otherwise each attempt waits at most 3 s and it tries 10 times, 3 s apart, before giving up.
DO $$
DECLARE
    attempt INT := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'yacht' AND column_name = 'partner_name') THEN
        RAISE NOTICE 'V9_85: yacht.partner_name already present (pre-applied), nothing to do';
        RETURN;
    END IF;
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'ALTER TABLE public.yacht ADD COLUMN IF NOT EXISTS partner_name TEXT';
            EXECUTE $c$COMMENT ON COLUMN public.yacht.partner_name IS
                'Raw partner yacht name (MMK/NauSys), R02. Internal only - never in a public DTO. NULL = yacht.name is the raw name.'$c$;
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_85: yacht is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
    RAISE NOTICE 'V9_85: yacht.partner_name added';
END $$;
