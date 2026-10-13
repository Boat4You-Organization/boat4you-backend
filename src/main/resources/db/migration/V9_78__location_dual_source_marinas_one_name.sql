-- V9_78 (13.10.2026): one name per marina for the other dual-source duplicates (Mario 13.10.2026: "da, spoji sve duple
-- marine"; report docs/location-duplicates-2026-10-13.md, table "Isto mjesto, dva reda" #2-#15, plus two same-name
-- pairs). Same recipe as V9_77 (Marina Kaštela): read that header for what the MMK / NauSys syncs do with a location row.
-- In short: names are written only when blank (first creation), NauSys never writes city, MMK overwrites city with its
-- own on every run but only on the rows it maps, display_name is a generated column (name || ' | ' || city).
--
-- WEB PRECONDITION: the boat4you-web landing pin for "Marina Lav - Podstrana" (docs/location-merge-pins-2026-10-13.md)
-- must be live BEFORE this migration. Its 9 indexed MOTOR_YACHT landings are canonical `marina%20lav%20-%20podstrana`;
-- after the rename below no catalogue row carries that name any more, so without the pin those URLs answer 404 and
-- the landing moves to `marina%20lav`. The other indexed landings of these marinas (Lavrion Main Port, Preveza Main
-- Port, Skiathos, Adaköy Marina) keep their URL without a pin (the merged row keeps today's canonical name).
--
-- Prod (read only, 13.10.2026): each place is ONE marina with TWO location rows, one per partner base, <= 1.5 km apart,
-- same country (active boats NauSys / MMK). Per pair the NauSys row takes the MMK row's name + town, and the pair goes
-- into location_same_place (V9_67): the search's l- resolution, the location list and the charter facts then treat
-- both rows as one place, and every boat card / boat page names the marina the same way.
--   GR  150 "Lavrion, main port"        -> "Lavrion Main Port" | Lavrion          MMK 1069  (63 / 70)   209 m
--   GR  134 "Preveza, Main Port"        -> "Preveza Main Port" | Preveza          MMK 835   (55 / 58)   400 m
--   GR  160 "Skiathos"                  -> "Skiathos" | Skiathos                  MMK 2017  (13 / 39)   363 m
--   TR  204 "Marmaris Adakoy Marina"    -> "Adaköy Marina" | Marmaris             MMK 904   (5 / 35)    523 m
--   HR   72 "Marina Lav - Podstrana"    -> "Marina Lav" | Podstrana               MMK 1423  (17 / 4)    70 m
--   HR   26 "Zadar, D-Marin Borik"      -> "D-Marin Borik" | Zadar                MMK 1413  (1 / 14)    127 m
--   HR   76 "Marina Baska Voda"         -> "Marina Baška Voda" | Baška Voda       MMK 1571  (1 / 7)     142 m
--   ES  548 "Denia, Marina de Dénia"    -> "Marina de Dénia" | Dénia              MMK 848   (5 / 2)     38 m
--   NO  396 "Tromso"                    -> "Tromsø" | Tromsø                      MMK 624   (3 / 2)     1.5 km
--   HR   13 "Marina Admiral Opatija"    -> "Marina Admiral" | Opatija             MMK 1950  (1 / 3)     157 m
--   IT  229 "Siracusa"                  -> "Siracusa" | Siracusa                  MMK 691   (2 / 2)     364 m
--   HR   65 "Marina Spinut, Split"      -> "Marina Špinut" | Split                MMK 1421  (1 / 1)     17 m
--   TR  502 "Göcek/Skopea Marina"       -> "Skopea Marina" | Göcek                MMK 780   (1 / 1)     72 m
--   IT  242 "Sorrento"                  -> "Sorrento" | Sorrento                  MMK 1809  (0 / 1)     130 m
-- Dénia: MMK spells it "Marina de Dènia" (grave accent, wrong in Spanish and Valencian), so the MMK row 848 takes the
-- correct "Marina de Dénia" too (its name is also written only at creation). Its city stays MMK's "Dènia": MMK rewrites
-- it on every run, so the two rows read "Marina de Dénia | Dénia" (548) and "Marina de Dénia | Dènia" (848); the cards
-- show the marina alone.
-- Pair only, no rename:
--   IT 2099 "Naples, Porto di Mergellina" / MMK 2007 "Porto di Mergellina" | Naples (1 / 1, 181 m): renaming 2099 to
--      "Porto di Mergellina" would leave "Naples" (NauSys 209, 3 boats, 156 m from Mergellina) inside only ONE other
--      name, "Naples, Pozzuoli" (257, 8.8 km away), and the location list would fold Naples into Pozzuoli
--      (MarinaPlaces.containmentPairs). Whether 209 is Mergellina too is Mario's call; until then the names stay.
--   ES 1781 / 1516 "Port Calanova" | Palma de Mallorca: two MMK bases of one marina, 86 m apart, no boats today.
-- Not paired: "Marigot Bay" 837 (Saint-Martin) / 983 (Saint Lucia) - two countries, 500 km apart; "Marina Frapa"
-- (Rogoznica, 2029) / "Marina Frapa Dubrovnik" (775) - two marinas (audit B14).
--
-- Rename, not merge (as V9_77): no row is deleted and no offer / yacht / mapping is repointed, so every `did=l-<id>`
-- link keeps working, and the backup below undoes it. Each pair is guarded by both ids, the country, today's
-- production names, the MMK twin's city and both partner mappings (and the renamed row must not be MMK-mapped, or MMK
-- would rewrite its city): a database where these ids are other rows (local dev) changes nothing, a pair whose data
-- moved since 13.10.2026 is skipped whole (no rename -> no pair), and a second run changes nothing.
-- Backup: ops_location_dual_source_backup_20261013 (both rows of every pair, before the fix).
-- The catalogue syncs write location rows; give up fast instead of waiting on a sync transaction - a timeout rolls the
-- migration back and the API's systemd restart tries again (deploy outside the sync windows, DEPLOY_NOTES).
SET LOCAL lock_timeout = '5s';

