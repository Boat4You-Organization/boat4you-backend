CREATE OR REPLACE VIEW custom_yacht_view AS
SELECT y.id,
       y.NAME,
       m.id            AS model_id,
       m.NAME          AS model_name,
       c.id            AS country_id,
       c.NAME          AS country_name,
       c.code2         AS country_code,
       cyd.low_price,
       cyd.country_key AS country_key,
       mf.name         AS manufacturer_name
FROM yacht y
         JOIN custom_yacht_details cyd
              ON y.id = cyd.yacht_id
         LEFT JOIN country c
                   ON cyd.country_id = c.id
         LEFT JOIN model m
                   ON y.model_id = m.id
         LEFT JOIN manufacturer mf
                   ON m.manufacturer_id = mf.id;