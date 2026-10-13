-- V9_79 (13.10.2026): one name per marina for the near-duplicate pairs Mario approved (docs/location-near-duplicates-review-2026-10-13.md,
-- Mario 13.10.2026: "A sve da", "C može", plus Olympic Marine, Marina di Olbia and ACI Marina Split). Same recipe as V9_77 / V9_78:
-- read those headers for what the MMK / NauSys syncs do with a location row. In short: names are written only when blank (first
-- creation), NauSys never writes city, MMK overwrites city with its own on every run but only on the rows it maps, lat / lon are
-- filled only when NULL, regions are add-only (LocationRegionOverrides pins none of these rows), display_name is a generated
-- column (name || ' | ' || city).
--
-- WEB PRECONDITION: the boat4you-web landing pins and the curated-text alias in docs/location-merge-pins-v979-2026-10-13.md must be
-- live BEFORE this migration. Without them 90 indexed landing URLs answer 404 ("marina zadar (ex. tankerkomerc)" 27, "olympic marina"
-- 18, "lavrion - olympic marine" 9, "olbia" 18, "palairos" 9, the agency-named Golfo Aranci marina 9) and "marina di golfo aranci"
-- has no curated text (noindex). The same web deploy also carries (review 13.10.2026): landingNav.ts liesInside `some` instead of
-- `every` and regionHubAbove over all of a place's dids (a NauSys row and its MMK twin sit in different regions, so a merged landing
-- otherwise drops out of its regions' link blocks - the "Olbia" pin alone empties Sardinia's), and the removal of the 9 agency-named
-- curated texts (public/seo-content/*/catamaran-charter-<agency>.html; never before the pins: today they are an indexed landing's text).
--
-- What it does, per place (unit): every row of the place takes ONE name; a NauSys row also takes its MMK twin's town as city (MMK
-- keeps writing its own); the rows go into location_same_place (V9_67), so the search's l- resolution, the location list and the
-- charter facts treat them as one place. Rename, never merge: no row is deleted, no offer / yacht / mapping is repointed, every
-- `did=l-<id>` keeps working, and the backup below undoes it.
--   #  cc  final name                          rows renamed (today's name)
--   1  HR  Marina Baotić                       57 "Trogir, Yachtclub Seget (Marina Baotić)"
--   2  HR  D-Marin Marina Mandalina            49 "Marina Mandalina, Sibenik"
--   3  IT  Marina Portorosa                    245 "Sicily, Portorosa"
--   4  HR  Marina Zadar                        27 "Marina Zadar (ex. Tankerkomerc)"                (indexed URLs 308, Mario)
--   5  GR  Marina Kos                          157 "Kos, Kos Marina"
--   7  GR  Nea Peramos Marina                  465 "Athens, Attica, Nea Peramos Marina"; MMK 1937 "Nea Peramos"
--   8  TR  Bodrum Marina                       202 "Bodrum, Milta Marina"
--   9  GR  Lavrion Main Port                   MMK 2022 "Lavrion Marina" -> group 150 / 1069 / 2022 (V9_78 pair 150 / 1069)
--  10  ES  Sant Antoni de Portmany Port        399 "Ibiza, Club Nàutic Sant Antoni de Portmany"
--  11  GR  Paros port                          590 "Paros, Paroikia Marina"
--  12  TR  Netsel Marmaris Marina              185 "Marmaris, Netsel Marina"
--  13  HR  Port of Split / West Harbour        521 "Split, West Coast (Zapadna obala)"
--  14  GR  Marina Paleros                      135 "Palairos"                                       (indexed URLs 308, Mario)
--  15  HR  Marina Agana                        55 "Marina, Marina Agana (near Trogir)"
--  16  PF  Marina de Uturoa                    359 "Polynesia, Raiatea"
--  18  FR  Hyeres Port Saint Pierre            MMK 1975 "Hyeres"
--  19  TR  Yacht Classic Hotel Marina          205 "Fethiye, Yacht Classic Hotel Marina"; MMK 1252 "Yacht Classic Hotel"
--  20  IT  La Cala (Porto Palermo)             546 "Palermo, Marina di La Cala"
--  21  GR  Marina Zeas                         116 "Athens, Zea Marina"
--  22  IT  Marina Villa Igiea                  227 "Palermo"
--  23  GR  Marina Benitses                     458 "Corfu, Benitses Port Yacht Marina"
--  24  FR  Port de Corbières                   352 "Marseille, Corbières Port Services"; MMK 1689 "Corbieres Port"
--  27  FR  Port Charles Ornano                 338 "Corsica, Ajaccio, Port De Plaisance Charles Ornano"
--  28  GR  Sami Port                           450 "Port Sami, Kefalonia"
--  29  ES  Puerto de Andratx                   573 "Palma de Mallorca,  Port Andratx"
--  30  GR  Salamis Yachting Club               462 "Yacht Club Marina Salamina, Athens area"
--  31  ES  Reial Club Nàutic Port de Pollença  173 "Mallorca, Real Club Nautic Pollenca"
--  32  IT  Pozzuoli                            257 "Naples, Pozzuoli"
--  33  FR  Port de Bonifacio                   345 "Corsica, Bonifacio"
--  34  IT  Poseidon Marina                     262 "Marina Poseidon, Milazzo"
--  35  TR  D-Marin Turgutreis                  193 "Bodrum/Turgutreis/D-Marin"
--  36  IT  Marina di Golfo Aranci              MMK 1550 (named after a charter agency; an agency name is never public)  (308, Mario)
--  37  ME  Port of Kotor                       206 "Kotor, Port of Kotor"; MMK 760 "Kotor"
--  38  ES  Real Club Náutico de Dénia          170 "Port of Denia"; MMK 1566 "Real Club Nautico Dènia" (MMK's city stays "Dènia", the
--                                              NauSys row gets "Dénia", as V9_78 did for Marina de Dénia; a different place from 548/848)
--  39  ES  Port Olímpic de Barcelona           MMK 1808 "Port Olimpic"
--  40  FR  Port of Pianottoli-Caldarello       432 "Corsica, Pianottoli"
--  41  GR  Kavala Marina                       144 "Port of Kavala"            (MMK 932 "Kavala" stays apart: Mario "ne znam")
--  42  IT  Porto di Mergellina                 209 "Naples"; 2099 "Naples, Porto di Mergellina"; MMK 1301 "Mergelina e Porto di
--  50                                          Sannazaro"; MMK 1118 "Marina Molo Luise" -> group 209 / 1118 / 1301 / 2007 / 2099 (V9_78
--                                              left 2099 unnamed because "Naples" 209 would fold into "Naples, Pozzuoli"; both renamed now)
--  43  HR  Trget Port                          11 "Trget (near Pula)"
--  44  HR  Luka Stobreč                        71 "Stobreč, Split"; MMK 1374 "Marina Stobreč" (MMK 1531 "Stobreč Port" stays apart)
--  45  HR  Port Kaštel Stari                   95 "Luka Kaštel Stari"
--  46  TR  Gümbet Limanı                       445 "Bodrum, Gümbet Marina"; MMK 831 "Gumbet Limani"
--  48  HR  Sumpetar                            582 "Sumpetar, Marina Sumpetar"
--  49  HR  Port of Vodice                      45 "Vodice"
--  51  BZ  Robert's Grove Marina               265 "Belize, Placencia, Roberts Grove Marina" (MMK 1375 "Placencia", ~5 km south, no
--                                              boat based there but the pick-up of 3 Robert's Grove boats - 346 bookable future
--                                              offers - was folded in by name and becomes its own list row; Mario's call)
--  52  IT  Marina di Riposto                   419 "Sicily, Riposto"; MMK 1045 "Riposto"
--  53  GR  Port of Pachi                       452 "Megara, Port Pachi"
--  54  FR  Vieux-Port de Cannes                2093 "Cannes"; MMK 2002 "IGY Vieux-Port de Cannes"
--  55  HR  Marina Baška Voda                   MMK 1387 "Luka Baška Voda" -> group 76 / 1387 / 1571 (V9_78 pair 76 / 1571)
--  56  ME  Jamb Marina                         2098 "Tivat, Jamb Marina"
--  58  GR  Oia Port                            317 "Santorini, Marina Oia"; MMK 619 "Oia port"
--   E  GR  Olympic Marine (the marina's own name, olympicmarine.gr)   139 "Lavrion - Olympic Marine" + MMK 710 "Olympic Marina";
--                                              139 also takes 710's coordinates (NauSys put it on the Lavrion town port, 1.9 km north)
--   E  IT  Marina di Olbia                     222 "Olbia" (pairs with MMK 609; 222 takes 609's coordinates, NauSys put it on the town
--                                              port 1.7 km west). MMK 1208 "Molo Brin" stays apart (Mario B #17). NauSys 225 "Marina di
--                                              Olbia Yachting Services" (0 active boats) is already one place with 609 by name and stays
--                                              as it is (its name still labels the list row; Mario's call).
--   E  HR  ACI Marina Split                    MMK 1755 "Split" (1 boat, no region): paired with 69 (ACI Marina Split, MMK 9 + NauSys
--                                              56), takes 69's coordinates and region 5 Split (Mario: "Split je pod Split, stavi ACI Split").
--                                              NauSys 67 "Trajektna luka Split" stays apart (Mario B #57).
-- Not in this migration: #25 Tourlos Marina (NauSys 158 "Mykonos" + MMK 1561). 158 sits in the indexed "Port of Mykonos" landing by
-- name today; renamed, Port of Mykonos keeps 9 boats (3 catamarans) and its 18 indexed URLs fall to noindex - waits for Mario.
-- B (#6, 17, 26, 47, 57) and the third rows Zea 1554, Kavala 932, Marmaris 1395 (Mario: "ne znam") are not touched.
--
-- Side effects of the one name (review 13.10.2026, prod read only):
--   - yacht_listing_twin (V9_69 / V9_70) pairs two copies of a boat by the base NAME (base_key). With one name per place the MMK and
--     NauSys copies of 131 boats at these places become twins: the next refresh (YachtSearchViewRefresher, <= 10 min) hides the
--     second copy from the undated search, the landings' fleet counts, the sitemaps and the charter facts (audit B17, one card per
--     boat; 557 -> 688 twins on 13.10.). None leaves a place: every copy shown sits in the same place. Biggest: Mandalina 26,
--     Portorosa 18, Marina Zadar 14, Olympic Marine 12, Sant Antoni 9, Paleros 8, Pollença 7.
--   - YachtSuccessorComputeService (same twin table + base_key): 22 retired boats' 308 targets move to the copy the listings show
--     (same boat, other channel; all 22 still pass the successor rule) on the next 07:55 run.
--   - Charter facts: NauSys 233 "Milazzo" (pick-up of 1 Portorosa boat) is in the Poseidon Marina place today only because "milazzo"
--     sits inside "Marina Poseidon, Milazzo"; after #34 it is a place of its own (no indexed landing).
--   - Autocomplete (search_filed = name + city): "trogir" no longer finds Marina Baotić (57 / 1749) or Marina Agana (55 / 993) - the
--     NauSys names carried "Trogir", the MMK twins' towns are Seget Donji / Marina; no "Trogir" region exists. Mario's call.
--
-- Groups: LocationRepository.findSamePlaceMarinaIds pairs rows by the same name (country, <= 50 km) plus the anchor's OWN
-- location_same_place rows - not transitive. Every group gets a full mesh of pairs, so each member did resolves the whole group even
-- without the name rule. The location list (LocationQueryingService.mergeDualSourceMarinas) joins a curated pair that touches a
-- merged row into one row since the code shipped with this migration (same jar); an older jar shows a group as two or three rows.
--
-- Guards (as V9_78): each place is applied only when ALL its rows still carry the 13.10.2026 production data - id, country, today's
-- name (or the final one), the partner mapping, an MMK row's city, the coordinates being corrected - and a renamed NauSys row has no
-- MMK mapping (MMK would rewrite its city). A place whose data moved is skipped whole; a database where these ids are other rows (local
-- dev) changes nothing; a second run changes nothing. Backup: ops_location_near_dup_backup_20261013 (every row of every place,
-- before the fix, with its regions).
-- The catalogue syncs write location rows; give up fast instead of waiting on a sync transaction - a timeout rolls the migration
-- back and the API's systemd restart tries again (deploy outside the sync windows, DEPLOY_NOTES).
SET LOCAL lock_timeout = '5s';

CREATE TEMP TABLE v9_79_row (
    unit      TEXT    NOT NULL,   -- the place (pNN = Mario's pair number, or a group / extra)
    id        BIGINT  PRIMARY KEY,
    sys       INT     NOT NULL,   -- external_system_id of the partner mapping the row must have (1 MMK, 2 NauSys)
    base      BIGINT  NOT NULL,   -- that mapping's external_id (the partner's base id)
    cc        TEXT    NOT NULL,
    names     TEXT[]  NOT NULL,   -- accepted names: today's production name and the final one
    city      TEXT,               -- an MMK row: its city as MMK writes it (guard); NULL for a NauSys row
    new_name  TEXT    NOT NULL,
    new_city  TEXT,               -- a NauSys row: the MMK twin's town; NULL = city unchanged
    lat_now   NUMERIC,            -- coordinates to correct: today's (guard) ...
    lon_now   NUMERIC,
    new_lat   NUMERIC,            -- ... and the marina's
    new_lon   NUMERIC,
    region_id INT                 -- a region to add (NULL = none)
) ON COMMIT DROP;

CREATE TEMP TABLE v9_79_pair (
    unit TEXT   NOT NULL,
    a_id BIGINT NOT NULL,         -- location_same_place.location_id
    b_id BIGINT NOT NULL,         -- location_same_place.same_as_location_id
    note TEXT   NOT NULL,
    PRIMARY KEY (a_id, b_id)
) ON COMMIT DROP;

INSERT INTO v9_79_row (unit, id, sys, base, cc, names, city, new_name, new_city, lat_now, lon_now, new_lat, new_lon, region_id)
VALUES
('p01', 57, 2, 101164, 'HR', ARRAY ['Trogir, Yachtclub Seget (Marina Baotić)', 'Marina Baotić'], NULL, 'Marina Baotić', 'Seget Donji', NULL, NULL, NULL, NULL, NULL),
('p01', 1749, 1, 288201290000100000, 'HR', ARRAY ['Marina Baotić'], 'Seget Donji', 'Marina Baotić', NULL, NULL, NULL, NULL, NULL, NULL),
('p02', 49, 2, 66, 'HR', ARRAY ['Marina Mandalina, Sibenik', 'D-Marin Marina Mandalina'], NULL, 'D-Marin Marina Mandalina', 'Šibenik', NULL, NULL, NULL, NULL, NULL),
('p02', 1813, 1, 251, 'HR', ARRAY ['D-Marin Marina Mandalina'], 'Šibenik', 'D-Marin Marina Mandalina', NULL, NULL, NULL, NULL, NULL, NULL),
('p03', 245, 2, 482362, 'IT', ARRAY ['Sicily, Portorosa', 'Marina Portorosa'], NULL, 'Marina Portorosa', 'Furnari', NULL, NULL, NULL, NULL, NULL),
('p03', 1831, 1, 232, 'IT', ARRAY ['Marina Portorosa'], 'Furnari', 'Marina Portorosa', NULL, NULL, NULL, NULL, NULL, NULL),
('p04', 27, 2, 61, 'HR', ARRAY ['Marina Zadar (ex. Tankerkomerc)', 'Marina Zadar'], NULL, 'Marina Zadar', 'Zadar', NULL, NULL, NULL, NULL, NULL),
('p04', 1941, 1, 110, 'HR', ARRAY ['Marina Zadar'], 'Zadar', 'Marina Zadar', NULL, NULL, NULL, NULL, NULL, NULL),
('p05', 157, 2, 108079, 'GR', ARRAY ['Kos, Kos Marina', 'Marina Kos'], NULL, 'Marina Kos', 'Kos', NULL, NULL, NULL, NULL, NULL),
('p05', 2023, 1, 15, 'GR', ARRAY ['Marina Kos'], 'Kos', 'Marina Kos', NULL, NULL, NULL, NULL, NULL, NULL),
('p07', 465, 2, 17393174, 'GR', ARRAY ['Athens, Attica, Nea Peramos Marina', 'Nea Peramos Marina'], NULL, 'Nea Peramos Marina', 'Nea Peramos - Attica', NULL, NULL, NULL, NULL, NULL),
('p07', 1937, 1, 2689609200000100000, 'GR', ARRAY ['Nea Peramos', 'Nea Peramos Marina'], 'Nea Peramos - Attica', 'Nea Peramos Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('p08', 202, 2, 478048, 'TR', ARRAY ['Bodrum, Milta Marina', 'Bodrum Marina'], NULL, 'Bodrum Marina', 'Bodrum', NULL, NULL, NULL, NULL, NULL),
('p08', 2013, 1, 25, 'TR', ARRAY ['Bodrum Marina'], 'Bodrum', 'Bodrum Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('lavrion', 150, 2, 125450, 'GR', ARRAY ['Lavrion Main Port'], NULL, 'Lavrion Main Port', 'Lavrion', NULL, NULL, NULL, NULL, NULL),
('lavrion', 1069, 1, 18220780000100000, 'GR', ARRAY ['Lavrion Main Port'], 'Lavrion', 'Lavrion Main Port', NULL, NULL, NULL, NULL, NULL, NULL),
('lavrion', 2022, 1, 16, 'GR', ARRAY ['Lavrion Marina', 'Lavrion Main Port'], 'Lavrion', 'Lavrion Main Port', NULL, NULL, NULL, NULL, NULL, NULL),
('p10', 399, 2, 12885024, 'ES', ARRAY ['Ibiza, Club Nàutic Sant Antoni de Portmany', 'Sant Antoni de Portmany Port'], NULL, 'Sant Antoni de Portmany Port', 'Sant Antoni de Portmany', NULL, NULL, NULL, NULL, NULL),
('p10', 1434, 1, 85785960000100000, 'ES', ARRAY ['Sant Antoni de Portmany Port'], 'Sant Antoni de Portmany', 'Sant Antoni de Portmany Port', NULL, NULL, NULL, NULL, NULL, NULL),
('p11', 590, 2, 65617310, 'GR', ARRAY ['Paros, Paroikia Marina', 'Paros port'], NULL, 'Paros port', 'Paros', NULL, NULL, NULL, NULL, NULL),
('p11', 1845, 1, 216, 'GR', ARRAY ['Paros port'], 'Paros', 'Paros port', NULL, NULL, NULL, NULL, NULL, NULL),
('p12', 185, 2, 122465, 'TR', ARRAY ['Marmaris, Netsel Marina', 'Netsel Marmaris Marina'], NULL, 'Netsel Marmaris Marina', 'Marmaris', NULL, NULL, NULL, NULL, NULL),
('p12', 2012, 1, 26, 'TR', ARRAY ['Netsel Marmaris Marina'], 'Marmaris', 'Netsel Marmaris Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('p13', 521, 2, 31336716, 'HR', ARRAY ['Split, West Coast (Zapadna obala)', 'Port of Split / West Harbour'], NULL, 'Port of Split / West Harbour', 'Split', NULL, NULL, NULL, NULL, NULL),
('p13', 1693, 1, 6463919540000100000, 'HR', ARRAY ['Port of Split / West Harbour'], 'Split', 'Port of Split / West Harbour', NULL, NULL, NULL, NULL, NULL, NULL),
('p14', 135, 2, 832589, 'GR', ARRAY ['Palairos', 'Marina Paleros'], NULL, 'Marina Paleros', 'Palairos', NULL, NULL, NULL, NULL, NULL),
('p14', 828, 1, 395874570000100000, 'GR', ARRAY ['Marina Paleros'], 'Palairos', 'Marina Paleros', NULL, NULL, NULL, NULL, NULL, NULL),
('p15', 55, 2, 63, 'HR', ARRAY ['Marina, Marina Agana (near Trogir)', 'Marina Agana'], NULL, 'Marina Agana', 'Marina', NULL, NULL, NULL, NULL, NULL),
('p15', 993, 1, 18221310000100000, 'HR', ARRAY ['Marina Agana'], 'Marina', 'Marina Agana', NULL, NULL, NULL, NULL, NULL, NULL),
('p16', 359, 2, 482516, 'PF', ARRAY ['Polynesia, Raiatea', 'Marina de Uturoa'], NULL, 'Marina de Uturoa', 'Raiatea', NULL, NULL, NULL, NULL, NULL),
('p16', 1979, 1, 64, 'PF', ARRAY ['Marina de Uturoa'], 'Raiatea', 'Marina de Uturoa', NULL, NULL, NULL, NULL, NULL, NULL),
('p18', 346, 2, 482374, 'FR', ARRAY ['Hyeres Port Saint Pierre'], NULL, 'Hyeres Port Saint Pierre', 'Hyeres', NULL, NULL, NULL, NULL, NULL),
('p18', 1975, 1, 68, 'FR', ARRAY ['Hyeres', 'Hyeres Port Saint Pierre'], 'Hyeres', 'Hyeres Port Saint Pierre', NULL, NULL, NULL, NULL, NULL, NULL),
('p19', 205, 2, 8546879, 'TR', ARRAY ['Fethiye, Yacht Classic Hotel Marina', 'Yacht Classic Hotel Marina'], NULL, 'Yacht Classic Hotel Marina', 'Fethiye', NULL, NULL, NULL, NULL, NULL),
('p19', 1252, 1, 3295920880000100000, 'TR', ARRAY ['Yacht Classic Hotel', 'Yacht Classic Hotel Marina'], 'Fethiye', 'Yacht Classic Hotel Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('p20', 546, 2, 36603312, 'IT', ARRAY ['Palermo, Marina di La Cala', 'La Cala (Porto Palermo)'], NULL, 'La Cala (Porto Palermo)', 'Palermo', NULL, NULL, NULL, NULL, NULL),
('p20', 738, 1, 125712970000100000, 'IT', ARRAY ['La Cala (Porto Palermo)'], 'Palermo', 'La Cala (Porto Palermo)', NULL, NULL, NULL, NULL, NULL, NULL),
('p21', 116, 2, 1222257, 'GR', ARRAY ['Athens, Zea Marina', 'Marina Zeas'], NULL, 'Marina Zeas', 'Piraeus', NULL, NULL, NULL, NULL, NULL),
('p21', 910, 1, 1055942990000100000, 'GR', ARRAY ['Marina Zeas'], 'Piraeus', 'Marina Zeas', NULL, NULL, NULL, NULL, NULL, NULL),
('p22', 227, 2, 482322, 'IT', ARRAY ['Palermo', 'Marina Villa Igiea'], NULL, 'Marina Villa Igiea', 'Palermo', NULL, NULL, NULL, NULL, NULL),
('p22', 1229, 1, 956454820000100000, 'IT', ARRAY ['Marina Villa Igiea'], 'Palermo', 'Marina Villa Igiea', NULL, NULL, NULL, NULL, NULL, NULL),
('p23', 458, 2, 10377818, 'GR', ARRAY ['Corfu, Benitses Port Yacht Marina', 'Marina Benitses'], NULL, 'Marina Benitses', 'Mpenitses', NULL, NULL, NULL, NULL, NULL),
('p23', 855, 1, 3129817260000100000, 'GR', ARRAY ['Marina Benitses'], 'Mpenitses', 'Marina Benitses', NULL, NULL, NULL, NULL, NULL, NULL),
('p24', 352, 2, 25235575, 'FR', ARRAY ['Marseille, Corbières Port Services', 'Port de Corbières'], NULL, 'Port de Corbières', 'Marseille', NULL, NULL, NULL, NULL, NULL),
('p24', 1689, 1, 6241349230000100000, 'FR', ARRAY ['Corbieres Port', 'Port de Corbières'], 'Marseille', 'Port de Corbières', NULL, NULL, NULL, NULL, NULL, NULL),
('p27', 338, 2, 3476794, 'FR', ARRAY ['Corsica, Ajaccio, Port De Plaisance Charles Ornano', 'Port Charles Ornano'], NULL, 'Port Charles Ornano', 'Ajaccio', NULL, NULL, NULL, NULL, NULL),
('p27', 1960, 1, 86, 'FR', ARRAY ['Port Charles Ornano'], 'Ajaccio', 'Port Charles Ornano', NULL, NULL, NULL, NULL, NULL, NULL),
('p28', 450, 2, 15123144, 'GR', ARRAY ['Port Sami, Kefalonia', 'Sami Port'], NULL, 'Sami Port', 'Sami', NULL, NULL, NULL, NULL, NULL),
('p28', 1921, 1, 133, 'GR', ARRAY ['Sami Port'], 'Sami', 'Sami Port', NULL, NULL, NULL, NULL, NULL, NULL),
('p29', 573, 2, 35916688, 'ES', ARRAY ['Palma de Mallorca,  Port Andratx', 'Puerto de Andratx'], NULL, 'Puerto de Andratx', 'Andratx', NULL, NULL, NULL, NULL, NULL),
('p29', 744, 1, 640405370000100000, 'ES', ARRAY ['Puerto de Andratx'], 'Andratx', 'Puerto de Andratx', NULL, NULL, NULL, NULL, NULL, NULL),
('p30', 462, 2, 16333611, 'GR', ARRAY ['Yacht Club Marina Salamina, Athens area', 'Salamis Yachting Club'], NULL, 'Salamis Yachting Club', 'Salamina', NULL, NULL, NULL, NULL, NULL),
('p30', 1250, 1, 3426109020000100000, 'GR', ARRAY ['Salamis Yachting Club'], 'Salamina', 'Salamis Yachting Club', NULL, NULL, NULL, NULL, NULL, NULL),
('p31', 173, 2, 482396, 'ES', ARRAY ['Mallorca, Real Club Nautic Pollenca', 'Reial Club Nàutic Port de Pollença'], NULL, 'Reial Club Nàutic Port de Pollença', 'Port de Pollença', NULL, NULL, NULL, NULL, NULL),
('p31', 1211, 1, 125496490000100000, 'ES', ARRAY ['Reial Club Nàutic Port de Pollença'], 'Port de Pollença', 'Reial Club Nàutic Port de Pollença', NULL, NULL, NULL, NULL, NULL, NULL),
('p32', 257, 2, 842444, 'IT', ARRAY ['Naples, Pozzuoli', 'Pozzuoli'], NULL, 'Pozzuoli', 'Pozzuoli', NULL, NULL, NULL, NULL, NULL),
('p32', 1830, 1, 233, 'IT', ARRAY ['Pozzuoli'], 'Pozzuoli', 'Pozzuoli', NULL, NULL, NULL, NULL, NULL, NULL),
('p33', 345, 2, 482373, 'FR', ARRAY ['Corsica, Bonifacio', 'Port de Bonifacio'], NULL, 'Port de Bonifacio', 'Bonifacio', NULL, NULL, NULL, NULL, NULL),
('p33', 1884, 1, 174, 'FR', ARRAY ['Port de Bonifacio'], 'Bonifacio', 'Port de Bonifacio', NULL, NULL, NULL, NULL, NULL, NULL),
('p34', 262, 2, 10268474, 'IT', ARRAY ['Marina Poseidon, Milazzo', 'Poseidon Marina'], NULL, 'Poseidon Marina', 'Milazzo', NULL, NULL, NULL, NULL, NULL),
('p34', 655, 1, 3739560640000100000, 'IT', ARRAY ['Poseidon Marina'], 'Milazzo', 'Poseidon Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('p35', 193, 2, 109045, 'TR', ARRAY ['Bodrum/Turgutreis/D-Marin', 'D-Marin Turgutreis'], NULL, 'D-Marin Turgutreis', 'Bodrum', NULL, NULL, NULL, NULL, NULL),
('p35', 1936, 1, 118, 'TR', ARRAY ['D-Marin Turgutreis'], 'Bodrum', 'D-Marin Turgutreis', NULL, NULL, NULL, NULL, NULL, NULL),
('p36', 411, 2, 10373986, 'IT', ARRAY ['Marina di Golfo Aranci'], NULL, 'Marina di Golfo Aranci', 'Golfo Aranci', NULL, NULL, NULL, NULL, NULL),
('p36', 1550, 1, 4523886870000100000, 'IT', ARRAY ['Mare Fun Charter & Marina', 'Marina di Golfo Aranci'], 'Golfo Aranci', 'Marina di Golfo Aranci', NULL, NULL, NULL, NULL, NULL, NULL),
('p37', 206, 2, 482365, 'ME', ARRAY ['Kotor, Port of Kotor', 'Port of Kotor'], NULL, 'Port of Kotor', 'Kotor', NULL, NULL, NULL, NULL, NULL),
('p37', 760, 1, 27289680000100000, 'ME', ARRAY ['Kotor', 'Port of Kotor'], 'Kotor', 'Port of Kotor', NULL, NULL, NULL, NULL, NULL, NULL),
('p38', 170, 2, 482394, 'ES', ARRAY ['Port of Denia', 'Real Club Náutico de Dénia'], NULL, 'Real Club Náutico de Dénia', 'Dénia', NULL, NULL, NULL, NULL, NULL),
('p38', 1566, 1, 32688140000100000, 'ES', ARRAY ['Real Club Nautico Dènia', 'Real Club Náutico de Dénia'], 'Dènia', 'Real Club Náutico de Dénia', NULL, NULL, NULL, NULL, NULL, NULL),
('p39', 181, 2, 834511, 'ES', ARRAY ['Port Olímpic de Barcelona'], NULL, 'Port Olímpic de Barcelona', 'Barcelona', NULL, NULL, NULL, NULL, NULL),
('p39', 1808, 1, 256, 'ES', ARRAY ['Port Olimpic', 'Port Olímpic de Barcelona'], 'Barcelona', 'Port Olímpic de Barcelona', NULL, NULL, NULL, NULL, NULL, NULL),
('p40', 432, 2, 32262650, 'FR', ARRAY ['Corsica, Pianottoli', 'Port of Pianottoli-Caldarello'], NULL, 'Port of Pianottoli-Caldarello', 'Pianottoli-Caldarello', NULL, NULL, NULL, NULL, NULL),
('p40', 1360, 1, 3860237560000100000, 'FR', ARRAY ['Port of Pianottoli-Caldarello'], 'Pianottoli-Caldarello', 'Port of Pianottoli-Caldarello', NULL, NULL, NULL, NULL, NULL, NULL),
('p41', 144, 2, 6517793, 'GR', ARRAY ['Port of Kavala', 'Kavala Marina'], NULL, 'Kavala Marina', 'Kavala', NULL, NULL, NULL, NULL, NULL),
('p41', 1644, 1, 6320614840000100000, 'GR', ARRAY ['Kavala Marina'], 'Kavala', 'Kavala Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('mergellina', 209, 2, 119154, 'IT', ARRAY ['Naples', 'Porto di Mergellina'], NULL, 'Porto di Mergellina', 'Naples', NULL, NULL, NULL, NULL, NULL),
('mergellina', 1118, 1, 3558170880000100000, 'IT', ARRAY ['Marina Molo Luise', 'Porto di Mergellina'], 'Naples', 'Porto di Mergellina', NULL, NULL, NULL, NULL, NULL, NULL),
('mergellina', 1301, 1, 4979527500000100000, 'IT', ARRAY ['Mergelina e Porto di Sannazaro', 'Porto di Mergellina'], 'Naples', 'Porto di Mergellina', NULL, NULL, NULL, NULL, NULL, NULL),
('mergellina', 2007, 1, 31, 'IT', ARRAY ['Porto di Mergellina'], 'Naples', 'Porto di Mergellina', NULL, NULL, NULL, NULL, NULL, NULL),
('mergellina', 2099, 2, 75354092, 'IT', ARRAY ['Naples, Porto di Mergellina', 'Porto di Mergellina'], NULL, 'Porto di Mergellina', 'Naples', NULL, NULL, NULL, NULL, NULL),
('p43', 11, 2, 590616, 'HR', ARRAY ['Trget (near Pula)', 'Trget Port'], NULL, 'Trget Port', 'Pula', NULL, NULL, NULL, NULL, NULL),
('p43', 1451, 1, 532139690000100000, 'HR', ARRAY ['Trget Port'], 'Pula', 'Trget Port', NULL, NULL, NULL, NULL, NULL, NULL),
('p44', 71, 2, 1080804, 'HR', ARRAY ['Stobreč, Split', 'Luka Stobreč'], NULL, 'Luka Stobreč', 'Stobreč', NULL, NULL, NULL, NULL, NULL),
('p44', 1374, 1, 4182110170000100000, 'HR', ARRAY ['Marina Stobreč', 'Luka Stobreč'], 'Stobreč', 'Luka Stobreč', NULL, NULL, NULL, NULL, NULL, NULL),
('p45', 95, 2, 35219758, 'HR', ARRAY ['Luka Kaštel Stari', 'Port Kaštel Stari'], NULL, 'Port Kaštel Stari', 'Kaštel Stari', NULL, NULL, NULL, NULL, NULL),
('p45', 2119, 1, 8201197930000100000, 'HR', ARRAY ['Port Kaštel Stari'], 'Kaštel Stari', 'Port Kaštel Stari', NULL, NULL, NULL, NULL, NULL, NULL),
('p46', 445, 2, 10422207, 'TR', ARRAY ['Bodrum, Gümbet Marina', 'Gümbet Limanı'], NULL, 'Gümbet Limanı', 'Bodrum', NULL, NULL, NULL, NULL, NULL),
('p46', 831, 1, 5896157740000100000, 'TR', ARRAY ['Gumbet Limani', 'Gümbet Limanı'], 'Bodrum', 'Gümbet Limanı', NULL, NULL, NULL, NULL, NULL, NULL),
('p48', 582, 2, 55524245, 'HR', ARRAY ['Sumpetar, Marina Sumpetar', 'Sumpetar'], NULL, 'Sumpetar', 'Sumpetar', NULL, NULL, NULL, NULL, NULL),
('p48', 1528, 1, 4460821360000100000, 'HR', ARRAY ['Sumpetar'], 'Sumpetar', 'Sumpetar', NULL, NULL, NULL, NULL, NULL, NULL),
('p49', 45, 2, 580277, 'HR', ARRAY ['Vodice', 'Port of Vodice'], NULL, 'Port of Vodice', 'Vodice', NULL, NULL, NULL, NULL, NULL),
('p49', 1738, 1, 3973641420000100000, 'HR', ARRAY ['Port of Vodice'], 'Vodice', 'Port of Vodice', NULL, NULL, NULL, NULL, NULL, NULL),
('p51', 265, 2, 10227225, 'BZ', ARRAY ['Belize, Placencia, Roberts Grove Marina', 'Robert''s Grove Marina'], NULL, 'Robert''s Grove Marina', 'Placencia', NULL, NULL, NULL, NULL, NULL),
('p51', 1302, 1, 5018840130000100000, 'BZ', ARRAY ['Robert''s Grove Marina'], 'Placencia', 'Robert''s Grove Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('p52', 419, 2, 8560010, 'IT', ARRAY ['Sicily, Riposto', 'Marina di Riposto'], NULL, 'Marina di Riposto', 'Riposto', NULL, NULL, NULL, NULL, NULL),
('p52', 1045, 1, 322902220000100000, 'IT', ARRAY ['Riposto', 'Marina di Riposto'], 'Riposto', 'Marina di Riposto', NULL, NULL, NULL, NULL, NULL, NULL),
('p53', 452, 2, 33215132, 'GR', ARRAY ['Megara, Port Pachi', 'Port of Pachi'], NULL, 'Port of Pachi', 'Pachi, Megara', NULL, NULL, NULL, NULL, NULL),
('p53', 1654, 1, 4611181660000100000, 'GR', ARRAY ['Port of Pachi'], 'Pachi, Megara', 'Port of Pachi', NULL, NULL, NULL, NULL, NULL, NULL),
('p54', 2002, 1, 36, 'FR', ARRAY ['IGY Vieux-Port de Cannes', 'Vieux-Port de Cannes'], 'Cannes', 'Vieux-Port de Cannes', NULL, NULL, NULL, NULL, NULL, NULL),
('p54', 2093, 2, 861137, 'FR', ARRAY ['Cannes', 'Vieux-Port de Cannes'], NULL, 'Vieux-Port de Cannes', 'Cannes', NULL, NULL, NULL, NULL, NULL),
('baska_voda', 76, 2, 477801, 'HR', ARRAY ['Marina Baška Voda'], NULL, 'Marina Baška Voda', 'Baška Voda', NULL, NULL, NULL, NULL, NULL),
('baska_voda', 1387, 1, 5633158080000100000, 'HR', ARRAY ['Luka Baška Voda', 'Marina Baška Voda'], 'Baška Voda', 'Marina Baška Voda', NULL, NULL, NULL, NULL, NULL, NULL),
('baska_voda', 1571, 1, 30782240000100000, 'HR', ARRAY ['Marina Baška Voda'], 'Baška Voda', 'Marina Baška Voda', NULL, NULL, NULL, NULL, NULL, NULL),
('p56', 2072, 1, 7705537160000100000, 'ME', ARRAY ['Jamb Marina'], 'Mrcevac', 'Jamb Marina', NULL, NULL, NULL, NULL, NULL, NULL),
('p56', 2098, 2, 75207402, 'ME', ARRAY ['Tivat, Jamb Marina', 'Jamb Marina'], NULL, 'Jamb Marina', 'Mrcevac', NULL, NULL, NULL, NULL, NULL),
('p58', 317, 2, 72462131, 'GR', ARRAY ['Santorini, Marina Oia', 'Oia Port'], NULL, 'Oia Port', 'Oia', NULL, NULL, NULL, NULL, NULL),
('p58', 619, 1, 3406806320000100000, 'GR', ARRAY ['Oia port', 'Oia Port'], 'Oia', 'Oia Port', NULL, NULL, NULL, NULL, NULL, NULL),
('aci_split', 69, 1, 9, 'HR', ARRAY ['ACI Marina Split'], 'Split', 'ACI Marina Split', NULL, NULL, NULL, NULL, NULL, NULL),
('aci_split', 1755, 1, 32326420000100000, 'HR', ARRAY ['Split', 'ACI Marina Split'], 'Split', 'ACI Marina Split', NULL, 43.503771, 16.441621, 43.5024, 16.4295, 5),
('olbia', 222, 2, 517915, 'IT', ARRAY ['Olbia', 'Marina di Olbia'], NULL, 'Marina di Olbia', 'Olbia', 40.92269, 9.50867, 40.91588, 9.526216, NULL),
('olbia', 609, 1, 210, 'IT', ARRAY ['Marina di Olbia'], 'Olbia', 'Marina di Olbia', NULL, NULL, NULL, NULL, NULL, NULL),
('olympic', 139, 2, 1002560, 'GR', ARRAY ['Lavrion - Olympic Marine', 'Olympic Marine'], NULL, 'Olympic Marine', 'Lavrion', 37.7123, 24.056, 37.69547, 24.06044, NULL),
('olympic', 710, 1, 484655120000100000, 'GR', ARRAY ['Olympic Marina', 'Olympic Marine'], 'Lavrion', 'Olympic Marine', NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO v9_79_pair (unit, a_id, b_id, note)
VALUES
('p01', 57, 1749, 'Marina Baotić (Seget Donji): NauSys base 101164 and MMK base 288201290000100000 rows, 79 m apart (Mario 13.10.2026, V9_79)'),
('p02', 49, 1813, 'D-Marin Marina Mandalina (Šibenik): NauSys base 66 and MMK base 251 rows, 126 m apart (Mario 13.10.2026, V9_79)'),
('p03', 245, 1831, 'Marina Portorosa (Furnari): NauSys base 482362 and MMK base 232 rows, 11 m apart (Mario 13.10.2026, V9_79)'),
('p04', 27, 1941, 'Marina Zadar (Zadar): NauSys base 61 and MMK base 110 rows, 21 m apart (Mario 13.10.2026, V9_79)'),
('p05', 157, 2023, 'Marina Kos (Kos): NauSys base 108079 and MMK base 15 rows, 136 m apart (Mario 13.10.2026, V9_79)'),
('p07', 465, 1937, 'Nea Peramos Marina (Nea Peramos - Attica): NauSys base 17393174 and MMK base 2689609200000100000 rows, 131 m apart (Mario 13.10.2026, V9_79)'),
('p08', 202, 2013, 'Bodrum Marina (Bodrum): NauSys base 478048 and MMK base 25 rows, 68 m apart (Mario 13.10.2026, V9_79)'),
('lavrion', 150, 2022, 'Lavrion Main Port (Lavrion): NauSys base 125450 and MMK base 16 rows, 95 m apart; one place of 3 rows (150, 1069, 2022) (Mario 13.10.2026, V9_79)'),
('lavrion', 1069, 2022, 'Lavrion Main Port (Lavrion): MMK base 18220780000100000 and MMK base 16 rows, 210 m apart; one place of 3 rows (150, 1069, 2022) (Mario 13.10.2026, V9_79)'),
('p10', 399, 1434, 'Sant Antoni de Portmany Port (Sant Antoni de Portmany): NauSys base 12885024 and MMK base 85785960000100000 rows, 110 m apart (Mario 13.10.2026, V9_79)'),
('p11', 590, 1845, 'Paros port (Paros): NauSys base 65617310 and MMK base 216 rows, 140 m apart (Mario 13.10.2026, V9_79)'),
('p12', 185, 2012, 'Netsel Marmaris Marina (Marmaris): NauSys base 122465 and MMK base 26 rows, 72 m apart (Mario 13.10.2026, V9_79)'),
('p13', 521, 1693, 'Port of Split / West Harbour (Split): NauSys base 31336716 and MMK base 6463919540000100000 rows, 52 m apart (Mario 13.10.2026, V9_79)'),
('p14', 135, 828, 'Marina Paleros (Palairos): NauSys base 832589 and MMK base 395874570000100000 rows, 25 m apart (Mario 13.10.2026, V9_79)'),
('p15', 55, 993, 'Marina Agana (Marina): NauSys base 63 and MMK base 18221310000100000 rows, 142 m apart (Mario 13.10.2026, V9_79)'),
('p16', 359, 1979, 'Marina de Uturoa (Raiatea): NauSys base 482516 and MMK base 64 rows, 147 m apart (Mario 13.10.2026, V9_79)'),
('p18', 346, 1975, 'Hyeres Port Saint Pierre (Hyeres): NauSys base 482374 and MMK base 68 rows, 33 m apart (Mario 13.10.2026, V9_79)'),
('p19', 205, 1252, 'Yacht Classic Hotel Marina (Fethiye): NauSys base 8546879 and MMK base 3295920880000100000 rows, 30 m apart (Mario 13.10.2026, V9_79)'),
('p20', 546, 738, 'La Cala (Porto Palermo) (Palermo): NauSys base 36603312 and MMK base 125712970000100000 rows, 116 m apart (Mario 13.10.2026, V9_79)'),
('p21', 116, 910, 'Marina Zeas (Piraeus): NauSys base 1222257 and MMK base 1055942990000100000 rows, 24 m apart (Mario 13.10.2026, V9_79)'),
('p22', 227, 1229, 'Marina Villa Igiea (Palermo): NauSys base 482322 and MMK base 956454820000100000 rows, 39 m apart (Mario 13.10.2026, V9_79)'),
('p23', 458, 855, 'Marina Benitses (Mpenitses): NauSys base 10377818 and MMK base 3129817260000100000 rows, 34 m apart (Mario 13.10.2026, V9_79)'),
('p24', 352, 1689, 'Port de Corbières (Marseille): NauSys base 25235575 and MMK base 6241349230000100000 rows, 90 m apart (Mario 13.10.2026, V9_79)'),
('p27', 338, 1960, 'Port Charles Ornano (Ajaccio): NauSys base 3476794 and MMK base 86 rows, 7 m apart (Mario 13.10.2026, V9_79)'),
('p28', 450, 1921, 'Sami Port (Sami): NauSys base 15123144 and MMK base 133 rows, 76 m apart (Mario 13.10.2026, V9_79)'),
('p29', 573, 744, 'Puerto de Andratx (Andratx): NauSys base 35916688 and MMK base 640405370000100000 rows, 118 m apart (Mario 13.10.2026, V9_79)'),
('p30', 462, 1250, 'Salamis Yachting Club (Salamina): NauSys base 16333611 and MMK base 3426109020000100000 rows, 40 m apart (Mario 13.10.2026, V9_79)'),
('p31', 173, 1211, 'Reial Club Nàutic Port de Pollença (Port de Pollença): NauSys base 482396 and MMK base 125496490000100000 rows, 107 m apart (Mario 13.10.2026, V9_79)'),
('p32', 257, 1830, 'Pozzuoli (Pozzuoli): NauSys base 842444 and MMK base 233 rows, 21 m apart (Mario 13.10.2026, V9_79)'),
('p33', 345, 1884, 'Port de Bonifacio (Bonifacio): NauSys base 482373 and MMK base 174 rows, 87 m apart (Mario 13.10.2026, V9_79)'),
('p34', 262, 655, 'Poseidon Marina (Milazzo): NauSys base 10268474 and MMK base 3739560640000100000 rows, 4 m apart (Mario 13.10.2026, V9_79)'),
('p35', 193, 1936, 'D-Marin Turgutreis (Bodrum): NauSys base 109045 and MMK base 118 rows, 35 m apart (Mario 13.10.2026, V9_79)'),
('p36', 411, 1550, 'Marina di Golfo Aranci (Golfo Aranci): NauSys base 10373986 and MMK base 4523886870000100000 rows, 27 m apart (Mario 13.10.2026, V9_79)'),
('p37', 206, 760, 'Port of Kotor (Kotor): NauSys base 482365 and MMK base 27289680000100000 rows, 101 m apart (Mario 13.10.2026, V9_79)'),
('p38', 170, 1566, 'Real Club Náutico de Dénia (Dénia): NauSys base 482394 and MMK base 32688140000100000 rows, 88 m apart (Mario 13.10.2026, V9_79)'),
('p39', 181, 1808, 'Port Olímpic de Barcelona (Barcelona): NauSys base 834511 and MMK base 256 rows, 5 m apart (Mario 13.10.2026, V9_79)'),
('p40', 432, 1360, 'Port of Pianottoli-Caldarello (Pianottoli-Caldarello): NauSys base 32262650 and MMK base 3860237560000100000 rows, 10 m apart (Mario 13.10.2026, V9_79)'),
('p41', 144, 1644, 'Kavala Marina (Kavala): NauSys base 6517793 and MMK base 6320614840000100000 rows, 147 m apart (Mario 13.10.2026, V9_79)'),
('mergellina', 209, 1118, 'Porto di Mergellina (Naples): NauSys base 119154 and MMK base 3558170880000100000 rows, 91 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 209, 1301, 'Porto di Mergellina (Naples): NauSys base 119154 and MMK base 4979527500000100000 rows, 52 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 209, 2007, 'Porto di Mergellina (Naples): NauSys base 119154 and MMK base 31 rows, 156 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 209, 2099, 'Porto di Mergellina (Naples): NauSys base 119154 and NauSys base 75354092 rows, 337 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 1118, 1301, 'Porto di Mergellina (Naples): MMK base 3558170880000100000 and MMK base 4979527500000100000 rows, 50 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 1118, 2007, 'Porto di Mergellina (Naples): MMK base 3558170880000100000 and MMK base 31 rows, 104 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 1118, 2099, 'Porto di Mergellina (Naples): MMK base 3558170880000100000 and NauSys base 75354092 rows, 273 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 1301, 2007, 'Porto di Mergellina (Naples): MMK base 4979527500000100000 and MMK base 31 rows, 143 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('mergellina', 1301, 2099, 'Porto di Mergellina (Naples): MMK base 4979527500000100000 and NauSys base 75354092 rows, 320 m apart; one place of 5 rows (209, 1118, 1301, 2007, 2099) (Mario 13.10.2026, V9_79)'),
('p43', 11, 1451, 'Trget Port (Pula): NauSys base 590616 and MMK base 532139690000100000 rows, 72 m apart (Mario 13.10.2026, V9_79)'),
('p44', 71, 1374, 'Luka Stobreč (Stobreč): NauSys base 1080804 and MMK base 4182110170000100000 rows, 114 m apart (Mario 13.10.2026, V9_79)'),
('p45', 95, 2119, 'Port Kaštel Stari (Kaštel Stari): NauSys base 35219758 and MMK base 8201197930000100000 rows, 45 m apart (Mario 13.10.2026, V9_79)'),
('p46', 445, 831, 'Gümbet Limanı (Bodrum): NauSys base 10422207 and MMK base 5896157740000100000 rows, 18 m apart (Mario 13.10.2026, V9_79)'),
('p48', 582, 1528, 'Sumpetar (Sumpetar): NauSys base 55524245 and MMK base 4460821360000100000 rows, 25 m apart (Mario 13.10.2026, V9_79)'),
('p49', 45, 1738, 'Port of Vodice (Vodice): NauSys base 580277 and MMK base 3973641420000100000 rows, 17 m apart (Mario 13.10.2026, V9_79)'),
('p51', 265, 1302, 'Robert''s Grove Marina (Placencia): NauSys base 10227225 and MMK base 5018840130000100000 rows, 45 m apart (Mario 13.10.2026, V9_79)'),
('p52', 419, 1045, 'Marina di Riposto (Riposto): NauSys base 8560010 and MMK base 322902220000100000 rows, 98 m apart (Mario 13.10.2026, V9_79)'),
('p53', 452, 1654, 'Port of Pachi (Pachi, Megara): NauSys base 33215132 and MMK base 4611181660000100000 rows, 44 m apart (Mario 13.10.2026, V9_79)'),
('p54', 2093, 2002, 'Vieux-Port de Cannes (Cannes): NauSys base 861137 and MMK base 36 rows, 85 m apart (Mario 13.10.2026, V9_79)'),
('baska_voda', 76, 1387, 'Marina Baška Voda (Baška Voda): NauSys base 477801 and MMK base 5633158080000100000 rows, 109 m apart; one place of 3 rows (76, 1387, 1571) (Mario 13.10.2026, V9_79)'),
('baska_voda', 1387, 1571, 'Marina Baška Voda (Baška Voda): MMK base 5633158080000100000 and MMK base 30782240000100000 rows, 217 m apart; one place of 3 rows (76, 1387, 1571) (Mario 13.10.2026, V9_79)'),
('p56', 2098, 2072, 'Jamb Marina (Mrcevac): NauSys base 75207402 and MMK base 7705537160000100000 rows, 5 m apart (Mario 13.10.2026, V9_79)'),
('p58', 317, 619, 'Oia Port (Oia): NauSys base 72462131 and MMK base 3406806320000100000 rows, 21 m apart (Mario 13.10.2026, V9_79)'),
('aci_split', 1755, 69, 'ACI Marina Split (Split): MMK base 32326420000100000 and MMK base 9 / NauSys base 56 rows, 989 m apart; the MMK base "Split" (1 boat) is ACI Marina Split (Mario 13.10.2026, V9_79)'),
('olbia', 222, 609, 'Marina di Olbia (Olbia): NauSys base 517915 and MMK base 210 rows, 1.7 km apart; the NauSys row had the town port coordinates (Mario 13.10.2026, V9_79)'),
('olympic', 139, 710, 'Olympic Marine (Lavrion): NauSys base 1002560 and MMK base 484655120000100000 rows, 1.9 km apart; the NauSys row had the town port coordinates (Mario 13.10.2026, V9_79)');

-- a row still carries the 13.10.2026 production data
CREATE TEMP VIEW v9_79_row_ok AS
SELECT r.*
FROM v9_79_row r
JOIN location l ON l.id = r.id
WHERE l.country_code = r.cc
  AND btrim(l.name) = ANY (r.names)
  AND EXISTS (SELECT 1
              FROM external_mapping em
              WHERE em.type = 'Location' AND em.system_id = r.id AND em.external_system_id = r.sys AND em.external_id = r.base)
  AND (r.city IS NULL OR btrim(l.city) = r.city)
  -- a NauSys row that takes a city must not be MMK-mapped: MMK would rewrite it on the next run
  AND (r.new_city IS NULL
       OR NOT EXISTS (SELECT 1
                      FROM external_mapping em
                      WHERE em.type = 'Location' AND em.system_id = r.id AND em.external_system_id = 1))
  AND (r.new_lat IS NULL
       OR (l.lat = r.lat_now AND l.lon = r.lon_now)
       OR (l.lat = r.new_lat AND l.lon = r.new_lon))
  AND (r.region_id IS NULL
       OR EXISTS (SELECT 1 FROM region g WHERE g.id = r.region_id AND g.country_code = r.cc));

-- a place is applied only when every one of its rows is
CREATE TEMP VIEW v9_79_ok AS
SELECT r.*
FROM v9_79_row r
WHERE NOT EXISTS (SELECT 1
                  FROM v9_79_row x
                  WHERE x.unit = r.unit
                    AND NOT EXISTS (SELECT 1 FROM v9_79_row_ok o WHERE o.id = x.id));

CREATE TABLE IF NOT EXISTS ops_location_near_dup_backup_20261013 (
    id           BIGINT PRIMARY KEY,
    name         VARCHAR(255),
    city         VARCHAR(100),
    display_name TEXT,
    lat          NUMERIC,
    lon          NUMERIC,
    region_ids   TEXT,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- every row of every place applied, only the first time
INSERT INTO ops_location_near_dup_backup_20261013 (id, name, city, display_name, lat, lon, region_ids)
SELECT l.id, l.name, l.city, l.display_name, l.lat, l.lon,
       (SELECT string_agg(lr.region_id::text, ',' ORDER BY lr.region_id) FROM location_region lr WHERE lr.location_id = l.id)
FROM location l
JOIN v9_79_ok o ON o.id = l.id
ON CONFLICT (id) DO NOTHING;

-- 1. one label: the final name; a NauSys row also takes its MMK twin's town
UPDATE location l
SET name = o.new_name,
    city = COALESCE(o.new_city, l.city)
FROM v9_79_ok o
WHERE l.id = o.id
  AND (l.name IS DISTINCT FROM o.new_name OR (o.new_city IS NOT NULL AND l.city IS DISTINCT FROM o.new_city));

-- 2. coordinates: the NauSys "Olympic Marine" and "Olbia" rows sat on the town ports; MMK "Split" sits on the ACI marina
UPDATE location l
SET lat = o.new_lat,
    lon = o.new_lon
FROM v9_79_ok o
WHERE l.id = o.id
  AND o.new_lat IS NOT NULL
  AND (l.lat IS DISTINCT FROM o.new_lat OR l.lon IS DISTINCT FROM o.new_lon);

-- 3. region (location_region has no unique key: insert only when missing)
INSERT INTO location_region (region_id, location_id)
SELECT o.region_id, o.id
FROM v9_79_ok o
WHERE o.region_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM location_region lr WHERE lr.location_id = o.id AND lr.region_id = o.region_id);

-- 4. one place: only once both rows carry their final names
INSERT INTO location_same_place (location_id, same_as_location_id, note)
SELECT p.a_id, p.b_id, p.note
FROM v9_79_pair p
JOIN v9_79_ok oa ON oa.id = p.a_id
JOIN v9_79_ok ob ON ob.id = p.b_id
JOIN location a ON a.id = p.a_id AND a.name = oa.new_name
JOIN location b ON b.id = p.b_id AND b.name = ob.new_name
WHERE NOT EXISTS (SELECT 1
                  FROM location_same_place sp
                  WHERE (sp.location_id = p.a_id AND sp.same_as_location_id = p.b_id)
                     OR (sp.location_id = p.b_id AND sp.same_as_location_id = p.a_id))
ON CONFLICT DO NOTHING;

DROP VIEW v9_79_ok;
DROP VIEW v9_79_row_ok;

-- Check after deploy (read only):
--   SELECT b.id, b.name AS before, l.name, l.city, l.lat, l.lon FROM ops_location_near_dup_backup_20261013 b
--     JOIN location l ON l.id = b.id ORDER BY b.id;                                    -> 113 rows, final names
--   SELECT count(*) FROM location_same_place WHERE note LIKE '%V9_79)';                 -> 64
--   SELECT * FROM location_region WHERE location_id = 1755;                             -> region 5
--   SELECT count(*) FROM yacht_listing_twin;          -> about 131 more than before (557 -> 688 on 13.10.), after the next refresh
--   GET https://api.boat4you.com/public/locations?name=mergellina -> ONE "Porto di Mergellina" row,
--     id "l-2007,l-2099,l-209,l-1118,l-1301" (after the jar restart; the list cache keeps 10 h)
--   https://www.boat4you.com/search?destinations=marina%20zadar%20(ex.%20tankerkomerc)&boatTypes=CATAMARAN -> 308 to
--     destinations=marina%20zadar (needs the web pin)
--
-- Undo (by hand):
--   UPDATE location l SET name = b.name, city = b.city, lat = b.lat, lon = b.lon
--   FROM ops_location_near_dup_backup_20261013 b WHERE b.id = l.id;
--   DELETE FROM location_region lr USING ops_location_near_dup_backup_20261013 b
--    WHERE lr.location_id = b.id AND b.id = 1755 AND lr.region_id = 5 AND b.region_ids IS NULL;
--   DELETE FROM location_same_place WHERE note LIKE '%(Mario 13.10.2026, V9_79)';
