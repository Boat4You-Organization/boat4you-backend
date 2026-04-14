CREATE OR REPLACE VIEW public.location_view
AS
SELECT 'l-' || l.id            as id,
       l.id                    as real_id,
       l.name,
       'MARINA'                as location_type,
       country_code            as country_code,
       l.name || ' ' || COALESCE(l.city, '') as search_filed
FROM location l
WHERE EXISTS (SELECT 1
              FROM yacht y
              WHERE y.location_id = l.id)
UNION ALL
SELECT 'r-' || r.id            as id,
       r.id                    as real_id,
       r.name,
       'REGION',
       r.country_code            as country_code,
       r.name || ' ' || COALESCE(c.name, '') as search_filed
FROM region r
         LEFT JOIN boat4you_db.public.country c
                   ON r.country_id = c.id
WHERE EXISTS (SELECT 1
              FROM yacht y
                       JOIN location_region lr
                            ON lr.location_id = y.location_id
              WHERE lr.region_id = r.id)
UNION ALL
SELECT 'c-' || c.id as id,
       c.id         as real_id,
       c.name,
       'COUNTRY',
       code2        as country_code,
       c.name       as search_filed
FROM country c
WHERE EXISTS (SELECT 1
              FROM yacht y
                       JOIN location l
                            ON l.id = y.location_id
                       JOIN country c2
                            ON c2.id = l.country_id
              WHERE c2.id = c.id);