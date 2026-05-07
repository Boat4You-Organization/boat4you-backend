-- Repeatable: yacht_search_view definition.
-- Flyway runs every R__ migration after all V_ migrations are applied,
-- whenever its checksum changes. Keeping this file in lockstep with the
-- latest versioned view migration (currently V1_67) is required so that a
-- fresh DB ends up with the same view as a DB that ran V1_60 / V1_67
-- incrementally — without it, R__ would silently revert those versioned
-- patches on first boot.
--
-- Latest tracked patches:
--   V1_60 — drop `o.status != 4` UNAVAILABLE filter (admin replacement flow opt-in)
--          and switch the custom-yacht branch from a `yacht_locations` join
--          to reading `y.location_id` directly (the per-yacht link table was
--          retired around the same time)
--   V1_67 — append `agency_recommended` (0/1 INT) for the Recommended sort
--   7.5.2026 — append `location_to_full_name` (mirror of location_full_name
--           but for the drop-off location) so listing endpoint can show
--           "Pickup » Drop-off" labels for one-way charters without an
--           extra round-trip.
DROP VIEW IF EXISTS public.yacht_search_view;
CREATE OR REPLACE VIEW public.yacht_search_view AS
  SELECT y.id,
         y.name AS yacht_name,
         o.location_from,
         ((((lfrom.id || '-'::text) || (lfrom.name)::text) || '-'::text) || (lfrom.country_code)::text) AS location_full_name,
         o.location_to,
         ((((lto.id || '-'::text) || (lto.name)::text) || '-'::text) || (lto.country_code)::text) AS location_to_full_name,
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
         o.status AS offer_status,
         (CASE WHEN COALESCE(a.recommended, false) THEN 1 ELSE 0 END) AS agency_recommended
  FROM   yacht y
         JOIN agency a              ON  y.agency_id = a.id AND a.active = true
         JOIN offer o               ON  o.yacht_id = y.id
         JOIN location lfrom        ON  lfrom.id = o.location_from
         LEFT JOIN location lto     ON  lto.id = o.location_to
         LEFT JOIN model m          ON  m.id = y.model_id
         LEFT JOIN manufacturer mf  ON  mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct ON yct.yacht_id = y.id
  WHERE  y.entry_type = 1
    AND  y.sys_active = true
UNION ALL
  SELECT y.id,
         y.name AS yacht_name,
         y.location_id AS location_from,
         ((((l.id || '-'::text) || (l.name)::text) || '-'::text) || (l.country_code)::text) AS location_full_name,
         y.location_id AS location_to,
         ((((l.id || '-'::text) || (l.name)::text) || '-'::text) || (l.country_code)::text) AS location_to_full_name,
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
         1 AS offer_status,
         (CASE WHEN COALESCE(a.recommended, false) THEN 1 ELSE 0 END) AS agency_recommended
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
