-- location_view: the countries, regions and marinas the search offers (every row has at least one yacht).
--
-- aliases (26.9.2026 audit B01): for a REGION, its other known spellings (region_alias, V9_68) - old names and the
-- partners' current names - '|'-separated, never the canonical name itself and never a spelling that is another
-- listed region's canonical name. The autocomplete matches them (search_filed). For the web an alias is a FALLBACK
-- only: consulted after its pinned landings, popular searches and catalogue names have all failed to resolve a
-- spelling, and never a reason to redirect a spelling that resolves directly or is a popular label or member
-- ("split region" is the "Split Region" landing, not an alias URL of r-5 "Split"). NULL for countries and marinas.
--
-- city / lat / lon (audit B14): MARINA only, for the dual-source merge of the location list (MarinaPlaces): "Marina
-- Frapa" (Rogoznica) is inside "Marina Frapa Dubrovnik" by name but 170 km away, so the coordinates / city veto it.
--
-- New columns are appended at the end: CREATE OR REPLACE VIEW may only add columns after the existing ones.
CREATE OR REPLACE VIEW public.location_view
AS
SELECT 'l-' || l.id            as id,
       l.id                    as real_id,
       l.name,
       'MARINA'                as location_type,
       country_code            as country_code,
       l.name || ' ' || COALESCE(l.city, '') as search_filed,
       NULL::text              as aliases,
       l.city::text            as city,
       l.lat::numeric          as lat,
       l.lon::numeric          as lon
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
       r.name || ' ' || COALESCE(c.name, '') || COALESCE(' ' || ra.aliases, '') as search_filed,
       ra.aliases,
       NULL::text,
       NULL::numeric,
       NULL::numeric
FROM region r
         LEFT JOIN boat4you_db.public.country c
                   ON r.country_id = c.id
         LEFT JOIN LATERAL (
             SELECT string_agg(a.alias, '|' ORDER BY lower(a.alias)) AS aliases
             FROM region_alias a
             WHERE a.region_id = r.id
               AND lower(a.alias) <> lower(COALESCE(r.name, ''))
               -- a spelling that is another LISTED region's name keeps resolving to that region
               AND NOT EXISTS (SELECT 1
                               FROM region r2
                               WHERE r2.id <> r.id
                                 AND lower(r2.name) = lower(a.alias)
                                 AND EXISTS (SELECT 1
                                             FROM yacht y2
                                                      JOIN location_region lr2 ON lr2.location_id = y2.location_id
                                             WHERE lr2.region_id = r2.id))
         ) ra ON true
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
       c.name       as search_filed,
       NULL::text   as aliases,
       NULL::text,
       NULL::numeric,
       NULL::numeric
FROM country c
WHERE EXISTS (SELECT 1
              FROM yacht y
                       JOIN location l
                            ON l.id = y.location_id
                       JOIN country c2
                            ON c2.id = l.country_id
              WHERE c2.id = c.id);
