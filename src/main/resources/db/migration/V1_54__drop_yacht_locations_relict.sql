-- V1_54: Drop the legacy `yacht_locations` table and prune dead JOIN branches
-- in the two views that referenced it.
--
-- History (23.4.2026 audit):
-- * `yacht_locations` is documented as "Used for custom boats as they can be
--   related to multiple countries and locations" but has been empty in dev
--   for the entire project lifetime. Custom yachts evolved to a single-
--   location model via `yacht.location_id` alongside `custom_yacht_details`,
--   leaving `yacht_locations` as orphan schema with no inserter, no reader.
-- * `yacht_locations_view` UNION-ed two branches; the second pulled from the
--   dead table and contributed zero rows.
-- * `yacht_search_view` UNION ALL-ed external (entry_type=1) and custom
--   (entry_type=2) branches; the custom branch LEFT JOIN-ed `yacht_locations`
--   even though the same yacht.location_id pattern would do, and entry_type=2
--   yachts are likewise zero today.
--
-- This migration: keep both views functionally identical for *current* data
-- (which has 0 custom yachts), prepare the custom branch to work via
-- `y.location_id` if the feature ships later, and remove the table itself.

-- 1. Drop the table FIRST while the views still depend on it. CASCADE drops
--    both yacht_locations_view and yacht_search_view (re-created below).
DROP TABLE IF EXISTS public.yacht_locations CASCADE;

-- 2. Re-create yacht_locations_view as the SOLE branch that ever produced
--    rows: yacht.location_id → location → country.
CREATE OR REPLACE VIEW public.yacht_locations_view AS
SELECT l.id          AS location_id,
       l.name        AS location_name,
       c.name        AS country_name,
       c.code2       AS country_code,
       y.id          AS yacht_id,
       c.id          AS country_id,
       c.continent
FROM   location l
JOIN   country  c ON l.country_id = c.id
JOIN   yacht    y ON l.id         = y.location_id;

GRANT SELECT ON public.yacht_locations_view TO boat4you_app;

-- 3. Re-create yacht_search_view. Branch 1 (external, entry_type=1) is
--    unchanged. Branch 2 (custom, entry_type=2) drops the yacht_locations
--    LEFT JOIN and uses y.location_id directly — same pattern as branch 1.
CREATE OR REPLACE VIEW public.yacht_search_view AS
  SELECT y.id,
         y.name AS yacht_name,
         o.location_from,
         ((((lfrom.id || '-'::text) || (lfrom.name)::text) || '-'::text) || (lfrom.country_code)::text) AS location_full_name,
         o.location_to,
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
         ((((COALESCE((y.cabins)::integer, 0) + COALESCE((y.max_persons)::integer, 0)))::numeric / NULLIF(COALESCE(o.client_price, (1)::numeric), (0)::numeric)) + (0.01 * (( SELECT count(*) AS count
                FROM yacht_extras ye
               WHERE (ye.yacht_id = y.id)))::numeric)) AS recommended_score,
         CASE
             WHEN (o.location_from = o.location_to) THEN o.location_from
             ELSE (o.location_from + o.location_to)
         END AS total_locations,
         y.main_image_id AS main_image,
         a.id AS agency_id,
         a.name AS agency_name,
         y.entry_type,
         o.status AS offer_status
  FROM   yacht y
         JOIN agency a              ON  y.agency_id = a.id AND a.active = true
         JOIN offer o               ON  o.yacht_id = y.id
         JOIN location lfrom        ON  lfrom.id = o.location_from
         LEFT JOIN model m          ON  m.id = y.model_id
         LEFT JOIN manufacturer mf  ON  mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct ON yct.yacht_id = y.id
  WHERE  y.entry_type = 1
    AND  y.sys_active = true
    AND  o.status <> 4
UNION ALL
  SELECT y.id,
         y.name AS yacht_name,
         y.location_id AS location_from,
         ((((l.id || '-'::text) || (l.name)::text) || '-'::text) || (l.country_code)::text) AS location_full_name,
         y.location_id AS location_to,
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
         ((((COALESCE((y.cabins)::integer, 0) + COALESCE((y.max_persons)::integer, 0)))::numeric / NULLIF(COALESCE(cyd.low_price, (1)::numeric), (0)::numeric)) + (0.01 * (( SELECT count(*) AS count
                FROM yacht_extras ye
               WHERE (ye.yacht_id = y.id)))::numeric)) AS recommended_score,
         1 AS total_locations,
         y.main_image_id AS main_image,
         a.id AS agency_id,
         a.name AS agency_name,
         y.entry_type,
         1 AS offer_status
  FROM   yacht y
         LEFT JOIN agency a         ON  y.agency_id = a.id AND a.active = true
         LEFT JOIN location l       ON  l.id = y.location_id
         LEFT JOIN model m          ON  m.id = y.model_id
         LEFT JOIN manufacturer mf  ON  mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct ON yct.yacht_id = y.id
         JOIN custom_yacht_details cyd ON cyd.yacht_id = y.id
  WHERE  y.entry_type = 2
    AND  y.sys_active = true;

GRANT SELECT ON public.yacht_search_view TO boat4you_app;