CREATE TEMP TABLE v9_78_pair (
    a_id       BIGINT PRIMARY KEY, -- the row that is renamed (NauSys) - location_same_place.location_id
    a_names    TEXT[]  NOT NULL,   -- its accepted names: today's production name and the new one
    a_system   INT     NOT NULL,   -- external_system_id of its partner (1 MMK, 2 NauSys)
    a_base     BIGINT  NOT NULL,   -- the partner's base id (external_mapping.external_id)
    b_id       BIGINT  NOT NULL,   -- the MMK twin - location_same_place.same_as_location_id
    b_names    TEXT[]  NOT NULL,
    b_system   INT     NOT NULL,
    b_base     BIGINT  NOT NULL,
    b_city     TEXT    NOT NULL,   -- the twin's city as MMK writes it (trimmed)
    cc         TEXT    NOT NULL,
    new_name   TEXT,               -- NULL = pair only
    new_city   TEXT,
    b_new_name TEXT,               -- the twin's new name (Dénia only)
    note       TEXT    NOT NULL
) ON COMMIT DROP;

INSERT INTO v9_78_pair (a_id, a_names, a_system, a_base, b_id, b_names, b_system, b_base, b_city, cc,
                        new_name, new_city, b_new_name, note)
VALUES (150, ARRAY ['Lavrion, main port', 'Lavrion Main Port'], 2, 125450,
        1069, ARRAY ['Lavrion Main Port'], 1, 18220780000100000, 'Lavrion', 'GR',
        'Lavrion Main Port', 'Lavrion', NULL,
        'Lavrion Main Port (Lavrion): NauSys base 125450 and MMK base 18220780000100000 rows, 209 m apart (Mario 13.10.2026)'),
       (134, ARRAY ['Preveza, Main Port', 'Preveza Main Port'], 2, 828255,
        835, ARRAY ['Preveza Main Port'], 1, 1935994390000100000, 'Preveza', 'GR',
        'Preveza Main Port', 'Preveza', NULL,
        'Preveza Main Port (Preveza): NauSys base 828255 and MMK base 1935994390000100000 rows, 400 m apart (Mario 13.10.2026)'),
       (160, ARRAY ['Skiathos'], 2, 476604,
        2017, ARRAY ['Skiathos'], 1, 21, 'Skiathos', 'GR',
        'Skiathos', 'Skiathos', NULL,
        'Skiathos (Skiathos): NauSys base 476604 and MMK base 21 rows, 363 m apart (Mario 13.10.2026)'),
       (204, ARRAY ['Marmaris Adakoy Marina', 'Adaköy Marina'], 2, 2458139,
        904, ARRAY ['Adaköy Marina'], 1, 1103044210000100000, 'Marmaris', 'TR',
        'Adaköy Marina', 'Marmaris', NULL,
        'Adaköy Marina (Marmaris): NauSys base 2458139 and MMK base 1103044210000100000 rows, 523 m apart (Mario 13.10.2026)'),
       (72, ARRAY ['Marina Lav - Podstrana', 'Marina Lav'], 2, 934568,
        1423, ARRAY ['Marina Lav'], 1, 1237751930000100000, 'Podstrana', 'HR',
        'Marina Lav', 'Podstrana', NULL,
        'Marina Lav (Podstrana): NauSys base 934568 and MMK base 1237751930000100000 rows, 70 m apart (Mario 13.10.2026)'),
       (26, ARRAY ['Zadar, D-Marin Borik', 'D-Marin Borik'], 2, 4963592,
        1413, ARRAY ['D-Marin Borik'], 1, 667400400000100000, 'Zadar', 'HR',
        'D-Marin Borik', 'Zadar', NULL,
        'D-Marin Borik (Zadar): NauSys base 4963592 and MMK base 667400400000100000 rows, 127 m apart (Mario 13.10.2026)'),
       (76, ARRAY ['Marina Baska Voda', 'Marina Baška Voda'], 2, 477801,
        1571, ARRAY ['Marina Baška Voda'], 1, 30782240000100000, 'Baška Voda', 'HR',
        'Marina Baška Voda', 'Baška Voda', NULL,
        'Marina Baška Voda (Baška Voda): NauSys base 477801 and MMK base 30782240000100000 rows, 142 m apart (Mario 13.10.2026)'),
       (548, ARRAY ['Denia, Marina de Dénia', 'Marina de Dénia'], 2, 55192998,
        848, ARRAY ['Marina de Dènia', 'Marina de Dénia'], 1, 6241768840000100000, 'Dènia', 'ES',
        'Marina de Dénia', 'Dénia', 'Marina de Dénia',
        'Marina de Dénia (Dénia): NauSys base 55192998 and MMK base 6241768840000100000 rows, 38 m apart (Mario 13.10.2026)'),
       (396, ARRAY ['Tromso', 'Tromsø'], 2, 482455,
        624, ARRAY ['Tromsø'], 1, 183988270000100000, 'Tromsø', 'NO',
        'Tromsø', 'Tromsø', NULL,
        'Tromsø (Tromsø): NauSys base 482455 and MMK base 183988270000100000 rows, 1.5 km apart (Mario 13.10.2026)'),
       (13, ARRAY ['Marina Admiral Opatija', 'Marina Admiral'], 2, 842727,
        1950, ARRAY ['Marina Admiral'], 1, 101, 'Opatija', 'HR',
        'Marina Admiral', 'Opatija', NULL,
        'Marina Admiral (Opatija): NauSys base 842727 and MMK base 101 rows, 157 m apart (Mario 13.10.2026)'),
       (229, ARRAY ['Siracusa'], 2, 482349,
        691, ARRAY ['Siracusa'], 1, 150, 'Siracusa', 'IT',
        'Siracusa', 'Siracusa', NULL,
        'Siracusa (Siracusa): NauSys base 482349 and MMK base 150 rows, 364 m apart (Mario 13.10.2026)'),
       (65, ARRAY ['Marina Spinut, Split', 'Marina Špinut'], 2, 100510,
        1421, ARRAY ['Marina Špinut'], 1, 485998870000100000, 'Split', 'HR',
        'Marina Špinut', 'Split', NULL,
        'Marina Špinut (Split): NauSys base 100510 and MMK base 485998870000100000 rows, 17 m apart (Mario 13.10.2026)'),
       (2099, ARRAY ['Naples, Porto di Mergellina'], 2, 75354092,
        2007, ARRAY ['Porto di Mergellina'], 1, 31, 'Naples', 'IT',
        NULL, NULL, NULL,
        'Porto di Mergellina (Naples): NauSys base 75354092 and MMK base 31 rows, 181 m apart; names kept (NauSys "Naples" 209) (Mario 13.10.2026)'),
       (502, ARRAY ['Göcek/Skopea Marina', 'Skopea Marina'], 2, 24457720,
        780, ARRAY ['Skopea Marina'], 1, 3593440780000100000, 'Göcek', 'TR',
        'Skopea Marina', 'Göcek', NULL,
        'Skopea Marina (Göcek): NauSys base 24457720 and MMK base 3593440780000100000 rows, 72 m apart (Mario 13.10.2026)'),
       (242, ARRAY ['Sorrento'], 2, 482355,
        1809, ARRAY ['Sorrento'], 1, 255, 'Sorrento', 'IT',
        'Sorrento', 'Sorrento', NULL,
        'Sorrento (Sorrento): NauSys base 482355 and MMK base 255 rows, 130 m apart (Mario 13.10.2026)'),
       (1781, ARRAY ['Port Calanova'], 1, 1363517430000100000,
        1516, ARRAY ['Port Calanova'], 1, 677306990000100000, 'Palma de Mallorca', 'ES',
        NULL, NULL, NULL,
        'Port Calanova (Palma de Mallorca): two MMK bases (1363517430000100000, 677306990000100000), 86 m apart (Mario 13.10.2026)');

