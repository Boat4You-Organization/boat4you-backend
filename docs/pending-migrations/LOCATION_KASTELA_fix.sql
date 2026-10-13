-- LOCATION_KASTELA_fix - PARKED, not in db/migration yet (another session is adding V9_76). The lead turns it into
-- src/main/resources/db/migration/V9_<next>__location_kastela_one_name.sql after `git pull --ff-only` +
-- `ls src/main/resources/db/migration | sort -V | tail -3` (never two files with one V9_ version - V9_46 took the API down).
--
-- Mario 13.10.2026: boat cards read "Marina Kastela" for some boats and "Marina Kaštela, Kaštel Gomilica" for others,
-- and the map did not find the marina. Prod (read only, 13.10.2026): ONE marina, TWO location rows, one per partner:
--   63   "Marina Kastela"  city NULL               NauSys base 54  (external_mapping 431)    159 active boats (NauSys)
--   1935 "Marina Kaštela"  city "Kaštel Gomilica"  MMK base 119    (external_mapping 70666)  135 active boats (MMK)
-- 280 m apart, both inside the marina's OpenStreetMap outline (centroid 43.5456 N / 16.4026 E). Both region 5 Split.
--
-- What the syncs do with a location row (MmkCatalogueSyncService.updateMmkLocations, NauSysCatalogueSyncService.locationsSync):
--   * resolve the partner base by external_mapping (type 'Location', the partner's base id) - name only for a base
--     without a mapping yet (then create the row);
--   * name: written only when blank (first creation), never overwritten;
--   * city: NauSys never writes it; MMK overwrites it with its own city on every run - for MMK-mapped rows only;
--   * lat / lon: filled only when NULL; country / country_code / regions (add-only, LocationRegionOverrides) every run;
--   * display_name is a STORED generated column (name || ' | ' || city, V9_16), never written by anyone.
-- Row 63 has only the NauSys mapping, so neither sync reverts the name or the city below.
--
-- Why a rename and not a merge 63 -> 1935: the search (LocationRepository.findSamePlaceMarinaIds, same spelling up to
-- Croatian diacritics, < 50 km) and the charter facts (MarinaPlaces) ALREADY treat 63 + 1935 as one place, so the
-- user-visible bugs are only the card label and the location list. A merge would repoint ~15,200 offers (pickup and
-- drop-off), 245 yachts, 46 external_bases, the NauSys mapping and the facts rows, and break every `did=l-63` link and
-- search (findSamePlaceMarinaIds anchors on the row id) for one label. The rename touches one row, keeps both ids
-- working and is undone from the backup below.
--
-- 1. Row 63 takes the spelling and town of its MMK twin: name "Marina Kaštela", city "Kaštel Gomilica"; Postgres
--    recomputes display_name = "Marina Kaštela | Kaštel Gomilica", so every card reads the same (yacht_search_view at
--    its next refresh, <= 10 min). Coordinates are left alone (both rows already have good ones).
-- 2. The pair goes into location_same_place (V9_67), so the location list / autocomplete shows ONE "Marina Kaštela" row
--    (did "l-1935,l-63", LocationQueryingService.mergeDualSourceMarinas) instead of two identical names. The search and
--    the facts already had them as one place, so their results do not change.
-- Code shipped with it (same jar or earlier): LocationRepository.findFirstByNameIgnoreCaseOrderByIdAsc - two rows now
-- share the name "Marina Kaštela", and the old single-result findByNameIgnoreCase threw for a new partner base of that
-- name, rolling back the whole location sync (five such names already existed: Skiathos, Siracusa, Sorrento, ...).
--
-- Idempotent and guarded by id + production names + partner mappings: a database where 63 / 1935 are other rows (local
-- dev) changes nothing; a second run changes nothing. Backup: ops_location_kastela_backup_20261013 (rows before the fix).
-- The catalogue syncs write location rows; give up fast instead of waiting on a sync transaction - a timeout rolls the
-- migration back and the API's systemd restart tries again (deploy outside the sync windows, DEPLOY_NOTES).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS ops_location_kastela_backup_20261013 (
    id           BIGINT PRIMARY KEY,
    name         VARCHAR(255),
    city         VARCHAR(100),
    display_name TEXT,
    lat          NUMERIC,
    lon          NUMERIC,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- the two rows, only the first time and only when they are the production rows
INSERT INTO ops_location_kastela_backup_20261013 (id, name, city, display_name, lat, lon)
SELECT l.id, l.name, l.city, l.display_name, l.lat, l.lon
FROM location l
WHERE l.id IN (63, 1935)
  AND l.country_code = 'HR'
  AND btrim(l.name) IN ('Marina Kastela', 'Marina Kaštela')
ON CONFLICT (id) DO NOTHING;

-- 1. one label
UPDATE location l
SET name = 'Marina Kaštela',
    city = 'Kaštel Gomilica'
WHERE l.id = 63
  AND l.country_code = 'HR'
  AND btrim(l.name) IN ('Marina Kastela', 'Marina Kaštela')
  AND (l.name IS DISTINCT FROM 'Marina Kaštela' OR l.city IS DISTINCT FROM 'Kaštel Gomilica')
  AND EXISTS (SELECT 1
              FROM external_mapping em
              WHERE em.type = 'Location' AND em.external_system_id = 2 AND em.external_id = 54 AND em.system_id = 63)
  AND EXISTS (SELECT 1
              FROM location t
              JOIN external_mapping em ON em.system_id = t.id AND em.type = 'Location'
                                      AND em.external_system_id = 1 AND em.external_id = 119
              WHERE t.id = 1935 AND t.country_code = 'HR'
                AND t.name = 'Marina Kaštela' AND t.city = 'Kaštel Gomilica');

-- 2. one row in the location list
INSERT INTO location_same_place (location_id, same_as_location_id, note)
SELECT 63, 1935, 'Marina Kaštela (Kaštel Gomilica): NauSys base 54 and MMK base 119 rows, 280 m apart (Mario 13.10.2026)'
WHERE EXISTS (SELECT 1 FROM location a WHERE a.id = 63 AND a.country_code = 'HR' AND a.name = 'Marina Kaštela')
  AND EXISTS (SELECT 1 FROM location b WHERE b.id = 1935 AND b.country_code = 'HR' AND b.name = 'Marina Kaštela')
  AND NOT EXISTS (SELECT 1
                  FROM location_same_place sp
                  WHERE (sp.location_id = 63 AND sp.same_as_location_id = 1935)
                     OR (sp.location_id = 1935 AND sp.same_as_location_id = 63))
ON CONFLICT DO NOTHING;

-- Check after deploy (read only):
--   SELECT id, name, city, display_name, lat, lon FROM location WHERE id IN (63, 1935);
--     -> both "Marina Kaštela | Kaštel Gomilica"
--   SELECT * FROM location_same_place WHERE location_id = 63;                           -> (63, 1935)
--   SELECT location_full_name, count(DISTINCT id) FROM yacht_search_view
--    WHERE location_full_name LIKE '63-%' OR location_full_name LIKE '1935-%' GROUP BY 1;
--     -> after the next matview refresh: '63-Marina Kaštela | Kaštel Gomilica-HR' and '1935-...'
--   GET /public/locations?name=kastela -> one row, id "l-1935,l-63" (after the jar restart; the list cache keeps 10 h)
--
-- Undo (by hand):
--   UPDATE location l SET name = b.name, city = b.city
--   FROM ops_location_kastela_backup_20261013 b WHERE b.id = l.id AND l.id = 63;
--   DELETE FROM location_same_place WHERE location_id = 63 AND same_as_location_id = 1935;
