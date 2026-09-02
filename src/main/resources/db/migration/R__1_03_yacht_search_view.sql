-- Repeatable: yacht_search_view as a MATERIALIZED VIEW.
--
-- WHY MATERIALIZED (28.5.2026): yacht_search_view is a heavy UNION join over
-- ~475k offer rows (offer⋈yacht⋈agency⋈location×2⋈model⋈manufacturer⋈charter).
-- getYachts() runs GROUP BY ~20 cols + aggregates + sort + count over it on
-- EVERY /public/yachts request, which on prod took >60s per query. Under
-- concurrent traffic the Hikari pool (25) filled with these long-held
-- connections (leak detector fired at 60s, pool exhausted at 20s timeout) →
-- site-wide freezes + starved every other endpoint (e.g. empty admin agency
-- list). Materializing precomputes the joins; the same query now runs in ~3.6s
-- against the flat indexed matview (validated on prod data), so connections are
-- released fast and the pool never exhausts. Freshness is kept by a background
-- REFRESH MATERIALIZED VIEW CONCURRENTLY job (see SearchViewRefreshJob) run on
-- a short interval + after each catalogue/offer sync.
--
-- row_uid: a STABLE unique key per logical row, required by REFRESH ...
-- CONCURRENTLY. EXTERNAL rows are keyed by (offer.id, charter-type row id),
-- CUSTOM rows by (yacht.id, charter-type row id); the 'o-'/'c-' prefixes keep
-- the two branches disjoint. yacht_charter_type.id is a surrogate PK so the
-- key is collision-free even when a yacht has multiple charter types.
--
-- Repeatable migrations re-run only when this file's checksum changes, so the
-- matview is rebuilt on a deploy that edits this file and otherwise persists
-- untouched. BUILD-AND-SWAP (2.9.2026): the new snapshot and its indexes are
-- built as `yacht_search_view_build` (invisible to readers, ~30-40 s on prod)
-- and only then the live relation is dropped and `_build` renamed into its
-- place — all inside the single Flyway transaction. Readers queue only between
-- the DROP and COMMIT (milliseconds). Before, DROP came first and every
-- /public/yachts search blocked, then failed at the role lock_timeout (15 s),
-- for the whole CREATE + index build (~45 s). The DO block at the swap still
-- drops whichever relation kind currently exists (plain view on first cutover,
-- matview on later re-runs). Column list/types are unchanged across re-runs so
-- cached JDBC plans re-plan silently; the relation is owned by the Flyway user
-- (boat4you_owner) and boat4you_app is a member, so REFRESH keeps working.
--
-- Tracked patches: V1_60 (drop UNAVAILABLE filter + custom branch reads
-- y.location_id), V1_67 (agency_recommended 0/1), 7.5.2026 (location_to_full_name),
-- 3.6.2026 (location_full_name embeds location.display_name = "name | city", a STORED
-- generated column from V9_16, so the nightly catalogue sync can never revert the city label),
-- 9.7.2026 (country_code/country_code_to columns + indexes: country searches used to expand
-- into 200+ marina IN-lists / right(location_full_name, 2) — neither could use an index
-- (Hibernate binds criteria literals, so an expression index never matches), forcing a
-- 1.6M-row full-view walk per page; a dedicated indexed column bitmap-scans only that
-- country's rows), 2.9.2026 (recommended_score: dropped the per-row count(*) over
-- yacht_extras — 1.7M index probes / 73M heap fetches per refresh, column unused since the
-- agency_recommended sort; build phase 23 s → ~4 s. Dropped yacht_search_view_offer_status_idx
-- (4 scans in 12 days, predicate keeps 85 % of rows) and yacht_search_view_agency_id_idx
-- (12 scans in 12 days, admin-only filter) — 63 MB and two index maintenances per refresh diff.
-- Head/tail rewritten to build-and-swap, see above).

-- Defensive: a rolled-back run leaves no _build relation, but a non-transactional
-- leftover must not break the next run.
DROP MATERIALIZED VIEW IF EXISTS public.yacht_search_view_build;

-- 1. Build the new snapshot under a temporary name; the live matview keeps serving.
CREATE MATERIALIZED VIEW public.yacht_search_view_build AS
  SELECT ('o-' || o.id || '-' || COALESCE(yct.id::text, '0')) AS row_uid,
         y.id,
         y.name AS yacht_name,
         o.location_from,
         ((((lfrom.id || '-'::text) || (lfrom.display_name)::text) || '-'::text) || (lfrom.country_code)::text) AS location_full_name,
         o.location_to,
         ((((lto.id || '-'::text) || (lto.display_name)::text) || '-'::text) || (lto.country_code)::text) AS location_to_full_name,
         (o.client_price / (COALESCE(NULLIF((o.date_to - o.date_from), 0), 1))::numeric) AS client_price,
         (o.ext_base_price / (COALESCE(NULLIF((o.date_to - o.date_from), 0), 1))::numeric) AS list_price,
         (o.broker_commission / (COALESCE(NULLIF((o.date_to - o.date_from), 0), 1))::numeric) AS broker_commission,
         COALESCE(NULLIF((o.date_to - o.date_from), 0), 7) AS number_of_days,
         o.date_from,
         o.date_to,
         y.build_year,
         y.model_id,
         m.name AS model_name,
         mf.id AS manufacturer_id,
         mf.name AS manufacturer_name,
         yct.type AS charter_type,
         y.vessel_type,
         y.mainsail_type,
         y.max_persons,
         y.cabins,
         y.berths,
         y.length,
         y.wc,
         y.engine_power,
         o.deposit AS lowest_prepayment,
         ((COALESCE((y.cabins)::integer, 0) + COALESCE((y.max_persons)::integer, 0))::numeric
             / NULLIF(COALESCE(o.client_price, 1::numeric), 0::numeric)) AS recommended_score,
         CASE
             WHEN (o.location_from = o.location_to) THEN o.location_from
             ELSE (o.location_from + o.location_to)
         END AS total_locations,
         y.main_image_id AS main_image,
         a.id AS agency_id,
         a.name AS agency_name,
         y.entry_type,
         o.status AS offer_status,
         (CASE WHEN COALESCE(a.recommended, false) THEN 1 ELSE 0 END) AS agency_recommended,
         lfrom.country_code AS country_code,
         lto.country_code AS country_code_to
  FROM   yacht y
         JOIN agency a              ON  y.agency_id = a.id AND a.active = true AND a.availability_blocked = false
         JOIN offer o               ON  o.yacht_id = y.id
         JOIN location lfrom        ON  lfrom.id = o.location_from
         LEFT JOIN location lto     ON  lto.id = o.location_to
         LEFT JOIN model m          ON  m.id = y.model_id
         LEFT JOIN manufacturer mf  ON  mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct ON yct.yacht_id = y.id
  WHERE  y.entry_type = 'EXTERNAL'
    AND  y.sys_active = true
