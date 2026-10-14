-- V9_87 R02 (owner rule 14.10.2026, „Di piše NSS ili SUNSAIL stavi BOAT4YOU tu i gotovo"; flags F1 literal, F4 curated list):
-- yacht.name becomes the PUBLIC name, the raw partner name moves to yacht.partner_name (V9_85, internal only).
--   * a charter-brand token in a fleet label -> "Boat4You": "Sunsail 424/4/4 Premium" -> "Boat4You 424/4/4 Premium",
--     "Moorings 4500 Club" -> "Boat4You 4500 Club", "Moorings Oceanis 30.1 Club" -> "Boat4You Oceanis 30.1 Club";
--     "Moorings" counts as the brand only before a number or as the first word; NSS Charter / NSS / Sunsail / The Moorings /
--     Navigare anywhere as a whole word;
--   * a whole name that is a curated agency trade name (partner/yacht_name_brand.txt: Rhodes Yachting, Active Cruises)
--     -> "Boat4You". Owner-named boats (Zenith, Despina, Omnia ...) and the borderline names (Sailtours, Trapani Vela Club,
--     Pronto Sail, Sailing Spirit) are not on the list and keep their names.
-- r02_yacht_public_name_v1 is the SQL twin of YachtNamePolicy.publicName (R02NamePolicyParityTest); the MMK / NauSys syncs of
-- the same jar apply the Kotlin rule on every write and fill partner_name for every boat they touch, so no full-table backfill:
-- only the rows whose public name differs are written here (about 600 on prod; see the prod checks), all rows incl. sys_active
-- = false (the successor match between retired and live copies keeps reading the raw name). Twin / successor / twin-resolver
-- matching reads COALESCE(partner_name, name) (V9_86 + the jar), so no pair is created or broken.
--
-- Name changes fire the V9_71 trigger (lastmod). R31 (same jar): SlugUtils drops a name that repeats the model
-- ("gulet-gulet-18992" -> "gulet-18992") - no DB change, so those boats get an explicit lastmod bump below (no lock on yacht).
--
-- WAVE-2 RULE: cusma3 runs the new jar in the same gate window (backend_deploy.sh REQUIRES_BOTH_NODES), otherwise the old
-- jar's syncs write the raw names back (and flip slugs / lastmod) on every run. Deploy outside the sync windows.
-- Row locks: each step retries 10 x 3 s on lock_not_available inside an exception block (a failed attempt rolls back to
-- the block start). Idempotent: a second run finds no row to change; the markers keep the lastmod bump to one run.
-- Backup: ops_yacht_name_backup_20261014 (yacht_id, name) - never overwritten.
-- Rollback (same window as the jar rollback, both nodes):
--   UPDATE yacht y SET name = b.name FROM ops_yacht_name_backup_20261014 b WHERE b.yacht_id = y.id;
--   (partner_name may stay: COALESCE(partner_name, name) then equals name.)
CREATE TABLE IF NOT EXISTS public.ops_yacht_name_backup_20261014 (
    yacht_id     BIGINT      PRIMARY KEY,
    name         TEXT        NOT NULL,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public._r02_v9_87_done (
    step    TEXT        PRIMARY KEY,
    rows    INTEGER     NOT NULL,
    done_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Accents as V9_67 location_name_key; the token list is the fleet-label subset of the owner rule. Keep in step with
-- YachtNamePolicy (Kotlin) - the parity test runs both over the same fixtures.
CREATE OR REPLACE FUNCTION public.r02_yacht_public_name_v1(raw TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$f$
SELECT CASE
           WHEN raw IS NULL THEN NULL
           WHEN btrim(regexp_replace(translate(lower(raw), 'šžčćđáàâäãåéèêëíìîïóòôöõúùûüçñ', 'szccdaaaaaaeeeeiiiiooooouuuucn'),
                                     '\s+', ' ', 'g'))
                IN ('rhodes yachting', 'active cruises')
               THEN 'Boat4You'
           WHEN raw !~ '\m(NSS Charter|NSS|Sunsail|The Moorings|Moorings(?=[[:space:]]*[0-9])|Navigare)\M'
            AND raw !~ '^Moorings\M'
               THEN raw
           ELSE btrim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(raw,
                    '^Moorings\M', 'Boat4You'),
                    '\m(NSS Charter|NSS|Sunsail|The Moorings|Moorings(?=[[:space:]]*[0-9])|Navigare)\M', 'Boat4You', 'g'),
                    'Boat4You([[:space:]/]+Boat4You)+', 'Boat4You', 'g'),
                    '[[:space:]]{2,}', ' ', 'g'))
       END
