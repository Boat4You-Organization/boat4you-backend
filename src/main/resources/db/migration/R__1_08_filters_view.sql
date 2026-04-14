CREATE OR REPLACE VIEW filters_view AS
WITH prices AS (SELECT MIN(o.client_price / COALESCE(NULLIF(o.date_to - o.date_from, 0), 1)) as min_price,
                       MAX(o.client_price / COALESCE(NULLIF(o.date_to - o.date_from, 0), 1)) as max_price
                FROM offer o
                UNION
                SELECT MIN(cyd.low_price / 7) as min_price,
                       MAX(cyd.low_price / 7) as max_price
                FROM custom_yacht_details cyd)
SELECT (SELECT MIN(min_price) FROM prices) as min_price,
       (SELECT MAX(max_price) FROM prices) as max_price,
       MIN(y.cabins)                       as min_cabins,
       MAX(y.cabins)                       as max_cabins,
       MIN(y.max_persons)                  as min_persons,
       MAX(y.max_persons)                  as max_persons,
       MIN(y.berths)                       as min_berths,
       MAX(y.berths)                       as max_berths,
       MIN(y.length)                       as min_lenght,
       MAX(y.length)                       as max_lenght,
       MIN(y.build_year)                   as min_build_year,
       MAX(y.build_year)                   as max_build_year,
       MIN(y.wc)                           as min_wc,
       MAX(y.wc)                           as max_wc,
       MIN(y.engine_power)                 as min_engine_power,
       MAX(y.engine_power)                 as max_engine_power,
       1                                   as id
FROM yacht y;