-- both rows of a pair still carry the 13.10.2026 production data (ids, country, names, mappings, the twin's city)
CREATE TEMP VIEW v9_78_ok AS
SELECT p.*
FROM v9_78_pair p
WHERE EXISTS (SELECT 1
              FROM location a
              JOIN external_mapping em ON em.system_id = a.id AND em.type = 'Location'
                                      AND em.external_system_id = p.a_system AND em.external_id = p.a_base
              WHERE a.id = p.a_id AND a.country_code = p.cc AND btrim(a.name) = ANY (p.a_names))
  AND EXISTS (SELECT 1
              FROM location b
              JOIN external_mapping em ON em.system_id = b.id AND em.type = 'Location'
                                      AND em.external_system_id = p.b_system AND em.external_id = p.b_base
              WHERE b.id = p.b_id AND b.country_code = p.cc AND btrim(b.name) = ANY (p.b_names)
                AND btrim(b.city) = p.b_city)
  -- a renamed row must not be MMK-mapped: MMK would rewrite its city on the next run
  AND (p.new_name IS NULL
       OR NOT EXISTS (SELECT 1
                      FROM external_mapping em
                      WHERE em.type = 'Location' AND em.system_id = p.a_id AND em.external_system_id = 1));

CREATE TABLE IF NOT EXISTS ops_location_dual_source_backup_20261013 (
    id           BIGINT PRIMARY KEY,
    name         VARCHAR(255),
    city         VARCHAR(100),
    display_name TEXT,
    lat          NUMERIC,
    lon          NUMERIC,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- both rows of every pair, only the first time
INSERT INTO ops_location_dual_source_backup_20261013 (id, name, city, display_name, lat, lon)
SELECT l.id, l.name, l.city, l.display_name, l.lat, l.lon
FROM location l
JOIN v9_78_ok p ON l.id IN (p.a_id, p.b_id)
ON CONFLICT (id) DO NOTHING;

-- 1. one label: the NauSys row takes the MMK row's name and town
UPDATE location l
SET name = p.new_name,
    city = p.new_city
FROM v9_78_ok p
WHERE l.id = p.a_id
  AND p.new_name IS NOT NULL
  AND (l.name IS DISTINCT FROM p.new_name OR l.city IS DISTINCT FROM p.new_city);

-- the MMK spelling that is wrong (Dénia): the name only, MMK keeps its city
UPDATE location l
SET name = p.b_new_name
FROM v9_78_ok p
WHERE l.id = p.b_id
  AND p.b_new_name IS NOT NULL
  AND l.name IS DISTINCT FROM p.b_new_name;

-- 2. one place: search, location list and facts read the pair (only once both rows carry their final names)
INSERT INTO location_same_place (location_id, same_as_location_id, note)
SELECT p.a_id, p.b_id, p.note
FROM v9_78_ok p
WHERE (p.new_name IS NULL
       OR EXISTS (SELECT 1
                  FROM location a
                  WHERE a.id = p.a_id AND a.name = p.new_name AND a.city IS NOT DISTINCT FROM p.new_city))
  AND (p.b_new_name IS NULL OR EXISTS (SELECT 1 FROM location b WHERE b.id = p.b_id AND b.name = p.b_new_name))
  AND NOT EXISTS (SELECT 1
                  FROM location_same_place sp
                  WHERE (sp.location_id = p.a_id AND sp.same_as_location_id = p.b_id)
                     OR (sp.location_id = p.b_id AND sp.same_as_location_id = p.a_id))
ON CONFLICT DO NOTHING;

DROP VIEW v9_78_ok;

-- Check after deploy (read only):
--   SELECT id, name, city, display_name FROM location
--    WHERE id IN (150,1069,134,835,160,2017,204,904,72,1423,26,1413,76,1571,548,848,396,624,13,1950,229,691,65,1421,
--                 2099,2007,502,780,242,1809,1781,1516) ORDER BY id;
--   SELECT * FROM location_same_place ORDER BY created_at;                         -> 16 new rows (3 before)
--   GET https://api.boat4you.com/public/locations?name=lavrion -> one "Lavrion Main Port" row, id "l-1069,l-150"
--     (after the jar restart; the list cache keeps 10 h)
--   https://www.boat4you.com/search?destinations=marina%20lav%20-%20podstrana&boatTypes=MOTOR_YACHT -> 200, canonical
--     unchanged (needs the web pin), `marina lav` -> 308 to it
--
-- Undo (by hand):
--   UPDATE location l SET name = b.name, city = b.city
--   FROM ops_location_dual_source_backup_20261013 b WHERE b.id = l.id;
--   DELETE FROM location_same_place
--    WHERE (location_id, same_as_location_id) IN ((150,1069),(134,835),(160,2017),(204,904),(72,1423),(26,1413),
--          (76,1571),(548,848),(396,624),(13,1950),(229,691),(65,1421),(2099,2007),(502,780),(242,1809),(1781,1516));
