CREATE OR REPLACE VIEW all_location_view AS
SELECT 'l-' || l.id as id,
       l.id         as real_id,
       l.display_name AS name,
       'MARINA'     as location_type,
       country_code as country_code
FROM location l
UNION ALL
SELECT 'r-' || r.id as id,
       r.id         as real_id,
       r.name,
       'REGION',
       country_code as country_code
FROM region r
UNION ALL
SELECT 'c-' || c.id as id,
       c.id         as real_id,
       c.name,
       'COUNTRY',
       code2        as country_code
FROM country c