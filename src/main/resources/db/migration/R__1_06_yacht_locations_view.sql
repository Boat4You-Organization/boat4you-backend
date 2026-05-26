-- F2-XXX local-dev fix (2026-05-14): drop the UNION branch that joined
-- the `yacht_locations` table — that table was removed in V1_54
-- (23.4.2026 audit). Existing DBs had R__1_06 applied BEFORE V1_54
-- ran, so they survive; fresh-DB setup runs V1_54 first (drops the
-- table + recreates view inline) and then R__1_06 — the old UNION
-- failed against the now-missing relation. New view definition
-- matches V1_54's recreation.
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
         JOIN yacht y ON l.id = y.location_id;
