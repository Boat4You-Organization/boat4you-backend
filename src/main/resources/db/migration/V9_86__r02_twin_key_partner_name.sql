-- V9_86 R02 (14.10.2026): the listing-twin matview (V9_69 / V9_70) pairs boats on the RAW partner name -
-- COALESCE(yacht.partner_name, yacht.name) - and treats "boat4you" as a placeholder, so the public rename (V9_87, which runs
-- after this) never creates or breaks a twin pair: two different fleet boats "Sunsail 41.3 Premium" / "Moorings 41.3 Premium"
-- at one base would both read "Boat4You 41.3 Premium", and two boats named after their agencies would both read "Boat4You".
-- partner_name is NULL on every row until V9_87 / the new jar's syncs write it, so right after this migration the pair set is
-- exactly V9_70's. Body = V9_70 verbatim except those two points (name_key, placeholder list).
--
-- DROP MATERIALIZED VIEW needs ACCESS EXCLUSIVE on the matview only (cusma3's REFRESH ... CONCURRENTLY every 2 min and API
-- reads hold it off briefly): each attempt waits at most 3 s, 10 attempts 3 s apart (as V9_71); a failed attempt rolls back to
-- the block start. WITH DATA builds it in well under a second (V9_69). Rollback = re-run the V9_70 matview body.
DO $$
DECLARE
    attempt INT := 0;
BEGIN
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'DROP MATERIALIZED VIEW IF EXISTS public.yacht_listing_twin';
            EXECUTE $v$
            CREATE MATERIALIZED VIEW public.yacht_listing_twin AS
            WITH future AS (
                -- what the undated listing can show (offer from today, not UNAVAILABLE) and whether a week is left to sell
                SELECT o.yacht_id,
                       bool_or(o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')) AS sellable
                FROM offer o
                WHERE o.date_from >= CURRENT_DATE
                  AND o.status <> 'UNAVAILABLE'
                GROUP BY o.yacht_id
            ),
            source AS (
                SELECT em.system_id AS yacht_id, min(em.external_system_id) AS system
                FROM external_mapping em
                WHERE em.type = 'Yacht'
                GROUP BY em.system_id
            ),
            named AS (
                SELECT y.*,
                       -- R02: the raw partner name - the public name (V9_87) must never create or break a pair
                       regexp_replace(lower(btrim(COALESCE(y.partner_name, y.name))), '[^[:alnum:]]', '', 'g') AS name_key,
                       regexp_replace(lower(COALESCE(m.name, '')), '[^[:alnum:]]', '', 'g') AS model_key,
                       regexp_replace(lower(COALESCE(mf.name, '') || COALESCE(m.name, '')), '[^[:alnum:]]', '', 'g') AS full_model_key
                FROM yacht y
                LEFT JOIN model m         ON m.id = y.model_id
                LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
            ),
            listed AS (
                SELECT y.id,
                       y.agency_id,
                       s.system,
                       y.vessel_type,
                       y.length,
                       y.build_year,
                       y.name_key,
                       l.country_code || ':' || translate(lower(btrim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS base_key,
                       f.yacht_id IS NOT NULL AS listable,
                       COALESCE(f.sellable, false) AS sellable,
                       NOT (COALESCE(y.option_approval, false) OR COALESCE(a.inquiry_only, false)) AS bookable
                FROM named y
                JOIN agency a      ON a.id = y.agency_id AND a.active AND NOT a.availability_blocked
                JOIN location l    ON l.id = y.location_id
                LEFT JOIN future f ON f.yacht_id = y.id
                LEFT JOIN source s ON s.yacht_id = y.id
                WHERE y.entry_type = 'EXTERNAL'
                  AND y.sys_active
                  -- a real boat name: letters, 3+ characters, not the model, not a placeholder
                  AND length(y.name_key) >= 3
                  AND y.name_key ~ '[a-z]'
                  AND y.name_key NOT IN (y.model_key, y.full_model_key, 'noname', 'unnamed', 'tba', 'tbd', 'new', 'yacht', 'boat', 'boat4you')
            ),
            pair AS (
                -- the name rule: same name, same base, same vessel type, length within 0.5 m and build year within 1, and a
                -- different channel - another agency, or the same agency through the other partner system
                SELECT y.id AS yacht_id, c.id AS twin_id
                FROM listed y
                JOIN listed c
                  ON c.name_key = y.name_key
                 AND c.base_key = y.base_key
                 AND (c.agency_id <> y.agency_id OR c.system IS DISTINCT FROM y.system)
                 AND c.vessel_type = y.vessel_type
                 AND c.id <> y.id
                 AND (c.length IS NULL) = (y.length IS NULL)
                 AND (c.length IS NULL OR abs(c.length - y.length) <= 0.5)
                 AND (c.build_year IS NULL) = (y.build_year IS NULL)
                 AND (c.build_year IS NULL OR abs(c.build_year - y.build_year) <= 1)
                UNION
                -- hand-verified pairs the rule cannot match (yacht_twin_manual_pair), both ways
                SELECT m.yacht_id, m.twin_yacht_id FROM yacht_twin_manual_pair m WHERE m.yacht_id <> m.twin_yacht_id
                UNION
                SELECT m.twin_yacht_id, m.yacht_id FROM yacht_twin_manual_pair m WHERE m.yacht_id <> m.twin_yacht_id
            ),
            better AS (
                SELECT y.id AS yacht_id,
                       c.id AS canonical_yacht_id,
                       row_number() OVER (PARTITION BY y.id ORDER BY c.sellable DESC, c.bookable DESC, c.id) AS rn
                FROM pair p
                JOIN listed y ON y.id = p.yacht_id
                JOIN listed c ON c.id = p.twin_id
                -- the copy shown: listable, and first by the stable rule above (false < true; the lower id wins a tie)
                WHERE c.listable
                  AND (c.sellable, c.bookable, -c.id) > (y.sellable, y.bookable, -y.id)
            ),
            pick AS (
                SELECT yacht_id, canonical_yacht_id
                FROM better
                WHERE rn = 1
            )
            -- Pairing is not transitive (length within 0.5 m, year within 1): when A~B and B~C but not A~C, C's best copy B is
            -- itself hidden behind A. One more hop names the copy that is shown.
            SELECT p.yacht_id, COALESCE(q.canonical_yacht_id, p.canonical_yacht_id) AS canonical_yacht_id
            FROM pick p
            LEFT JOIN pick q ON q.yacht_id = p.canonical_yacht_id
            $v$;
            EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS yacht_listing_twin_yacht_uidx ON public.yacht_listing_twin (yacht_id)';
            EXECUTE 'GRANT SELECT ON public.yacht_listing_twin TO boat4you_app';
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_86: yacht_listing_twin busy (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
END $$;

DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM public.yacht_listing_twin;
    RAISE NOTICE 'V9_86 yacht_listing_twin rows: %', n;
END $$;