UNION ALL
  SELECT ('c-' || y.id || '-' || COALESCE(yct.id::text, '0')) AS row_uid,
         y.id,
         y.name AS yacht_name,
         y.location_id AS location_from,
         ((((l.id || '-'::text) || (l.display_name)::text) || '-'::text) || (l.country_code)::text) AS location_full_name,
         y.location_id AS location_to,
         ((((l.id || '-'::text) || (l.display_name)::text) || '-'::text) || (l.country_code)::text) AS location_to_full_name,
         (cyd.low_price / (7)::numeric) AS client_price,
         NULL::numeric AS list_price,
         NULL::numeric AS broker_commission,
         7 AS number_of_days,
         NULL::date AS date_from,
         NULL::date AS date_to,
         y.build_year,
         y.model_id,
         m.name AS model_name,
         mf.id AS manufacturer_id,
         mf.name AS manufacturer_name,
         yct.type AS charter_type,
         y.vessel_type,
         y.mainsail_type,
         y.max_persons,
         y.cabins,
         y.berths,
         y.length,
         y.wc,
         y.engine_power,
         y.deposit AS lowest_prepayment,
         ((COALESCE((y.cabins)::integer, 0) + COALESCE((y.max_persons)::integer, 0))::numeric
             / NULLIF(COALESCE(cyd.low_price, 1::numeric), 0::numeric)) AS recommended_score,
         1 AS total_locations,
         y.main_image_id AS main_image,
         a.id AS agency_id,
         a.name AS agency_name,
         y.entry_type,
         'FREE' AS offer_status,
         (CASE WHEN COALESCE(a.recommended, false) THEN 1 ELSE 0 END) AS agency_recommended,
         l.country_code AS country_code,
         l.country_code AS country_code_to
  FROM   yacht y
         LEFT JOIN agency a         ON  y.agency_id = a.id AND a.active = true
         LEFT JOIN location l       ON  l.id = y.location_id
         LEFT JOIN model m          ON  m.id = y.model_id
         LEFT JOIN manufacturer mf  ON  mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct ON yct.yacht_id = y.id
         JOIN custom_yacht_details cyd ON cyd.yacht_id = y.id
  WHERE  y.entry_type = 'CUSTOM'
    AND  y.sys_active = true;

-- 2. Indexes on the not-yet-visible _build relation (renamed to their final
--    names at the swap: the `_build` suffix is stripped).
-- Unique key for REFRESH ... CONCURRENTLY (non-blocking refresh).
CREATE UNIQUE INDEX yacht_search_view_row_uid_uidx_build ON public.yacht_search_view_build (row_uid);

-- Filter / sort indexes matching buildYachtSearchPredicates + the sort columns.
CREATE INDEX yacht_search_view_vessel_type_idx_build  ON public.yacht_search_view_build (vessel_type);
CREATE INDEX yacht_search_view_location_from_idx_build ON public.yacht_search_view_build (location_from);
CREATE INDEX yacht_search_view_location_to_idx_build   ON public.yacht_search_view_build (location_to);
CREATE INDEX yacht_search_view_id_idx_build           ON public.yacht_search_view_build (id);
CREATE INDEX yacht_search_view_dates_idx_build        ON public.yacht_search_view_build (date_from, date_to);
CREATE INDEX yacht_search_view_client_price_idx_build ON public.yacht_search_view_build (client_price);
CREATE INDEX yacht_search_view_country_from_idx_build ON public.yacht_search_view_build (country_code);
CREATE INDEX yacht_search_view_country_to_idx_build   ON public.yacht_search_view_build (country_code_to);

-- 3. Swap: AccessExclusiveLock on the live relation only from here to COMMIT.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'yacht_search_view' AND relkind = 'v') THEN
        DROP VIEW public.yacht_search_view;
    ELSIF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'yacht_search_view' AND relkind = 'm') THEN
        DROP MATERIALIZED VIEW public.yacht_search_view;
    END IF;
END $$;

ALTER MATERIALIZED VIEW public.yacht_search_view_build RENAME TO yacht_search_view;

DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT indexname FROM pg_indexes
             WHERE schemaname = 'public' AND tablename = 'yacht_search_view' AND indexname LIKE '%\_build' LOOP
        EXECUTE format('ALTER INDEX public.%I RENAME TO %I', r.indexname, left(r.indexname, length(r.indexname) - 6));
    END LOOP;
END $$;

GRANT SELECT ON public.yacht_search_view TO boat4you_app;
