DROP VIEW IF EXISTS public.yacht_search_view;
CREATE OR REPLACE VIEW public.yacht_search_view
AS
SELECT y.id                                                             as id
     , y.name                                                           as yacht_name
     , o.location_from                                                  as location_from
     , lfrom.id || '-' || lfrom.name || '-' || lfrom.country_code       as location_full_name
     , o.location_to                                                    as location_to
     , o.client_price / COALESCE(NULLIF(o.date_to - o.date_from, 0), 1) as client_price
     , o.ext_base_price / COALESCE(NULLIF(o.date_to - o.date_from, 0), 1) as list_price
     -- Broker commission stored per-offer by Mmk/Nausys sync services
     -- (V1_51); exposed per-day here to match client_price treatment.
     , o.broker_commission / COALESCE(NULLIF(o.date_to - o.date_from, 0), 1) as broker_commission
     , COALESCE(NULLIF(o.date_to - o.date_from, 0), 7)                 as number_of_days
     , o.date_from                                                      as date_from
     , o.date_to                                                        as date_to
     , y.build_year                                                     as build_year
     , y.model_id                                                       as model_id
     , m.name                                                           as model_name
     , mf.id                                                            AS manufacturer_id
     , mf.name                                                          AS manufacturer_name
     , yct.type                                                         as charter_type
     , y.vessel_type                                                    as vessel_type
     , y.mainsail_type                                                  as mainsail_type
     , y.max_persons                                                    as max_persons
     , y.cabins                                                         as cabins
     , y.berths                                                         as berths
     , y.length                                                         as length
     , y.wc                                                             as wc
     , y.engine_power                                                   as engine_power
     , o.deposit                                                        AS lowest_prepayment
     , (
    ((COALESCE(y.cabins, 0) + COALESCE(y.max_persons, 0))::numeric /
     NULLIF(COALESCE(o.client_price, 1), 0)) +
    (0.01 * (SELECT COUNT(*)
             FROM yacht_extras ye
             WHERE ye.yacht_id = y.id))
    )                                                                   AS recommended_score
     , CASE
           WHEN o.location_from = o.location_to THEN o.location_from
           ELSE o.location_from + o.location_to
    END                                                                 AS total_locations
     , y.main_image_id                                                  as main_image
     , a.id                                                             as agency_id
     , a.name                                                           as agency_name
     , y.entry_type                                                     as entry_type
     , o.status                                                         as offer_status
FROM yacht y
         JOIN agency a
              ON y.agency_id = a.id AND a.active = true
         JOIN offer o
              ON o.yacht_id = y.id
         JOIN location lfrom
              ON lfrom.id = o.location_from
         LEFT JOIN model m
                   ON m.id = y.model_id
         LEFT JOIN manufacturer mf
                   ON mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct
                   ON yct.yacht_id = y.id
WHERE y.entry_type = 1
  AND y.sys_active = true
  -- Show yachts in all statuses EXCEPT UNAVAILABLE (4 = owner week, regatta,
  -- sleep aboard etc.) — those are truly un-bookable so we hide them from the
  -- listing. All other statuses surface with their own Available/Pre-reserved
  -- badge (see OfferStatus enum + card mapping).
  AND o.status != 4
UNION ALL
SELECT y.id                                           as id
     , y.name                                         as yacht_name
     , yl.location_id                                 as location_from
     , l.id || '-' || l.name || '-' || l.country_code as location_full_name
     , yl.location_id                                 as location_to
     , cyd.low_price / 7                              as client_price
     , null                                           as list_price
     , null                                           as broker_commission
     , 7                                              as number_of_days
     , null                                           as date_from
     , null                                           as date_to
     , y.build_year                                   as build_year
     , y.model_id                                     as model_id
     , m.name                                         as model_name
     , mf.id                                          AS manufacturer_id
     , mf.name                                        AS manufacturer_name
     , yct.type                                       as charter_type
     , y.vessel_type                                  as vessel_type
     , y.mainsail_type                                as mainsail_type
     , y.max_persons                                  as max_persons
     , y.cabins                                       as cabins
     , y.berths                                       as berths
     , y.length                                       as length
     , y.wc                                           as wc
     , y.engine_power                                 as engine_power
     , y.deposit                                      AS lowest_prepayment
     , (
    ((COALESCE(y.cabins, 0) + COALESCE(y.max_persons, 0))::numeric /
     NULLIF(COALESCE(cyd.low_price, 1), 0)) +
    (0.01 * (SELECT COUNT(*)
             FROM yacht_extras ye
             WHERE ye.yacht_id = y.id))
    )                                                 AS recommended_score
     , (SELECT COUNT(DISTINCT yl2.location_id)
        FROM yacht_locations yl2
        WHERE yl2.yacht_id = y.id)::INTEGER           AS total_locations
     , y.main_image_id                                as main_image
     , a.id                                           as agency_id
     , a.name                                         as agency_name
     , y.entry_type                                   as entry_type
     , 1                                              as offer_status   -- custom yachts have no offer row; treat as FREE
FROM yacht y
         LEFT JOIN agency a
                   ON y.agency_id = a.id AND a.active = true
         LEFT JOIN boat4you_db.public.yacht_locations yl
                   ON yl.yacht_id = y.id
         LEFT JOIN location l ON l.id = yl.location_id
         LEFT JOIN model m ON m.id = y.model_id
         LEFT JOIN manufacturer mf
                   ON mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct
                   ON yct.yacht_id = y.id
         JOIN custom_yacht_details cyd
              ON cyd.yacht_id = y.id
WHERE y.entry_type = 2
  AND y.sys_active = true;