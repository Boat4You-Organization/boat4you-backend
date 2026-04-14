CREATE OR REPLACE VIEW yacht_locations_view AS
SELECT l.id        AS location_id,
       l.name      AS location_name,
       c.name      AS country_name,
       c.code2     AS country_code,
       y.id        AS yacht_id,
       c.id        AS country_id,
       c.continent AS continent
FROM location l
         JOIN country c ON l.country_id = c.id
         JOIN yacht y ON l.id = y.location_id
UNION
SELECT l.id        AS location_id,
       l.name      AS location_name,
       c.name      AS country_name,
       c.code2     AS country_code,
       y.id        AS yacht_id,
       c.id        AS country_id,
       c.continent AS continent
FROM location l
         JOIN yacht_locations yl ON l.id = yl.location_id
         JOIN country c ON l.country_id = c.id
         JOIN yacht y ON yl.yacht_id = y.id