$f$;

DO $$
DECLARE
    n          INTEGER;
    n_inactive INTEGER;
    attempt    INT := 0;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public._r02_v9_87_done WHERE step = 'names') THEN
        LOOP
            BEGIN
                PERFORM set_config('lock_timeout', '3s', true);
                INSERT INTO public.ops_yacht_name_backup_20261014 (yacht_id, name)
                SELECT y.id, y.name
                FROM public.yacht y
                WHERE public.r02_yacht_public_name_v1(COALESCE(y.partner_name, y.name)) IS DISTINCT FROM y.name
                ON CONFLICT (yacht_id) DO NOTHING;
                SELECT count(*) FILTER (WHERE NOT y.sys_active) INTO n_inactive
                FROM public.yacht y
                WHERE public.r02_yacht_public_name_v1(COALESCE(y.partner_name, y.name)) IS DISTINCT FROM y.name;
                UPDATE public.yacht y
                SET partner_name = COALESCE(y.partner_name, y.name),
                    name         = public.r02_yacht_public_name_v1(COALESCE(y.partner_name, y.name))
                WHERE public.r02_yacht_public_name_v1(COALESCE(y.partner_name, y.name)) IS DISTINCT FROM y.name;
                GET DIAGNOSTICS n = ROW_COUNT;
                RAISE NOTICE 'V9_87 yacht public names written: % (of them inactive: %)', n, n_inactive;
                INSERT INTO public._r02_v9_87_done (step, rows) VALUES ('names', n);
                EXIT;
            EXCEPTION
                WHEN lock_not_available THEN
                    attempt := attempt + 1;
                    IF attempt >= 10 THEN
                        RAISE;
                    END IF;
                    RAISE NOTICE 'V9_87: yacht rows locked (attempt % of 10), retrying in 3 s', attempt;
                    PERFORM pg_sleep(3);
            END;
        END LOOP;
    ELSE
        RAISE NOTICE 'V9_87 names already written (marker present)';
    END IF;

    -- R31: the slug drops a name that repeats the model (YachtLabel.nameRepeatsModel: same words, or the model's last words;
    -- case, accents, punctuation and a vessel-type prefix ignored). Active boats only (the sitemaps list them).
    IF NOT EXISTS (SELECT 1 FROM public._r02_v9_87_done WHERE step = 'r31_lastmod') THEN
        INSERT INTO public.yacht_content_modified AS m (yacht_id, modified_at)
        SELECT y.id, clock_timestamp()
        FROM public.yacht y
        JOIN public.model mo ON mo.id = y.model_id
        CROSS JOIN LATERAL (
            SELECT regexp_replace(btrim(regexp_replace(translate(lower(mo.name), 'šžčćđáàâäãåéèêëíìîïóòôöõúùûüçñ',
                                                                 'szccdaaaaaaeeeeiiiiooooouuuucn'),
                                                       '[^[:alnum:]]+', ' ', 'g')),
                                  '^(m s|m y|s y|m v|s v|msy|ms|my|sy|mv|sv) +', '') AS mk,
                   regexp_replace(btrim(regexp_replace(translate(lower(y.name), 'šžčćđáàâäãåéèêëíìîïóòôöõúùûüçñ',
                                                                 'szccdaaaaaaeeeeiiiiooooouuuucn'),
                                                       '[^[:alnum:]]+', ' ', 'g')),
                                  '^(m s|m y|s y|m v|s v|msy|ms|my|sy|mv|sv) +', '') AS nk
        ) k
        WHERE y.sys_active
          AND k.mk <> '' AND k.nk <> ''
          AND (k.mk = k.nk OR k.mk LIKE '% ' || k.nk)
        ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at
            WHERE m.modified_at < EXCLUDED.modified_at;
        GET DIAGNOSTICS n = ROW_COUNT;
        RAISE NOTICE 'V9_87 R31 lastmod bumped: %', n;
        INSERT INTO public._r02_v9_87_done (step, rows) VALUES ('r31_lastmod', n);
    END IF;
END $$;
