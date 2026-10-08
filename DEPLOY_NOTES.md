# Backend deploy notes

## 2026-10-08 — Veze opreme: review popravci + JEDINI deploy recept za `973a2cb` + `a98f4d1` — ✅ DEPLOYANO 8.10.2026 (backend 17:35 UTC, web 17:43–18:01 UTC)

Deploya se ZAJEDNO s `973a2cb` (unos ispod = opis featurea; njegov redoslijed, SQL i rollback ZAMIJENJENI su ovim unosom). Jar iz commita ≥ `a98f4d1`, NIKAD samo `973a2cb`. Ugovor: `infra/equipment-mapping-audit-8-10/FIX_CONTRACT.md` §17 (dodatak reviewa); stare verzije podatkovnih datoteka su u `before_review_8_10/`.

**Review F1–F11 → `a98f4d1`:**

- **F1 ✅** `EquipmentMatcher`: token ključa od JEDNOG slova (`a/c`, `220 v`) broji se samo unutar uzastopnog niza tokena ključa ("A USB-C port in each cabin" više nije klima). `air-conditioning` i `navigation-set` + `not:c-map, not:cmap` ("a C-Map" je niz; C-Map karte su elektroničke).
- **F2 ✅** globalni veto: ime završava s `no` / `none` / `n/a` ili sadrži `optional`, `on/upon request`, `on/upon demand`, `not available/included/working/installed`, `out of order`, `unavailable`, `extra charge/cost`, `surcharge`, `for rent/hire`, `paid`, `eur/euro/usd/gbp` ili `€ $ £` → nema veze.
- **F3 ✅** javni detalji broda i my-bookings: `name` = NAŠ katalog (nikad partnerov tekst), MMK `-1` retci bez `comment`, retci koje partner označi kao odsutne (`false`/`no`/`0`/`none`/`n/a`, količina 0) se ne šalju (web je prisutnost čitao iz tog `comment`). Admin dobiva sve kao prije. Do web deploya `depth-sounder` na webu pada na engleski „Depth sounder", ne na partnerov tekst.
- **F4 ✅** ključevi: `snorkel-sets` `not:stabiliz`; `electric-fans` `not:belt, not:engine`; `main-anchor` `not:alarm, not:winsch`; `cooker` `not:heating, not:heater`; `BBQ` `not:ventilation`; `kitchen-utensils` `not:satellite`; `wifi` `not:streaming, not:music`; `sun-pads` `not:shower`; `navigation-set` bez golog `pilot` (+ `sea pilot`, `water pilot`, `cruising pilot`, `pilot guide`).
- **F7 ✅** seed `partner_equipment_mapping` (80 redaka, upsert) i tri spajanja (`merged_into_id`) sada su u **R__1_05** → svježa baza ih dobiva. Izjednačenje → manji `label_code` (ne id, id-jevi se razlikuju po okruženjima).
- **F8 ✅** V9_74 prvo sprema `_equipment_backup_20261008` (cijela tablica `equipment` prije promjene); rollback dolje vraća SVE retke i redoslijed je zapisan.
- **F9 ✅** test „jedan pisac" skenira svaku V-migraciju > V9_75 I svaku drugu R__ datoteku: `UPDATE / INSERT INTO / DELETE FROM / TRUNCATE / ALTER TABLE / COPY equipment|partner_equipment_mapping` i `merged_into_id`.
- **F10 ✅** zaglavlja V9_74 / V9_75 / R__1_05: samo Flyway ili `psql --single-transaction -f` (`SET LOCAL` + `ON COMMIT DROP` temp tablice; običan `psql -f` pada na drugoj naredbi).
- **Djelomično / odbijeno (razlozi):**
  - F1 prag „< 3 znaka" → samo 1 slovo: pravilo za 2 slova odvezuje „AC in every cabin" (16 aktivnih) i „AC in the saloon" (6); `wi fi`, `hi fi`, `cd …`, `ac …` na snimci nemaju lažnih pogodaka.
  - F2 „per day/hour" NIJE veto: „Watermaker 60 l per hour" je kapacitet, „Beach towels … per week" usluga; cijenu hvataju valuta i „extra charge/surcharge/paid".
  - F5 (sidro) → **Mario**: „Anchor + chain" (10) / „Anchor & chain" / „Anchor 100m chain" → `anchor-line`, „Anchor with chain" (1.241) → `main-anchor`. Oba koda su istinita, nijedan nije filter ni kartica; jedan ključ kad Mario odluči.
  - F6 prihvaćeno bez pinova (S4 = ishod matchera koji bi sync ionako napisao): „Dinghy with outboard engine" → dinghy (filter 104, ugovor §13); pumpa / garaža / lifting sustav dinghyja, „TV antenna", „Wind generator" → ništa (VERIFY 4.2 ih je označio kao krive); „Flybridge with bimini and Fridge" i „Flybridge camera" → flybridge (VERIFY: flybridge točan); „…eletric winch and Lazy Bag" → electric-winches (istina); „WindSUP" → ništa (ugovor §13); „Electronic sea charts", „Windex", „Teak table", „Kneeboard", „Electric Flatwinder" → ništa (stara veza kriva ili filter preširok). „Electric mainsail windlass" (53 retka, 6 danas povezano) i „Solar charger" (6, 1 povezan) → ništa: većina redaka je i danas bez veze, pin = Mariova odluka (jedan redak u R__1_05 seedu).
  - F7 BEZ nove R__ datoteke (reviewer predložio `R__1_06`, a ta postoji za view): stari JAR bi pri rollbacku pao na Flyway validaciji („applied migration not resolved locally", default ignore je samo `*:future`). Posljedica: R__1_05 sad PADA ako V9_74 nije primijenjen (cusma3 s novim JAR-om prije cusma2 se ne podiže; ništa se ne zapisuje).
  - F11 bez promjene: ponovljena MMK kataloška stavka zadržava prvi `comment` (bezopasno); mapiranje koje pokazuje na id kojeg cache kataloga još nema nastaje samo ako se R__1_05 promijeni bez restarta cusma3 (deploy uvijek restarta oba čvora; inače NULL do isteka 10 h).

**Simulacija (`sim/Sim.java`, commons-text, svih 2.778 imena snimke 8.10.):** mijenja se 6 veza, sve namjerno, 6 aktivnih jahti: Gennaker (optional), Wi-Fi streaming music equipment, Fin Stabilizing System, Anchor winsch, Hi-lo system … (TV not included), Railing net - on request → ništa. Reviewerova probna imena (`sim/probes_review_8_10.tsv`) su u `EquipmentMatcherTest`. V9_75 S3/S4 liste i fixture regenerirani; prod očekivanje S1 17.555 / S2 13.798 / S3 ≈ 6.115 / S4 ≈ 95.353 ≈ **132.821** redaka u backupu.

**Proba (BEGIN … ROLLBACK, pa provjereno da nije ostalo ništa: nema tablica, kolone ni indeksa; `equipment_id_seq` vraćen na max(id) jer `setval` nije transakcijski):**

- `b4y-rehearsal` (:15432, prod raspored, 0 MMK `-1`): S1 1.434 / S2 5.387 / S3 0 / S4 13.119 → 19.940; V9_75 ~0,74 s; 80 mapiranja; drugi prolaz V9_74 + V9_75 + R__1_05 = 0 promjena (veze, katalog, mapiranja); rollback recept → 0 veza i 0 redaka kataloga nevraćeno.
- `boat4you_postgres_new` (:5434, pomaknut raspored, 6.865 `-1`): S1 10.409 / S2 8.038 / S3 2.624 / S4 72.824 → 93.895; ~2,6 s; „Stereo" → `audio-system`; drugi prolaz = 0; rollback → 0 / 0.

**Verifikacija:** testovi opreme 44/44 zeleni (`EquipmentMatcherTest` 13, `EquipmentMatcherGoldenTest` 3, `EquipmentSeedConsistencyTest` 5, `EquipmentLinkFixMigrationTest` 4 — uključuje svježu bazu sa seedom i spajanjima, rollback recept i „R__1_05 prije V9_74 pada", `MmkYachtSyncEquipmentTests` 7, `NauSysYachtSyncEquipmentTests` 4, `YachtSearchAmenityFilterTest` 3, `YachtControllerAmenityAliasTests` 1, `YachtMapperAmenitiesTest` 4). Mutacije (F1 prag, F2 veto, tie po id-u) ruše testove. Puni suite **560**, isti **31 pre-existing failure** (`ReservationPaymentPhasesServiceTest` 26, `ReservationOptionsCombinationProviderTests` 2, `Boat4youWsApplicationTests` 1, `MatchersTests` 1, `NauSysDateTimeWrapperTests` 1). ktlint 0 na promijenjenim linijama. `bootJar` iz čistog `a98f4d1`: 71 × V9 bez duplikata, V9_74 / V9_75 / R__1_05 u jaru == commit (sha256 ovog builda `b63383903f391f23c9fb23b3fcdaf3a95e10f712c7806c6b1b02128ccffb2e9f`; svaki build ima svoj).

**Deploy redoslijed (stari sync NIKAD ne vidi resetirane retke; oba čvora gotova prije sljedećeg sync slota):**

- [ ] `git pull` + `ls src/main/resources/db/migration | sort -V | tail -3`: na origin/main zadnja je **V9_73**, V9_74 i V9_75 su ovi; B8 (`docs/pending-migrations`) uzima **≥ V9_76**.
- [ ] **Prije** (cusma4, read-only, spremiti izlaz): SQL „prije" dolje + `max(id)` iz `yacht_equipment`.
- [ ] **Prozor:** 13:05–14:30 UTC. Izbjegavati MMK availability (08:40/12:40/16:40/20:40, ~17 min), near-term (10:50/16:50), MMK 06:00–07:30, NauSys availability (10:20/16:20/22:20), NauSys 23:00–06:00, drain (06:15/10:15/15:15), successor 07:55, charter facts 08:00. Cijeli posao ~5 min; cusma3 MORA opet raditi prije 15:15.
- [ ] **cusma3 STOP** (ne restart) uz TVRDI gate: `n=$(journalctl --since '10 minutes ago' | grep -ci 'nausys\|mmk'); [ "$n" -gt 0 ] && { echo ABORT; exit 1; }; systemctl stop boat4youscheduler`.
- [ ] **cusma2:** novi JAR + restart → Flyway u JEDNOM runu: V9_74 (backup kataloga, kolona, tablica) → V9_75 (S0–S4, sekunde) → R__1_05 (ključevi, spajanja, 80 mapiranja). V9_75 NIKAD ručno s `psql -f` (samo Flyway ili `psql --single-transaction -f`). Provjera: `SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;` (9.74, 9.75, R 1.05, sve `t`), health `GET /public/settings/card-surcharge` → 200, SQL „nakon" dolje.
- [ ] **cusma3:** novi JAR (sha == cusma2), `start` (FLYWAY_TARGET 1.43: V__ ne, R__1_05 već primijenjen s istim checksumom). NIKAD novi JAR na cusma3 prije cusma2: R__1_05 bez V9_74 pada i cusma3 se ne podigne (popravak: cusma2 pa opet start).
- [ ] **Cache:** restart oba čvora = svježi `equipmentCache`, `partnerEquipmentMappingCache` (inače 10 h po čvoru) i `yachtSearchListCache`.
- [ ] **API:** `curl -s https://api.boat4you.com/public/yachts/13960 | jq -c '[.amenities[] | {name, code: .equipment.labelCode, comment}]'` → samo povezani retci, `name` = naš katalog, „Stereo" sada `audio-system`, nijedan `-1` redak nema `comment`.
- [ ] Prvi MMK sync (06:10) i NauSys (23:20) razdvajaju `-1` stavke i preračunaju veze; opcionalni ručni `POST /admin/mmk/yachts` = Mariova odluka (opterećuje partnera).
- [ ] **Web** (b4y + 6 sistera) ISTI DAN nakon backenda (web ne ide prvi); ISR stranica brodova na D+1 (nakon oba synca) ili TTL.

**SQL (cusma4, read-only):**

```sql
-- PRIJE deploya i NAKON migracije (usporediti): aktivne jahte po kodu
SELECT e.label_code, count(DISTINCT ye.yacht_id) AS active_yachts
FROM yacht_equipment ye JOIN equipment e ON e.id = ye.equipment_id JOIN yacht y ON y.id = ye.yacht_id AND y.sys_active
WHERE e.label_code IN ('wifi','fridge','refrigerator','outside-GPS-plotter','salon-GPS-plotter','audio-system','outside-speakers',
                       'bow-thruster','bow-thruster-deck','air-conditioning','generator','main-anchor','depth-sounder','cockpit-cushions')
GROUP BY 1 ORDER BY 1;
-- očekivano (simulacija snimke 8.10.) prije → nakon: wifi 12 → 2.221 · fridge 1.560 (+ refrigerator 9.355) → 9.523 ·
-- outside-GPS-plotter 4 → 4.131 · salon-GPS-plotter 17 → 6.908 · audio-system 17 → 8.090 · outside-speakers 28 → 6.016 ·
-- bow-thruster 4.596 (+ deck 1.115) → 5.688 · main-anchor 0 → 2.330 · depth-sounder 0 → 2.209 · cockpit-cushions 0 → 6.147 ·
-- air-conditioning 5.762 → 5.748, generator 4.757 → 4.691 (pad = posuđene -1 veze; vraćaju se nakon razdvajanja na D+1)
SELECT max(id) AS max_ye_id_at_deploy FROM yacht_equipment;                       -- PRIJE: zapisati za D+1
SELECT count(*) AS free_text_rows, count(DISTINCT yacht_id) AS yachts FROM yacht_equipment WHERE external_id = -1;  -- prije 10.696 / 10.696

-- „Stereo" (yacht 13960, redak 335543): prije 'Stereo' → usb-sockets; nakon V9_75 → audio-system;
-- nakon prvog MMK synca više -1 redaka, svaki s vezom po VLASTITOM imenu
SELECT ye.id, ye.name, e.label_code, ye.comment FROM yacht_equipment ye LEFT JOIN equipment e ON e.id = ye.equipment_id
 WHERE ye.yacht_id = 13960 AND ye.external_id = -1 ORDER BY ye.id;

-- NAKON migracije
SELECT label_code, id, merged_into_id, filter_order FROM equipment
 WHERE label_code IN ('fridge','refrigerator','bow-thruster','bow-thruster-deck','sun-pads','sundeck-cushions','cockpit-cushions','depth-sounder');
-- cockpit-cushions 107, depth-sounder 108; refrigerator → 14, bow-thruster-deck → 23, sundeck-cushions → 52
SELECT count(*) FROM yacht_equipment WHERE equipment_id IN (SELECT id FROM equipment WHERE merged_into_id IS NOT NULL);  -- 0
SELECT section, count(*) FROM _equipment_link_fix_backup_20261008 GROUP BY 1 ORDER BY 1;  -- ≈ S1 17.555 / S2 13.798 / S3 6.115 / S4 95.353
SELECT count(*) FROM _equipment_backup_20261008;                                          -- 106 (katalog prije)
SELECT count(*), count(equipment_id) FROM partner_equipment_mapping;                      -- 80 / 80

-- D+1 (nakon MMK synca 06:10): -1 stavke razdvojene i povezane po vlastitom imenu
SELECT count(*) AS free_text_rows, count(DISTINCT yacht_id) AS yachts, count(equipment_id) AS linked,
       count(*) FILTER (WHERE id > :max_ye_id_at_deploy) AS new_rows
  FROM yacht_equipment WHERE external_id = -1;                                            -- rows >> yachts
SELECT count(*) AS yachts_split FROM (SELECT yacht_id FROM yacht_equipment WHERE external_id = -1 GROUP BY 1 HAVING count(*) > 1) x;  -- > 0
SELECT e.label_code, ye.name, count(*) AS rows FROM yacht_equipment ye LEFT JOIN equipment e ON e.id = ye.equipment_id
 WHERE ye.external_id = -1 AND ye.id > :max_ye_id_at_deploy GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 200;
-- pregled: kriva veza → NONE redak u seedu R__1_05 (commit + deploy); nikad ime agencije/operatera javno (javni name = naš katalog)
```

**Rollback (redoslijed je bitan):**

1. cusma3: isti TVRDI gate + `systemctl stop boat4youscheduler`.
2. cusma2: `.prev` JAR + restart → Flyway: V9_74 / V9_75 = „future" (ignorirane), stari R__1_05 (drugi checksum) se ponovno izvrši (id 1–58).
3. cusma4, JEDNA transakcija (isto kao `EquipmentLinkFixMigrationTest.ROLLBACK_SQL`):

```sql
BEGIN;
UPDATE yacht_equipment ye SET equipment_id = b.equipment_id_before
  FROM _equipment_link_fix_backup_20261008 b WHERE b.ye_id = ye.id AND ye.equipment_id IS DISTINCT FROM b.equipment_id_before;
UPDATE equipment e SET name = b.name, category = b.category, match_keys = b.match_keys, filter_order = b.filter_order
  FROM _equipment_backup_20261008 b WHERE b.id = e.id;
UPDATE equipment SET merged_into_id = NULL WHERE merged_into_id IS NOT NULL;
DROP INDEX IF EXISTS equipment_label_code_uq;
COMMIT;
```

4. cusma3: `.prev` JAR + `start`.
5. git: `git revert a98f4d1 973a2cb`, ALI zadržati deployane migracije: `git checkout a98f4d1 -- src/main/resources/db/migration/V9_74__equipment_alias_and_partner_mapping.sql src/main/resources/db/migration/V9_75__fix_equipment_links.sql` (checksumi u `flyway_schema_history`; bez njih buduća V9_76 ruši validaciju). R__1_05 se vraća na staru verziju (repeatable, novi checksum = OK).

- Nova kolona, tablice i redovi 107/108 ostaju (stari kod ih ne čita; stari matcher podnosi njihove `token-match:` ključeve).
- Rollback PRIJE prvog synca vraća sve. Nakon synca: veze koje je novi sync napisao nisu u backupu; stari sync ih zadržava (piše samo u NULL) i razdvojene `-1` retke opet stapa (višak briše).

**Otvoreno za Marija:** F5 sidro (anchor-line ili main-anchor za „Anchor + chain"); pin „Electric mainsail windlass" → electric-winches (filter 105) i „Solar charger" → solar-panels; ostalo iz unosa ispod (generički plotter, Coffee pot, gennaker oprema, 220 V oznaka, nepovezane stavke, čisti reset S3, endpoint za cache). Zatečeno: MMK kataloški retci s vrijednošću `false`/`0`/`no` (78 redaka na aktivnim jahtama, snimka 8.10.) i dalje su povezani pa se broje u filterima i na karticama (javni detalji ih ne prikazuju).

## 2026-10-08 — Veze opreme partnera → naš katalog (audit 8.10., odluke a–d) — ✅ DEPLOYANO 8.10.2026 (backend 17:35 UTC, web 17:43–18:01 UTC) (commit `973a2cb`) · redoslijed, SQL i rollback ZAMIJENJENI unosom iznad (review `a98f4d1`)

Ugovor: `infra/equipment-mapping-audit-8-10/FIX_CONTRACT.md` (+ `VERIFY.md`). Backend ide PRVI, web (b4y + 6 sistera) isti dan POSLIJE backenda (web filter „samo povezano" prije backenda bi sakrio danas nepovezano: WiFi, plotteri…).

**Što mijenja:**

- **Sync (MMK + NauSys):** veza se ponovno računa SVAKI prolaz i piše kad se razlikuje (i u NULL). MMK `-1` stavke (slobodan tekst agencije) više se ne stapaju u JEDAN redak po jahti (prije: ime prve stavke + veza druge = „Stereo → USB sockets"): svaka dobiva svoj redak po normaliziranom imenu, napomena samo na vlastiti redak; kataloška stavka usvaja samo stari redak bez `external_id`; ista stavka 2× u payloadu = prvo pojavljivanje.
- **`EquipmentLinkResolver`:** `partner_equipment_mapping` (NULL = namjerno bez veze) → novi `EquipmentMatcher` (najbolji pogodak, deterministički po id-u; množina/tipfeler, NIKAD prefiks složenice: lifebuoy≠life, watermaker≠water, anchorage≠anchor) → alias se slijedi do kanonskog. `Matchers.extrasNameMatch` (extras) nepromijenjen.
- **`R__1_05` v2 = JEDINI pisac** `name/category/filter_order/match_keys` za svih 108 redaka (ključ `label_code` + `equipment_label_code_uq`); novi kodovi `cockpit-cushions` (COMFORT) i `depth-sounder` (NAVIGATION), oba bez filtera. `EquipmentSeedConsistencyTest` ruši build ako ijedna V-migracija > V9_75 spomene `match_keys`.
- **V9_74** (shema, brza, `lock_timeout` 5 s): `equipment.merged_into_id`, tablica `partner_equipment_mapping` + seed 80 redaka (po `label_code`; svježa baza bez kataloga → seed preskočen).
- **V9_75** (podaci, JEDNA transakcija, `lock_timeout` 10 s, `statement_timeout` 10 min): S0 novi redovi (prod: `cockpit-cushions` = 107, `depth-sounder` = 108), S1 spajanja (`bow-thruster-deck`→`bow-thruster`, `refrigerator`→`fridge`, `sundeck-cushions`→`sun-pads`), S2 A′ (67 stavki, uvjet = trenutni krivi kod), S3 MMK `-1` po VLASTITOM točnom imenu (ime nije na listi → NULL), S4 katalog po (sustav, stavka, točno ime). Sve po `label_code`, nijedan brojčani id. Backup `_equipment_link_fix_backup_20261008` (svaki promijenjeni redak jednom, izvorna veza, sekcija). Idempotentno.
- **Javno:** detalji broda i my-bookings šalju SAMO povezane retke (admin sve; admin rezervacija više ne stapa nepovezane u jedan). Filter opreme broji DISTINCT kod (prije 2 retka istog koda = jahta ispadala, 2.039 parova na produ). Alias id u `amenities=` / custom jahti → kanonski (i cache ključ). `all-amenities` i admin katalog bez alias redaka.
- **Cache:** `equipmentCache` (sad `findAllByOrderByIdAsc`) + novi `partnerEquipmentMappingCache`, oba 10 h po čvoru; restart ih briše.
- **Verzije:** V9_74 + V9_75 zauzete; `docs/pending-migrations/B8_*` uzima **≥ V9_76**.

**Proba na lokalnim bazama (BEGIN … ROLLBACK, ništa nije ostalo — provjereno):**

- `b4y-rehearsal` (:15432, raspored kao prod, 119.309 redaka, 0 MMK `-1`): S1 1.434 / S2 5.387 / S3 0 / S4 13.119 → backup 19.940; ~0,7 s; `cockpit-cushions` 107, `depth-sounder` 108; drugi prolaz V9_75 + R__1_05 = 0 promjena.
- `boat4you_postgres_new` (:5434, pomaknut raspored, V1_89 → u istoj transakciji prvo V1_90 dio za `equipment.category`): 357.674 redaka (6.865 `-1`): S1 10.409 / S2 8.038 / S3 2.630 / S4 72.826 → backup 93.897; ~2,4 s; `cockpit-cushions` ostaje 59, `depth-sounder` 108; drugi prolaz = 0.
- Prod očekivanje (snimka 8.10., FIX_CONTRACT §9): S1 17.555 / S2 13.798 / S3 6.171 / S4 95.355 ≈ 132.822 retka u backupu.

**Verifikacija:**

- Novi testovi (svi zeleni): `EquipmentMatcherTest` 10, `EquipmentMatcherGoldenTest` 3 (svih 2.778 imena snimke: matcher == `sim/Sim.java`, resolver == konačna veza iz `resolver_targets_8_10.csv`), `EquipmentSeedConsistencyTest` 4, `EquipmentLinkFixMigrationTest` 3 (pravi Flyway 11 na PG18: prod raspored, :5434 raspored, svježa baza; drugi prolaz = isto; rollback recept vraća sve), `MmkYachtSyncEquipmentTests` 7, `NauSysYachtSyncEquipmentTests` 4, `YachtSearchAmenityFilterTest` 3 (pravi R__1_03 matview + JPA upiti), `YachtControllerAmenityAliasTests` 1, `YachtMapperAmenitiesTest` 2.
- **Puni suite:** 552 testa, **isti 31 pre-existing failure** kao prije (`ReservationPaymentPhasesServiceTest` 26, `ReservationOptionsCombinationProviderTests` 2, `Boat4youWsApplicationTests` 1, `MatchersTests` 1, `NauSysDateTimeWrapperTests` 1), nijedan u dirnutom kodu.
- ktlint: 0 na promijenjenim linijama. Fixture i migracije bez imena operatera (`operatorNames.ts`, 713 imena).
- **Jar** (`bootJar` iz čistog `973a2cb`): bez dupliciranih verzija (71 × V9), V9_74/V9_75/R__1_05 u jaru == commit; sha256 `4dd5e1669db461ed29f05f72cd0c43e4423a716ec24214dc03244743b5c23071`.

**Deploy redoslijed (stari sync NIKAD ne vidi resetirane retke):**

- [ ] `git pull` + `ls src/main/resources/db/migration | sort -V | tail -3`: V9_74 i V9_75 su ovi; ništa novije s istim brojem.
- [ ] Prozor: izbjegavati MMK availability (08:40/12:40/16:40/20:40), near-term (10:50/16:50), MMK 06:00–07:30, NauSys availability (10:20/16:20/22:20), NauSys 23:00–06:00, drain (06:15/10:15/15:15). Prijedlog 13:05–14:30 UTC.
- [ ] **cusma3 STOP** (ne restart) uz TVRDI gate: `n=$(journalctl --since '10 minutes ago' | grep -ci 'nausys\|mmk'); [ "$n" -gt 0 ] && { echo ABORT; exit 1; }; systemctl stop boat4youscheduler`.
- [ ] **cusma2:** novi JAR + restart → Flyway V9_74, V9_75, R__1_05 v2 (~30 s). Provjera `flyway_schema_history` + health `GET /public/settings/card-surcharge` → 200 + SQL ispod.
- [ ] **cusma3:** novi JAR (provjeriti sha), `start` (FLYWAY_TARGET 1.43 → V__ ne, R__ već primijenjen).
- [ ] Prvi MMK sync (06:10) i NauSys (23:20) razdvajaju `-1` stavke i preračunaju veze. Opcionalno odmah `POST /admin/mmk/yachts` u mirnom prozoru = Mariova odluka (opterećuje partnera).
- [ ] Web (b4y + 6 sistera) ISTI DAN nakon backenda; ISR stranica brodova na D+1 (nakon oba synca) ili TTL.

**SQL nakon migracije (cusma4, read-only):**

```sql
SELECT label_code, id, merged_into_id, filter_order FROM equipment
 WHERE label_code IN ('fridge','refrigerator','bow-thruster','bow-thruster-deck','sun-pads','sundeck-cushions','cockpit-cushions','depth-sounder');
SELECT count(*) FROM yacht_equipment WHERE equipment_id IN (SELECT id FROM equipment WHERE merged_into_id IS NOT NULL);  -- 0
SELECT section, count(*) FROM _equipment_link_fix_backup_20261008 GROUP BY 1;   -- ≈ S1–S4 gore
SELECT count(*) FROM partner_equipment_mapping;                                  -- 80
SELECT e.label_code, count(DISTINCT ye.yacht_id) FROM yacht_equipment ye JOIN equipment e ON e.id = ye.equipment_id
  JOIN yacht y ON y.id = ye.yacht_id AND y.sys_active
 WHERE e.label_code IN ('wifi','fridge','outside-GPS-plotter','audio-system','outside-speakers','depth-sounder') GROUP BY 1;
-- očekivano ≈ wifi 2.222, fridge 9.523, outside-GPS-plotter 4.131, audio 8.090, outside-speakers 6.016, depth-sounder 2.209
```

**D+1 (nakon MMK synca 06:10):** novi MMK `-1` retci (`id > max_id_pri_deployu`) grupirani po `(label_code, name)` i broju jahti; krive veze → NONE redci u `partner_equipment_mapping` kroz Flyway (V9_76+, po `label_code`).

**Rollback:** stop cusma3 → `.prev` JAR na cusma2 pa cusma3; podaci: `UPDATE yacht_equipment ye SET equipment_id = b.equipment_id_before FROM _equipment_link_fix_backup_20261008 b WHERE b.ye_id = ye.id;` (test potvrdio); nova kolona i tablica bezopasne za stari kod; stari R__1_05 vraća ključeve za 1–58 (redovi 59–108 zadržavaju v2 ključeve, stari matcher ih podnosi).

**Otvoreno za Marija (FIX_CONTRACT §15):** generički „Chart plotter" → `salon-GPS-plotter` (a ne `outside`, filter 101); „Coffee pot" → coffee-machine ostaje; gennaker/spinnaker oprema na kodu jedra; `shore-connection-220v` kao „220 V utičnice"; nepovezani ostaju Swimming ladder, Windex, Bilge pump, Set of tools, Emergency tiller, Coolbox, Sun awning; čisti reset S3 umjesto relinka; endpoint za pražnjenje cachea opreme.

## 2026-10-07 — Nasljednik povučenog broda + review popravci (flotne oznake, broj modela, kraj lanca, registracije, twin petlja) — ✅ DEPLOYANO 8.10.2026 (cusma2 07:50:46, cusma3 08:06:27 UTC; fill 2.757 / 63 bez) (commiti `566775a` + `9c58d29`)

Deploya se ZAJEDNO s `566775a` (unos ispod = opis featurea; njegova checklista i prod brojke vrijede za STARO pravilo). Ugovor s webom (b4y + 6 sistera) NEPROMIJENJEN: `GET /public/yachts/{idOrSlug}` za povučeni brod → **400 `{"code":1502,"message":"Yacht is not active"}`** + `"successorSlug"` / `"successorId"` kad je točno jedan nasljednik, inače polja NEMA. Nikakvi podaci o agenciji/partneru.

**Commiti:**

- `566775a` feature (V9_73 `yacht_successor`, `YachtSuccessorComputeService` + `YachtSuccessorJob` na cusma3, lookup samo na 1502 putu).
- `9c58d29` review popravci:
  1. **Kanal (HIGH):** ista agencija + isti partnerski sustav (`external_mapping`, kao V9_69) = flotni brodovi, NIKAD nasljednik, osim kad se registracije slažu (≥ 4 znamenke, MMK `certificate`). Moorings „Moorings 4500 Club" Cannigione → Portorosa više ne prolazi.
  2. **Flotna oznaka:** ime koje jedna agencija na jednom sustavu daje ≥ 2 ŽIVA broda istog modela i godine (u državi) nije ime broda („Sunsail 410 Classic"). Ime koje agencija samo ponovno koristi za drugi model/godinu („Luna") ostaje.
  3. **Rezervni model:** uz prvu riječ i duljinu ±0,3 m i **isti prvi broj u imenu modela** (Lagoon 46 ≠ Lagoon 43; „390 GL" / „390 Grand Large" ostaje).
  4. **Lanac:** kraj lanca mora zadovoljiti izravno pravilo prema STAROM brodu (±0,3 m između krajeva, kanal, registracija); premošćuje se samo nepoznata duljina. Test „Pathfinder" ispravljen (411 → više ništa, 414 → 415).
  5. **Registracije** ≥ 4 znamenke koje se razlikuju = dva broda („Aria" 12480 / 12495).
  6. **Prikazana kopija** (`yacht_listing_twin`) koju pravilo ne bi uparilo sa starim brodom (njegov kanal / druga registracija) → nasljednik sumnjiv → NEMA ga.
  7. **Guard:** run koji imenuje < 50 % spremljenih redova (i 0) se ne sprema; job svaki dan javlja ERROR `Yacht successors are STALE` kad su redovi stariji od 48 h. Pravo veliko smanjenje: `DELETE FROM yacht_successor;` → sljedeći run (ili restart cusma3) puni.
  8. **Twin petlja:** `YachtTwinRepository.pickCanonical*` (pilot 6047/7576/9431, ručni 481/13163) biraju samo POSLUŽIVU kopiju (`sys_active`, agencija aktivna, nije `availability_blocked`). Prije: povučena kopija s FREE tjednima mogla je pobijediti → živa stranica postaje 1502 s nasljednikom = ona sama → beskonačni redirect. Uz to detalj nikad ne vraća nasljednika jednakog traženom id-u.
  9. V9_73 (nigdje primijenjen; checksum = verzija iz `9c58d29`): zaglavlje → `YachtSuccessorComputeService`, maknut nekorišteni indeks na `new_id`. KDoc joba: refresh u 07:50 (180–423 s) se može preklopiti s 07:55 - bezopasno (CONCURRENTLY ne blokira čitanje).
- **Odbijeno:**
  - Dokumentirati polja u `boat4you_ws_common.openapi.yaml` (`ErrorSchema`): yaml je ulaz generatora CRUD modela (`org.openapitools.model.ErrorSchema` za SVE greške), `/public/yachts` je code-first i nijedan endpoint ne dokumentira tijelo greške. Ugovor: KDoc `YachtNotActiveErrorBody` + ovaj unos.
  - `/boat/lagoon-42` čita se kao brod 42: ugovor traži da i KRIVI slug s važećim id-jem vrati nasljednika; web generira samo slugove s id-jem; čitanje id-a iz zadnjeg segmenta je pre-existing.
  - Napomena za Marija: brojke u `566775a` mjerene su read-only na produ (cusma4), izvan dogovorenih lokalnih baza (rehearsal nema nijedan povučen brod). Ovaj popravak mjeren SAMO na `b4y-rehearsal`.

**Brojke (`b4y-rehearsal`, kopija proda, 14.120 brodova, NIJEDAN povučen → simulacija: svaki živi partnerski brod uzet kao povučen, bez lanaca; rehearsal je na V9_05, pa su V9_69/V9_70 + 3 kolone stvorene u transakciji i ROLLBACK - provjereno, ništa nije ostalo):**

- Staro pravilo **1.931** nasljednika → novo **1.757** (−9 %).
- Otpalo 175: **170 ista agencija + sustav** (flotne oznake Moorings / Sunsail / „Premium", Pogo/RM kod iste agencije na dvije baze), **3 različite registracije** (Aria ×2, Aliki), **1 flotna oznaka** (dva „Nanda" Futura 40 iste agencije), **1 Lagoon 46 → 43** (AELIA). Dobiven 1: 8302 → 5017 (Aelia Lagoon 43 Volos; prije dvosmisleno zbog Lagoon 46 u Alimosu).
- Nakon popravka: **0** parova istog kanala, **0** s različitom registracijom. Simulacija = `PICK_SQL` s `WHERE true` umjesto `WHERE NOT o.sys_active` i bez skokova (`w.depth < 1`), parovi s ciljem = sam brod izbačeni.
- Prod (2.768 u unosu ispod) vrijedi za staro pravilo; nova brojka dolazi iz loga prvog runa na cusma3. Očekivano nešto ispod 2.768 (review: istokanalnih nasljednika na produ danas ≤ 9). **< 2.000 = stati i istražiti prije javljanja webu.**

**Verifikacija (7.10.):**

- Testovi successora/twina: `YachtSuccessorComputeServiceTest` 17 (pravi Postgres 17, pravi V9_69/V9_70/V9_73, V9_73 2× = idempotentno), `YachtTwinCanonicalPickTest` 2 (pravi SQL repozitorija), `YachtControllerSuccessorTests` 4, `YachtNotActiveSuccessorTest` 4, `YachtSuccessorJobTests` 2 - svi zeleni.
- **Mutacije:** staro pravilo + stari guard ruši 7 testova (kanal, flota, broj modela, lanac, registracija, sumnjiva prikazana kopija, guard < 50 %); twin SQL bez filtera ruši pick test; kontroler bez provjere self-id ruši test petlje.
- **Puni suite:** 516 testova, **isti 31 pre-existing failure** kao u unosu od 6.10. (`ReservationPaymentPhasesServiceTest` 26, `ReservationOptionsCombinationProviderTests` 2, `Boat4youWsApplicationTests` 1, `MatchersTests` 1, `NauSysDateTimeWrapperTests` 1), nijedan u dirnutom kodu.
- ktlint: 0 na promijenjenim linijama (`566775a^..9c58d29`).

**Deploy redoslijed:**

- [ ] `git pull` + `ls src/main/resources/db/migration | sort -V | tail -3`: **V9_73 mora biti slobodan** (B8 iz `docs/pending-migrations` uzima SLJEDEĆI broj). Jar iz commita ≥ `9c58d29` (NIKAD samo `566775a`).
- [ ] **cusma2** u sigurnom prozoru (ne dok cusma3 piše `yacht`; vidi sync slotove): Flyway V9_73 = nova prazna tablica, `lock_timeout` 5 s, bez locka na postojećim tablicama. Health: `GET /public/settings/card-surcharge` → 200. Odmah: `for id in 6047 7576 9431 481 13163; do curl -s -o /dev/null -w "$id %{http_code}\n" https://api.boat4you.com/public/yachts/$id; done` → živi pilot/ručni brodovi 200 (twin pick sad samo posluživu kopiju).
- [ ] **cusma3** uz TVRDI sync gate u istoj skripti: `n=$(journalctl --since '10 minutes ago' | grep -ci 'nausys\|mmk'); [ "$n" -gt 0 ] && { echo ABORT; exit 1; }`, tek onda swap + restart.
- [ ] **Fill:** u logu cusma3 `Yacht successors: N retired boats named a live listing (M through a chain), K left without ...` bez `NOT stored`; SQL (cusma4, read-only): `SELECT count(*), max(computed_at) FROM yacht_successor;` i `SELECT new_id FROM yacht_successor WHERE old_id = 4066;` = **11681**.
- [ ] Recept dolje prolazi → **tek tada javiti web sesiji da je polje live**, uz pravilo: **web nikad ne preusmjerava na isti id** (`successorId` == id iz vlastitog URL-a → bez redirecta, običan 404).

**Recept (prod):**

- Povučeni s nasljednikom: `curl -s -w '\n%{http_code}\n' https://api.boat4you.com/public/yachts/lagoon-bnteau-lagoon-42-4-2-cab-masterpiece-4066` → **400** + `"code":1502` + `"successorSlug":"lagoon-42-masterpiece-11681","successorId":11681`; isto za `/public/yachts/4066` i krivi slug `/public/yachts/x-4066`.
- Aktivni brod nepromijenjen: `curl -s -o /dev/null -w '%{http_code}\n' https://api.boat4you.com/public/yachts/lagoon-42-masterpiece-11681` → **200**.
- Dvosmislen bez polja: id iz upita ispod (povučen, ≥ 2 živa imenjaka istog modela/godine/države, bez reda u tablici) → `curl -s https://api.boat4you.com/public/yachts/<id> | jq -c keys` → **`["code","message"]`**.

```sql
SELECT o.id, count(DISTINCT c.id) AS live_namesakes
FROM yacht o
JOIN location lo ON lo.id = o.location_id
JOIN yacht c     ON c.sys_active AND c.id <> o.id AND c.build_year = o.build_year AND c.model_id = o.model_id
                AND regexp_replace(lower(btrim(c.name)), '[^[:alnum:]]', '', 'g')
                  = regexp_replace(lower(btrim(o.name)), '[^[:alnum:]]', '', 'g')
JOIN location lc ON lc.id = c.location_id AND lc.country_code = lo.country_code
WHERE NOT o.sys_active AND o.entry_type = 'EXTERNAL'
  AND NOT EXISTS (SELECT 1 FROM yacht_successor s WHERE s.old_id = o.id)
GROUP BY o.id HAVING count(DISTINCT c.id) >= 2
ORDER BY o.id LIMIT 5;
```

**Rollback:** `git revert 9c58d29 566775a` + redeploy (cusma2 → cusma3), ALI zadržati deployanu migraciju: `git checkout 9c58d29 -- src/main/resources/db/migration/V9_73__yacht_successor.sql` (checksum u `flyway_schema_history` je te verzije). Tablica `yacht_successor` smije ostati (nitko je ne čita). Samo pravilo: `git revert 9c58d29` vraća staro (labavije) pravilo i twin pick bez filtera - ne preporučuje se. Web bez polja pada na stari 404 → nema štete.

## 2026-10-07 — Nasljednik povučenog broda: 1502 nosi `successorSlug` / `successorId` (V9_73 `yacht_successor`, job na cusma3) — ✅ DEPLOYANO 8.10.2026 (cusma2 07:50:46, cusma3 08:06:27 UTC; fill 2.757 / 63 bez) · pravilo, brojke i checklista ZAMIJENJENI unosom iznad (review `9c58d29`)

Mario 7.10.: stari URL povučenog partnerskog broda (`sys_active = false`) daje 404, a ISTI brod živi pod drugim id-jem (Bing: `/boat/lagoon-bnteau-lagoon-42-4-2-cab-masterpiece-4066` → 404, brod je živ kao 11681). Ugovor s webom (b4y + 6 sistera, radi druga sesija): `GET /public/yachts/{idOrSlug}` za povučeni brod i dalje vraća **400 `{"code":1502,"message":"Yacht is not active"}`** i DODAJE `"successorSlug"` + `"successorId"` kad postoji točno jedan nasljednik; inače polja nema (ni `null`). Nikakvi podaci o agenciji/partneru.

- **Pravilo** (`YachtSuccessorComputeService.PICK_SQL`, jedan set-based SQL): isto normalizirano ime (kao V9_69; ime = samo model ili placeholder se ne uparuje) + ista godina + ista država baze + isti `model_id` (ili, kad partneri drže model pod drugim id-jem, ista prva riječ modela i duljina ±0,3 m). Nasljednik mora biti ono za što detalj vraća 200 (aktivan, agencija aktivna i nije `availability_blocked`, baza nije `inland`). Lanci kroz druge povučene kopije do 4 skoka (UNION + limit = zaštita od ciklusa). Dvije kopije istog broda (MMK + NauSys, `yacht_listing_twin`) → kopija koju liste prikazuju (ista godina/država). Više kandidata → onaj na staroj bazi (isti `location_id` ili isti base key), inače NIŠTA.
- **Prod 7.10. (read-only, točno SQL iz joba, ~0,2 s):** 7.061 povučenih EXTERNAL → **2.768 dobiva nasljednika** (2.439 jedan kandidat, 210 twin → prikazana kopija, 110 ista baza, 9 kroz lanac), 65 ostaje bez (više različitih kandidata), 2.452 različita cilja. 4066 → 11681 ✔.
- **Tablica, ne matview:** `yacht_successor(old_id PK, new_id, computed_at)` + indeks na `new_id`, bez FK (lock na `yacht`). Job (`YachtSuccessorJob`, samo `data-sync` = cusma3) svaki dan **07:55 UTC** (nakon NauSys noćnog bloka do ~06:00 i MMK 06:00/06:10, prije charter facts 08:00) + jednom pri startu ako je tablica prazna. Zamjena u JEDNOJ transakciji (temp tablica → DELETE + INSERT; čitači vide stari snapshot, nema TRUNCATE locka); prazan rezultat dok redovi postoje = rollback + ERROR.
- **API (cusma2):** lookup samo na 1502 putu (aktivni brodovi 0 upita), ponovno provjerava da je nasljednik i dalje posluživ; svaka greška (npr. tablica još ne postoji) = običan 1502. Stari slug i bilo koji slug koji završava starim id-jem rade (id iz zadnjeg segmenta).

**Akcije pred deploy:**

- [ ] Prije deploya opet `git pull` + `ls src/main/resources/db/migration | sort -V | tail -3`: V9_73 mora i dalje biti slobodan (B8 iz `docs/pending-migrations` uzima SLJEDEĆI slobodni broj).
- [ ] Redoslijed **cusma2 → cusma3** (Flyway na cusma2 kreira tablicu; cusma3 je pinned). V9_73 = nova prazna tablica, bez locka na postojećim tablicama.
- [ ] Nakon cusma3 restarta u logu: `Yacht successors: N retired boats named a live listing ...` (startup fill); provjera: `SELECT count(*) FROM yacht_successor;` ≈ 2.7k i `SELECT new_id FROM yacht_successor WHERE old_id = 4066;` = 11681.
- [ ] `curl -s https://api.boat4you.com/public/yachts/lagoon-bnteau-lagoon-42-4-2-cab-masterpiece-4066` → 400 s `"successorSlug":"lagoon-42-masterpiece-11681","successorId":11681`.
- Rollback: stari jar; tablica može ostati (nitko je ne čita) ili `DROP TABLE yacht_successor;`.

## 2026-10-06 — Kapacitet i oprema broda točno kako ih šalju MMK / NauSys (capacity contract v1: V9_72, sync, blokovi `capacity` / `rig`, filter osoba, jedra, AI chat) + review popravci — ✅ DEPLOYANO 6.10.2026 (V9_72 ručno 17:52 UTC na cusma4; cusma2 17:52 + cusma3 17:53 UTC, jar md5 c7b49e92…; frontendi tek nakon gate SQL-a 7.10.) (commiti `c374579` `dd7c977` `ac4a874` `ccc5c3e` `a96efe8` + review `422841d` `29abb20` `5b9803e` `e056aba` `2bd81a9` `c8da846`)

Mario 6.10.: broj kabina, ležajeva i WC-a te raspored moraju biti 100 % kao kod partnera na svim površinama (b4y, 6 sistera, admin Offers + e-mail ponude, PDF), da klijent nikad ne mora pitati. Odluke: (1) partnerske napomene prevodi web kroz pregledanu tablicu; (2) filter osoba `COALESCE(max_persons, berths)`, samo filter / brojevi / AI pretraga, nikad cijena; (3) `SailTypeEnum` ispravljen u istom releaseu; (4) interne partnerske napomene samo u adminu.

**Commiti:**

- `c374579` **V9_72:** 14 nullable kolona na `yacht`, spremljeno kako partner šalje. To su MMK napomene uz kabine / ležajeve / WC, NauSys ležajevi u kabinama i u salonu, tuševi, preporučeni broj osoba, oznake jedara, MMK oznaka motora, NauSys broj motora i snaga po motoru te `internal_remark` (samo admin). Trigger V9_71 se NE mijenja (to je B8, kasnije).
- `dd7c977`:
  - strogi parser napomena (gosti / posada / skipper / salon);
  - parser snage motora, koji broji samo snagu s jedinicom ("Volvo MD 22 Saildrive 40 h.p." 880 → 40);
  - oznake jedara;
  - jedan sanitizer `PartnerTextSanitizer.capacityNote`;
  - `SailTypeEnum`: letve / classic / flok → `CLASSIC_SAIL`, rolo → `ROLLING_SAIL`, MMK „None" → `UNKNOWN` (dosad je full batten bio `ROLLING_SAIL`).
- `ac4a874` **sync MMK + NauSys** (samo cusma3, `data-sync`): sve kolone kapaciteta se pišu bezuvjetno iz partnera. Uz to:
  - ispravljen je **NauSys bug `crew_wc = wc`** (sada `wcCrew`);
  - `crew_number` se briše kad partner isprazni listu posade;
  - `engine_power` dolazi iz parsera.
- `ccc5c3e` **API:**
  - `capacity` + `rig` na detalju, rezervaciji i tripu;
  - lista ima `berths`, `wc` i kratki `capacity` (jedan PK lookup po stranici, zajedno s `updatedAt`);
  - admin dobiva `brokerNotes` (sirove napomene + interna napomena, samo `SYSTEM_ADMIN`);
  - replacement pretraga ima `berths` / `wc` / `capacity`;
  - filter osoba je `COALESCE(max_persons, berths)` u listi, brojanju i distributionu. Cijene i extrasi i dalje koriste samo `max_persons`.
- `a96efe8` **AI chat:** kapacitet iz bloka `capacity`, bez nula (`asInt(0)`), bez „sleeps up to {maxPersons}"; posada samo za crewed ponude.
- Review popravci 6.10.:
  1. `422841d` **V9_72** čeka lock najviše **1 s po pokušaju, 30 pokušaja s 1 s razmaka** (oko 1 min; bilo 3 s × 10). Dok ALTER čeka ACCESS EXCLUSIVE, **iza njega čekaju i sva ČITANJA `yacht`**, ne samo pisanja (komentar ispravljen). Blokiraju ga sync, refresh matviewa (ACCESS SHARE 180–423 s) i pg_dump. Novi test: dok sync drži `yacht`, čitanje čeka najviše jedan pokušaj (< 1,6 s; s 3 s test pada).
  2. `29abb20` `YachtSearchPagingStabilityTest`: tjedan je subota godinu dana od danas. Fiksni 3.–10.10.2026 je prošao i 3 testa su bila crvena na HEAD-u, među njima jedina end-to-end provjera `updatedAt` kroz prepisani lookup.
  3. `e056aba` **Test admin-gatea:** `brokerNotes` i tekst interne napomene dobiva samo `SYSTEM_ADMIN`.
     - Pokriveno: lista, replacement pretraga i serijalizirani JSON; prijave bez autentikacije, anonymous token, `USER` i `MANAGER`.
     - Mapper skriva napomenu i kad SQL već vrati remark (`isAdmin = true`).
     - Mutacija bez provjere uloge pada.
  4. `2bd81a9` **AI chat:**
     - Više ne navodi crew WC. Chat ide live s cusma2, a `crew_wc` je i dalje `= wc` na svakom NauSys brodu dok ga ispravljeni NauSys sync (cusma3, noću) ne prepiše. Stranica broda crew WC prikazuje tek nakon gate SQL-a.
     - U system prompt idu samo partnerske napomene ≤ 60 znakova. Sve iz uzorka od 896 brodova su kraće.
  5. `5b9803e` **sanitizer, imena** (reviewerove probe su prije prolazile):
     - agencije od 3 slova (TYC, NCC, UNA, MDM, GTF);
     - sam brend bez trgovačkog sufiksa, najmanje 5 znakova („Navigare Yachting" → `navigare`, „Pitter Yachtcharter" → `pitter`). Iznimke: imena s allow-liste u operators.txt i oblici sastavljeni samo od riječi kapaciteta („master", „seven", „starboard"), pa „(4+1 master cabin)" ostaje;
     - fold zadržava slova svih pisama (grčka / ćirilična imena prije su postala prazan string);
     - sve provjere rade na NFKC obliku („example．com", fullwidth znamenke).
     - Svih 71 napomena, 121 oznaka motora s jedinicom i sve oznake jedara iz uzorka i dalje prolaze nepromijenjene.
     - Svjesno: brend koji je ujedno obična riječ ili mjesto („mainsail", „ionian", „luxury") sakrije i napomenu koja tu riječ samo koristi. Broj ostaje. Bolje sakriti napomenu nego pokazati ime operatera.
  6. `5b9803e` Imena agencija se više **ne čitaju na request threadu**. Tamo bi neuspio upit abortirao transakciju poziva i stranica bi pala. Sada se čitaju pri startu (`@PostConstruct`, prije posluživanja), zatim jednom na sat u pozadinskoj niti. Dok traje čitanje, vrijedi stari popis. Kod greške ostaje stari popis, a novi pokušaj ide za minutu.
  7. `5b9803e` Test: `src/main/resources/partner/operators.txt` mora biti jednak `infra/deploy-scripts/operators.txt`. Preskače se ako `infra/` nije checkoutan pored backenda. Novo ime operatera dodati u OBA.
  8. `c8da846` Uklonjen mrtvi `extractAndMultiplyNumbers` i njegov test. Slučajevi su i dalje pokriveni u `EnginePowerParserTest`.
- **Odbijeno (lažno pozitivno):** „V9_71 još nije deployan, ručno primijeniti i njega". V9_71 je **LIVE od 1.10. 18:07 UTC** (val 2, `42b85cc`): ručno primijenjen na cusma4, Flyway ga je zapisao. Live `GET /public/yachts?sortBy=id&idFrom=3400&idTo=3500` 6.10. vraća `updatedAt`. Zaglavlja dvaju unosa od 1.10. ispod („NIJE DEPLOYANO") su zastarjela. **Ručno se primjenjuje SAMO V9_72.**

**Verifikacija (6.10.):**

- **Puni suite:** 487 testova, **isti 31 pre-existing failure** kao u ranijim unosima, nijedan u dirnutom kodu: `ReservationPaymentPhasesServiceTest` 26, `ReservationOptionsCombinationProviderTests` 2, `Boat4youWsApplicationTests` 1 (env), `MatchersTests` 1, `NauSysDateTimeWrapperTests` 1. Na HEAD-u prije popravaka bila su još 3 paging testa.
- **Kapacitet:** parser, motor, referentni brodovi, sanitizer i jedra (22 testa); AI chat 5; `YachtSearchCapacityTest` 5 i migracija 4 (oba na pravom Postgresu, migracija na PG18). Uz to search testovi: paging 3, dated 5, scope 6, weekly 4, `YachtContentModifiedTest` 7, DTO JSON 3.
- **Mutacije:** svaka od ovih izmjena ruši test:
  - maknuti admin-gate;
  - 3 s lock_timeout;
  - čitanje agencija na threadu poziva;
  - fold samo `[a-z0-9]`;
  - bez NFKC;
  - bez brend oblika.
- **Hibernate `validate`** tablice `yacht` prema HEAD entitetu, na `b4y-rehearsal` (kopija proda) s primijenjenim V9_72: OK. Čitanje svih 14 atributa kroz JPQL vraća String / Short / BigDecimal, kako treba.
  - ⚠️ Hibernateov DDL isolator commita otvorenu transakciju, pa je ALTER V9_72 tamo ostao commitan. 14 praznih kolona odmah je maknuto, rehearsal `yacht` je opet 45 kolona.
  - Lokalni docker run API-ja (contract §11) nije rađen; validate na kopiji proda ga zamjenjuje za mapiranje.
- **Lookup po stranici** (rehearsal, V9_71 + V9_72 u transakciji pa ROLLBACK): 100 id-eva preko PK indeksa = **0,34 ms**.
- **Jar** (`bootJar`): nema dupliciranih verzija migracija (68 × V9), V9_72 u jaru == HEAD, `partner/operators.txt` u jaru == infra.
- **ktlint:** 0 nalaza na izmijenjenim linijama (ostalo je pre-existing).

**Mario / GSC moraju znati (dolazi s backendom, ne s webom):**

- **Filter jedara:** brojke se sele s „Rolling" na „Classic" za većinu brodova (oko 73 % MMK, 84 % NauSys) već na prvom syncu na cusma3. Stranice odmah prikazuju ispravan tip.
- **Filter snage motora:** vrijednosti se mijenjaju (880 → 40 i slično). U uzorku 75 od 896 MMK brodova dobiva `null` (40 nemaju jedinicu u oznaci motora, ostali su dvosmisleni) i ispada iz rezultata filtera motora.
- **Filter osoba:** brodovi bez `max_persons` (oko 49 %) sada ulaze preko `berths`.
- **Sitemap `<lastmod>` za gotovo svaki brod već prvog dana:** trigger V9_71 (live) prati `mainsail_type`, `engine_power` i `crew_number`. Prvi sync na cusma3 zato zapiše `yacht_content_modified` za većinu aktivnih brodova (rehearsal: 11.046 `ROLLING_SAIL`, većina postaje `CLASSIC_SAIL`). Val ponovnog crawla dolazi s backendom; B8 kasnije još jednom zapiše oko 13,6 tisuća brodova.
- **AI chat** dobiva nove brojke odmah nakon restarta cusma2, bez frontend gatea.

**Deploy (OBAVEZAN REDOSLIJED; ništa u sync slotovima):**

Slotovi (UTC) koje gate mora izbjeći (ABORT):

- MMK availability 08:40 / 12:40 / 16:40 / 20:40 (oko 17 min);
- MMK near-term 10:50 / 16:50;
- MMK full 06:00–07:30;
- NauSys availability 10:20 / 16:20 / 22:20;
- NauSys near-term 10:40 / 16:40;
- NauSys retry 06:15 / 10:15 / 15:15;
- NauSys noćni 23:20 do oko 06:00;
- NauSys search-retry drain svakih 15 min;
- refresh matviewa svakih 10 min (180–423 s);
- pg_dump backup 07:10 / 11:10 / 16:10;
- RetentionReaper 03:40.

Preporuka: koraci 2–4 jedan za drugim u istom mirnom prozoru, npr. 21:00–22:10 UTC (nakon MMK availability 20:40 + 17 min, prije NauSys 22:20). Tako noćni NauSys (23:20) dolazi uskoro.

1. **Build:** `cd boat4you-backend/boat4you-ws-main && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew bootJar`.
   - Zapisati `shasum`.
   - Provjeriti duplikate migracija: `unzip -l build/libs/boat4you-0.0.1-SNAPSHOT.jar | grep -o 'db/migration/V[0-9_]*__' | sort | uniq -d` mora biti prazno.
   - scp jar na cusma2 i cusma3 kao `webservice_new.jar`.
2. **Ručna primjena V9_72 na cusma4, PRIJE restarta cusma2**, odmah nakon što završi refresh matviewa i izvan pg_dumpa.
   - a) Provjera (cusma4, samo čitanje). Pokrenuti 2–3 puta; smije ostati samo kratko čitanje API-ja koje u idućem pokretanju nestane. NE smije biti `REFRESH`, `pg_dump`, `idle in transaction` ni sync `UPDATE` / `INSERT`:

     ```sql
     SELECT a.pid, a.state, l.mode, now() - a.xact_start AS age, left(a.query, 80)
       FROM pg_locks l JOIN pg_stat_activity a USING (pid)
      WHERE l.relation = 'public.yacht'::regclass AND a.pid <> pg_backend_pid();
     SELECT pid, application_name, now() - query_start AS age, left(query, 60) FROM pg_stat_activity
      WHERE (query ILIKE 'REFRESH MATERIALIZED VIEW%' OR application_name = 'pg_dump') AND pid <> pg_backend_pid();  -- 0 redaka
     ```

     Stara provjera (samo `RowExclusiveLock` i jači) **ne vidi** refresh matviewa ni pg_dump, a ALTER bi svejedno čekao.

   - b) scp `src/main/resources/db/migration/V9_72__yacht_partner_capacity.sql` na cusma4 u `/tmp/`, zatim `sudo -u postgres psql -d boat4you_db -v ON_ERROR_STOP=1 -c 'SET ROLE boat4you_owner' -f /tmp/V9_72__yacht_partner_capacity.sql`.
   - c) `SELECT count(*) FROM information_schema.columns WHERE table_name = 'yacht' AND column_name IN ('cabins_note','berths_note','wc_note','cabin_berths','salon_berths','showers','crew_showers','recommended_persons','mainsail_label','genoa_label','engine_label','engine_count','engine_power_each','internal_remark');` mora vratiti **14**.
   - Ako ispiše `V9_72: yacht is locked (attempt n of 30)` i padne, ponoviti kasnije. API za to vrijeme radi, samo čitanja povremeno čekaju do 1 s.
3. **cusma2:** provjera iz 2a još jednom, zatim:
   - `cp -p webservice.jar webservice.jar.prev && mv webservice_new.jar webservice.jar && sudo systemctl restart boat4you.service`.
   - Flyway nađe svih 14 kolona, izlazi bez locka i zapisuje 9.72.
   - Poll `GET https://api.boat4you.com/public/settings/card-surcharge` dok ne bude 200.
   - Provjere (≤ 1 zahtjev/s, normalan browser UA, NIKAD Googlebot):
     - `/public/yachts/<slug>` ima `capacity` i `rig`;
     - `/public/yachts?did=c-54&size=18` ima `berths`, `wc`, `capacity` i `"brokerNotes":null`;
     - admin Offers (prijavljen admin) vidi `brokerNotes`;
     - `journalctl -u boat4you.service --since '10 min ago' | grep -c 'agency names unavailable'` daje 0.
4. **cusma3 odmah nakon toga, kroz tvrdi gate** (cusma3 entitet čita nove kolone, zato tek nakon koraka 2):
   - U ISTOJ skripti: `n=$(journalctl -u boat4youscheduler.service --since '10 minutes ago' | grep -ci 'nausys\|mmk'); [ "$n" -gt 0 ] && { echo "ABORT: $n sync linija"; exit 1; }`.
   - Tek onda `cp -p webservice.jar webservice.jar.prev && mv webservice_new.jar webservice.jar && sudo systemctl restart boat4youscheduler.service`.
   - Flyway je pinned i javlja „Schema up to date".
   - **Zapisati vrijeme. Od njega se računa čekanje u koraku 5, ne od cusma2.**
5. **Čekati jedan puni NauSys (23:20 → oko 06:00) i jedan puni MMK (06:10) ciklus na novom jaru.**
6. **Gate SQL** (cusma4, samo čitanje), PRIJE ijednog frontend deploya:

   ```sql
   WITH s AS (SELECT y.*, em.external_system_id AS sys
                FROM yacht y JOIN external_mapping em ON em.system_id = y.id AND em.type = 'Yacht'
               WHERE y.sys_active)
   SELECT sys,                                                          -- 1 = MMK, 2 = NauSys
          count(*)                                                        AS active,
          count(*) FILTER (WHERE sys = 2 AND salon_berths IS NULL)        AS ns_salon_null,     -- ~0
          count(*) FILTER (WHERE sys = 2 AND wc > 0 AND crew_wc = wc)     AS ns_crew_wc_eq_wc,  -- bilo ~7,6k, sada malo
          count(*) FILTER (WHERE sys = 1 AND mainsail_label IS NULL)      AS mmk_mainsail_null, -- ~0
          count(*) FILTER (WHERE sys = 1 AND berths_note IS NOT NULL)     AS mmk_berths_note,   -- 40-50 %
          count(*) FILTER (WHERE mainsail_type = 'ROLLING_SAIL')          AS rolling,           -- samo rolo
          count(*) FILTER (WHERE mainsail_type = 'CLASSIC_SAIL')          AS classic
     FROM s GROUP BY sys;
   -- agencije čiji NauSys sync nije prošao na novom jaru
   SELECT a.name, count(*) FROM yacht y JOIN agency a ON a.id = y.agency_id
     JOIN external_mapping em ON em.system_id = y.id AND em.type = 'Yacht' AND em.external_system_id = 2
    WHERE y.sys_active AND y.salon_berths IS NULL GROUP BY 1 ORDER BY 2 DESC LIMIT 20;
   -- C12: samo EN oznake (bez „Lattée", „Lattengroß")
   SELECT mainsail_label, count(*) FROM yacht WHERE sys_active GROUP BY 1 ORDER BY 2 DESC;
   -- PNOE (MMK 6169078420000104347): EN oznake u testnom payloadu nisu s poziva bez jezika - ovdje potvrditi
   SELECT id, mainsail_label, genoa_label, cabins_note, berths_note FROM yacht WHERE name = 'PNOE';
   SELECT count(*), max(modified_at) FROM yacht_content_modified WHERE modified_at > '<vrijeme koraka 4>';  -- val lastmod-a
   ```

   Ako gate ne prođe, frontende NE deployati, nego prvo istražiti.

7. **admin → b4y → 6 sistera**, svaki po svom DEPLOY_NOTES, strogo nakon prolaza gatea.
8. **Kasnije B8** (`B8_trigger_later.sql`, zasad samo u scratchpadu sesije 87cc80f2, `…/scratchpad/capacity/contract/`; /private/tmp se čisti nakon oko 3 dana, pa ga sačuvati). B8 postaje `V9_<next>` tek kad b4y i svih 6 sistera renderiraju nove blokove.
   - Proširuje UPDATE trigger V9_71 novim kolonama, BEZ `internal_remark`.
   - Guard je definicija triggera.
   - Jednokratno zapisuje `yacht_content_modified` za brodove s novim sadržajem: oko 13.624, opet val crawla.
   - Primjenjuje se ručno prije restarta, kao V9_72. `CREATE OR REPLACE TRIGGER` uzima SHARE ROW EXCLUSIVE: čitanja ne čekaju, pisanja čekaju.

**Rollback:**

- Ako frontendi već čitaju nove blokove, prvo frontendi, onda backend.
- **Backend:** `mv webservice.jar.prev webservice.jar` + restart (cusma2; cusma3 kroz isti gate). Može i `git revert c8da846 2bd81a9 e056aba 5b9803e 29abb20 422841d a96efe8 ccc5c3e ac4a874 dd7c977 c374579`, zatim novi jar i isti deploy.
- Kolone V9_72 smiju ostati: nullable su, a stari jar ih ne čita.
- Pazi: stari sync ponovno upisuje `crew_wc = wc` i staro mapiranje jedara. Brojke filtera i `<lastmod>` se time opet pomaknu.
- **Brisanje kolona** samo ako baš treba, u mirnom prozoru odmah nakon refresha matviewa (ACCESS EXCLUSIVE, čitanja čekaju): `SET lock_timeout = '1s'; ALTER TABLE yacht DROP COLUMN cabins_note, DROP COLUMN berths_note, DROP COLUMN wc_note, DROP COLUMN cabin_berths, DROP COLUMN salon_berths, DROP COLUMN showers, DROP COLUMN crew_showers, DROP COLUMN recommended_persons, DROP COLUMN mainsail_label, DROP COLUMN genoa_label, DROP COLUMN engine_label, DROP COLUMN engine_count, DROP COLUMN engine_power_each, DROP COLUMN internal_remark; DELETE FROM flyway_schema_history WHERE version = '9.72';`

**Otvoreno:**

- Frontend `safeCapacityNote` (`yachtCapacity.ts`, byte-identičan u 8 repoa, druga linija obrane) treba isti NFKC korak. Nova pravila za imena (3 slova, sam brend, sva pisma) vrijede tamo samo ako frontend preda vlastiti matcher imena (b4y `operatorNames.ts`, samo na serveru); jedini gate je backend.
- B8 datoteku sačuvati izvan /private/tmp.
- Oznake jedara za PNOE potvrditi u gate SQL-u.
- Admin izvještaj o blizancima koji se ne slažu (contract §12.4) nije dio ovoga.

## 2026-10-01 — Review vala 2: V9_71 idempotentan (ručna primjena prije restarta), provjera locka prije restarta, test JSON oblika `updatedAt` — ⏳ NIJE DEPLOYANO (commit `54a8665`, nadograđuje `4d3a303`)

**Ispravak tvrdnje iz unosa ispod:** „čitanja NIKAD ne čekaju taj lock" vrijedi samo u bazi. Flyway radi PRIJE nego API počne posluživati, a cusma2 je jedini API čvor. Svaka sekunda koju `CREATE TRIGGER` čeka na sync koji piše `yacht` zato je ispad API-ja (do ~1 min), a nakon 10. pokušaja API se ne digne (restart petlja).

**Promjene:**

- **V9_71 je idempotentan** (još nigdje primijenjen, pa izmjena checksuma ne smeta; provjereno: cusma4 `flyway_schema_history` zadnji 9.70, lokalni Postgresi nemaju 9.71). Ako oba triggera već postoje, DO blok odmah izlazi i **ne uzima nikakav lock na `yacht`**. Inače `CREATE OR REPLACE TRIGGER` (PG14+, cusma4 = 18.1), pa se i polovično stanje (jedan trigger) dovrši. Tablica `IF NOT EXISTS`, funkcija `OR REPLACE`, GRANT je no-op.
- Komentar u migraciji sada točno opisuje rizik (zastoj starta API-ja, ne čitanja).
- **Testovi:** `YachtContentModifiedTest` +2 (7/7 na pravom PG18): ručno primijenjen V9_71 + sync koji drži `ROW EXCLUSIVE` na `yacht` → Flyway prolaz završi ispod 1 s s `lock_timeout = 1s` (bez čekanja); polovično stanje se dovrši. Novi `YachtSearchResponseDtoJsonTest` (2) s Bootovim auto-konfiguriranim ObjectMapperom (isti kao API, nema custom mappera ni `spring.jackson` postavki): `"updatedAt":"2026-10-02T06:12:41Z"` (string, cijele sekunde) i eksplicitni `null`. ktlint čist.
- **Odbijen nalaz „`main_image_id` nema pisca":** pišu ga MMK i NauSys sync (`MmkYachtSyncService:188`, `NauSysYachtSyncService:166`, `getMainImage(...)`) i admin (`YachtMutationService`). cusma4 1.10. (samo čitanje): 13.005 od 13.072 aktivnih brodova ima `main_image_id`, 12.914 pokazuje na sliku s `main_image = true`; triggera na `yacht`/`yacht_image` nema. Kolona u triggeru ostaje.

**Deploy (zamjenjuje korak 3 unosa ispod):**

1. **Ručna primjena PRIJE restarta, u mirnom trenutku** (izvan sync prozora iz unosa ispod), na cusma4 kao vlasnik sheme (Flyway radi kao `boat4you_owner`):
   - scp `src/main/resources/db/migration/V9_71__yacht_content_modified.sql` na cusma4 `/tmp/`;
   - `sudo -u postgres psql -d boat4you_db -v ON_ERROR_STOP=1 -c 'SET ROLE boat4you_owner' -f /tmp/V9_71__yacht_content_modified.sql`;
   - provjera: `SELECT tgname FROM pg_trigger WHERE tgname LIKE 'yacht_content_modified_%'` → 2 retka. Ako ispiše `V9_71: yacht is being written (attempt n of 10)` i padne, samo ponoviti kasnije; API pritom radi normalno.
2. **Neposredno prije restarta cusma2** (cusma4, mora vratiti 0 redaka; inače pričekati):
   `SELECT a.pid, a.xact_start, a.state, left(a.query, 80) FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE l.relation = 'public.yacht'::regclass AND l.mode IN ('RowExclusiveLock','ShareUpdateExclusiveLock','ShareLock','ShareRowExclusiveLock','ExclusiveLock','AccessExclusiveLock');`
   Nakon koraka 1 ovo je samo dodatna sigurnost (Flyway tada ne uzima lock), bez koraka 1 je obavezno.
3. Restart cusma2: Flyway zapiše V9_71 kao primijenjen (tablica i triggeri već postoje, ništa ne čeka). Ostalo kao u unosu ispod (koraci 4–5).

**Rollback:** isti kao u unosu ispod.

## 2026-10-01 — `updatedAt` po brodu u javnoj listi brodova za `<lastmod>` u yacht sitemapima (V9_71, Codex N7) — ⏳ NIJE DEPLOYANO (commit `4d3a303`)

**Problem (N7):** yacht sitemapi na svih 7 sajtova nemaju `<lastmod>`, a baza nije imala nikakav podatak o tome kad se brod promijenio. `yacht`, `offer`, `yacht_image` i `yacht_translations` nemaju vremensku kolonu, a `synced_entity` se nikad ne puni. Provjereno na cusma4 (samo čitanje).

**Promjene:**

- **V9_71:** nova tablica `yacht_content_modified (yacht_id PK, modified_at timestamptz)`. Puni je trigger na `yacht`: AFTER INSERT (novi brod) i AFTER UPDATE samo kad se promijeni polje koje javna stranica broda prikazuje. To su: ime, model, matična marina, godina, duljina, širina, kabine, WC, ležajevi, osobe, motor, tankovi, tip glavnog jedra, depozit (i osigurani, valuta), posada, check-in/out, tip plovila, entry type, `sys_active`, glavna slika i `option_approval` (inquiry-only).
- Sync koji ponovno spremi iste vrijednosti NIJE promjena (`IS DISTINCT FROM`). Ni promjena privatnog polja nije promjena: provizija, registracija, agencija, popusti.
- Vrijeme je `clock_timestamp()` same promjene i nikad ne ide unatrag.
- **Namjerno se NE broje:** cijene i dostupnost (`offer`, milijuni redaka), galerija, opisi, oprema, extras, preimenovanje modela ili proizvođača (mijenja slug, pa sitemap ionako dobije novi URL), twin canonical.
- Postojeći brodovi kreću BEZ retka. To znači da promjena nije zabilježena, a ne da se nagađa.
- **`GET /public/yachts`:** novo polje `updatedAt` na svakoj kartici, ISO-8601 UTC u cijelim sekundama (npr. `"2026-10-02T06:12:41Z"`), ili `null`. Čita se jednim upitom po primarnom ključu po stranici, izvan upita liste. Upit liste i matview nisu dirani.
- Polje dobivaju sve liste, pa i b4y id-range shardovi (`sortBy=id&idFrom&idTo`) i sister fleet/sitemap pozivi (`did`, `countryCodes`). Admin replacement lista (`includeUnavailable`) ga nema.

**Mjerenja:**

- Produkcija 1.10. (xmin redaka `yacht` prema `service_call_cache.created_at`): 10.855 od 13.072 aktivnih brodova bez ijednog upisa u 45 dana; inače 4–99 brodova dnevno (vrh 385 na 29.9.). `<lastmod>` će se zato pojavljivati postupno, samo za stvarno promijenjene i nove brodove.
- Lookup 100 id-eva po PK na cusma4: 0,05 ms (EXPLAIN ANALYZE). Lokalni suhi prolaz na 15 tisuća brodova: puni no-op re-save ne upiše ništa, trošak triggera je u šumu, 3.000 stvarnih promjena upiše 2.990 redaka (10 brodova bez kabina, NULL + 1 = NULL).

**Testovi:** 447, isti 31 pre-existing failure kao prije (popis identičan). Novi `YachtContentModifiedTest` vrti pravi V9_71 na PostgreSQL 18: insert; svako prikazano polje; re-save i privatno polje bez promjene; nikad unatrag; čekanje na lock uz istovremenog pisca dok čitatelji prolaze. Uz to novi test id-range sharda u `YachtSearchPagingStabilityTest`. Mutacijski provjereno (maknuta kolona, `<` → `IS DISTINCT FROM`, jedan pokušaj, `null` umjesto mape: svaki pada). Migracija prošla i kroz pravi Flyway (`Successfully applied`). ktlint: nijedan novi nalaz.

**Deploy (redom):**

1. **Backend PRIJE weba koji čita `updatedAt`.** Web mora i sam tretirati polje koje nedostaje ili je `null` kao „bez `<lastmod>`", jer stari jar polje nema.
2. Jar = `main` HEAD, pa uključuje i `4f0f815` + `f4dbb23`. Ako oni još nisu live, vrijedi njihov PREDUVJET iz unosa ispod (web prvi).
3. **cusma2 izvan yacht syncova:** NauSys katalog 23:00+, MMK 06:00–07:30 UTC, backup 07:10/11:10/16:10, RetentionReaper 03:40. Flyway na startu kreira trigger. ~~Čitanja (stranice brodova, liste) NIKAD ne čekaju taj lock.~~ Netočno za start API-ja: Flyway radi prije posluživanja, pa čekanje = ispad. **Vidi unos iznad (ručna primjena V9_71 prije restarta + provjera locka).** Ako cusma3 baš piše u `yacht`, migracija čeka najviše 3 s po pokušaju, do 10 pokušaja (oko 1 min), pa tek onda pada. U logu je to `V9_71: yacht is being written (attempt n of 10)`.
4. cusma3 kroz isti tvrdi gate kao u unosu ispod (jar paritet). V9_71 je tada već primijenjen.
5. Provjere (cusma4, samo čitanje): `SELECT tgname FROM pg_trigger WHERE tgname LIKE 'yacht_content_modified_%'` daje 2 retka. Nakon idućeg yacht synca `SELECT count(*), max(modified_at) FROM yacht_content_modified` raste. API: `/public/yachts?sortBy=id&idFrom=3400&idTo=3500&size=100` ima `updatedAt` (većinom `null` prvih dana).

**Rollback:** samo `webservice.jar.prev`. Stari jar ne čita tablicu, a trigger smije ostati jer samo piše u nju. Uklanjanje (samo ako baš treba, u mirnom prozoru): `SET lock_timeout = '3s'; DROP TRIGGER yacht_content_modified_update ON yacht; DROP TRIGGER yacht_content_modified_insert ON yacht; DROP FUNCTION yacht_content_modified_touch(); DROP TABLE yacht_content_modified; DELETE FROM flyway_schema_history WHERE version = '9.71';`. Pazi: `DROP TRIGGER` uzima ACCESS EXCLUSIVE, pa čitanja `yacht` čekaju do 3 s.

## 2026-10-01 — Teški upiti (distribution + lista) ograničeni da boat stranice uvijek dobiju vezu na bazu (Codex F2) — ⏳ NIJE DEPLOYANO (commit `4f0f815` + review popravci `f4dbb23`)

Mario 1.10.: „DEPLOY POPRAVAK" (kratak restart cusma2 + cusma3 odobren; brojke na landinzima smiju kasniti do 30 min, samo prikaz).

**⚠️ PREDUVJET — redoslijed deploya (review 1.10.):** pod naletom backend sada NAMJERNO vraća 503 (`2003 SEARCH_BUSY`, `Retry-After: 5`) na `GET /public/yachts` i `/distribution`. Živi web kod to ne podnosi, pa PRIJE ovog jara (ili u istom prozoru, web prvi) moraju biti deployani:

- **Greece / CC / Italy / Caribbean** — F5 `getFleetChunk` baca grešku umjesto `catch { return { content: [] } }` (inače fleet walk pod naletom spremi djelomičnu flotu na 6 h u `/fleet`, sitemap i brojke): GR `cb1aa77` + `b6034ec`, CC `f7074c6` + `d081ec9`, IT `94d8d5e` + `91d57c3`, CB `a9547eb` + `290cd9c`.
- **Greece / CC / EY** — 503 s `Retry-After` više ne postavlja globalni `downUntil` (inače jedan zauzet related upit = 15 s 503 na listingu i stranicama broda): GR `b6034ec`, CC `d081ec9`, EY `4ec7180` + `b5c2da5`.
- **Svih 6 sistera + b4y** — related na stranici broda: jedan pokušaj s kratkim rokom (sisteri 3 s, b4y 2 s), greška prekida kaskadu (nema širenja regija → država → sve, `size=100`): CY `821087e` + `168df04`, b4y `723704c6`…`da16e778`, ostali gore.

**Problem (verificirano, `codexverify/slow.md`):** Hikari pool (35) prazan 100–400×/sat u naletima od 1–3 min (`waiting=168`). 22 veze držane 60–120 s, sve u `YachtDistributionController.getDistribution`; detalj broda i standard-offers čekali su 20 s na vezu i padali (b4y 499/503, sisteri 5.183×499 30.9.). Okidač: nalet hladnih landing stranica (svaka zove distribution + listu), `@Cacheable` bez `sync` pa je svaki istovremeni promašaj vrtio svih 9–11 skenova matviewa, TTL 3 min, bez statement_timeouta.

**Promjene:**

- `YachtDistributionService.getDistribution` (review `f4dbb23`: ručni cache umjesto `@Cacheable(sync = true)`, čiji je Ehcache `compute` čekanje na gate i sam upit vrtio pod lockom bina): **hit** je obično čitanje bez locka i nikad ne čeka; istovremeni **promašaji istog ključa** vrte skenove JEDNOM (single flight: prvi ide kroz gate, ostali čekaju njegov rezultat — ili njegov 503 — bez slota i bez veze, najviše 60 s); promašaji različitih ključeva ne čekaju jedan drugoga. Ključ ima prefiks `landing:` / `search:`; `CacheConfig.FacetDistributionExpiry`: **landing = bez datuma I bez numeričkih slidera (cijena, duljina, kabine, …) → 30 min** (landinzi svih 7 sajtova + b4y model katalog); **sve ostalo 3 min kao prije** — i sidebar pretrage bez datuma, ali sa sliderima (Mario je kašnjenje odobrio samo za landinge), i promo ključ (cijeli katalog s datumima = isti ključ kao sidebar datumske pretrage bez destinacije). Hit ne produžuje TTL; test s Ehcache test-satom dokazuje stvarni istek (landing 30 min, datumski/slider 3 min). Max 2.000 unosa (cijela HR ≈ 10 KB JSON-a).
- Novi `HeavyQueryGuard` za `GET /public/yachts/distribution` i `GET /public/yachts` (lista): (1) **gate** — distribution najviše **4**, lista **6** istovremeno (= najviše 10 od 35 veza; DB ima 2 jezgre pa više paralelnih skenova ionako ne završi brže), čekanje na slot **1,5 s**, red najviše 4 po slotu, ostalo odmah **503 + `Retry-After: 5`**, kod `2003 SEARCH_BUSY`; **admin (`SYSTEM_ADMIN`) lista ide bez gatea** (`readUngated`: Create Reservation modal i Offers workspace nikad ne dobiju 503 zbog javnog naleta; ista transakcija i timeouti, review `f4dbb23`); (2) **vlastita read-only transakcija** tek nakon gatea — `getDistribution` više nema `@Transactional`, `getYachts` je `NOT_SUPPORTED`, pa cache hit i odbijeni zahtjev nikad ne uzimaju vezu iz poola; (3) **`SET LOCAL statement_timeout` 15 s** (resetira se na commit/rollback, vraćena veza je čista — test) + **timeout transakcije 30 s** (svih 9–11 skenova zajedno; 12-zemalja distribution je ~14 s hladno). Timeout → isto 503 (ne 500 + stack trace). Review `f4dbb23`: i Hikari „Connection is not available" (pool ispražnjen izvan gatea: sync, refresh matviewa) → 503 `TIMED_OUT`, ne 500; prekinuti (interrupted) čekač na slot → 503 `SATURATED`, interrupt flag se vraća.
- Upozorenje u logu najviše jednom u minuti s ukupnim brojem: `Heavy query shed with 503 (DISTRIBUTION SATURATED); gates DISTRIBUTION=x/4 running, y queued, … since start N saturated, M timed out …`.
- Sve granice preko env-a (default u `application.yml`, env NE treba mijenjati): `HEAVY_QUERY_DISTRIBUTION_MAX_CONCURRENT=4`, `HEAVY_QUERY_SEARCH_LIST_MAX_CONCURRENT=6`, `HEAVY_QUERY_WAIT_MS=1500`, `HEAVY_QUERY_STATEMENT_TIMEOUT_MS=15000`, `HEAVY_QUERY_TRANSACTION_TIMEOUT_SECONDS=30`.
- **Nije dirano:** booking, plaćanja, sync, admin replacement lista (`includeUnavailable`, samo admin), relax-suggest, detalj/standard-offers. Matview refresh ostaje kako je: `SearchViewRefreshJob` na cusma3 (`data-sync`, `*/10`) + on-demand `SearchViewRefreshService` na cusma2, oba `REFRESH … CONCURRENTLY` (čitanja ne čekaju lock). Dok traje dugi refresh (30.9. 180–300 s, 1.10. 06:47 423 s) teški upiti su sporiji, ali drže najviše 10 veza i najviše 30 s, pa detalj broda i dalje dobiva vezu. Zašto refresh svakih 90 min traje 180–423 s, i dalje je otvoreno (nije dio ovog commita).
- Bez Flyway migracija.

**Poznati kompromisi:**

- ~~Ehcache `sync` pod lockom bina~~ — riješeno u `f4dbb23` (ručni cache + single flight, vidi gore). Followeri jednog ključa drže Tomcat thread dok leader radi (najviše 60 s, zatim 503); veze ni slotove ne drže.
- Pod naletom landinzi mogu dobiti 503 na distribution/listi. b4y: distribution ionako odustaje nakon 1,5 s i stranica se renderira bez brojki; `fetchWithRetry` ponavlja 0,5/1/2 s; „slični brodovi" na stranici broda jedan pokušaj, 2 s. Sisteri: 5xx se ponavlja, 503 s `Retry-After` ne označava backend down, related jedan pokušaj 3 s bez širenja kaskade (vidi PREDUVJET). Odbijanje ne košta bazu.
- Bez gatea ostaju (namjerno, po specifikaciji): `relax-suggest`, `locations-count`, `charter-facts` — preostali rizik.
- Brojke na landinzima mogu kasniti do 30 min (+ do 10 min refresh matviewa).

**Testovi:** 441 (19 novih: `HeavyQueryGateTests` 7, `HeavyQueryGuardTests` 5 na pravom Postgresu, `YachtDistributionCacheTests` 5 s pravim Ehcache JCache-om i test-satom, `YachtSearchListGateRoutingTest` 2), **isti 31 pre-existing failure** kao HEAD `a92edcb` (popis identičan). Mutacijska provjera: bez single flighta padaju test istovremenih promašaja i test followera odbijenog promašaja. ktlint: main 0; u testovima samo pre-existing nalazi na netaknutim linijama. Full-context smoke (Spring Boot + prazni Postgres, nije commitan): beanovi se dižu, gateovi 4/6, guarded read radi, greška baze kroz sync cache stiže neomotana (`DataAccessException` → 2002).

**Deploy (redom; NE u sync prozoru za cusma3):**

1. `cd boat4you-backend/boat4you-ws-main && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew bootJar` → `build/libs/boat4you-0.0.1-SNAPSHOT.jar`; `shasum` zapisati.
2. scp na cusma2 i cusma3 kao `webservice_new.jar`.
3. **cusma2 prvo** (jedini API): `cp -p webservice.jar webservice.jar.prev && mv webservice_new.jar webservice.jar` → `sudo systemctl restart boat4you.service` → poll `GET https://api.boat4you.com/public/settings/card-surcharge` dok ne bude 200 (~15–20 s). Restart prazni cacheve (prvi landinzi su hladni — upravo to gate sad podnosi).
4. Provjere na cusma2 (≤ 1 zahtjev/s, bez Googlebot UA): `/public/yachts/distribution?did=c-54` 2× (drugi brzo, isti JSON); `/public/yachts?did=c-54&size=18` 200; `/public/yachts/<slug>` 200; `journalctl -u boat4you.service --since '10 min ago' | grep -c 'Connection is not available'` (očekivano 0); `… | grep 'Heavy query shed'` (rijetko ili nikad u mirnom prometu).
5. **cusma3 samo kroz tvrdi gate** (jar paritet, isti kod; scheduler ne zove ove endpointe): u ISTOJ skripti `n=$(journalctl -u boat4youscheduler.service --since '10 minutes ago' | grep -ci 'nausys\|mmk'); [ "$n" -gt 0 ] && { echo "ABORT: $n sync linija"; exit 1; }` → tek onda `cp -p webservice.jar webservice.jar.prev && mv webservice_new.jar webservice.jar` + `sudo systemctl restart boat4youscheduler.service`. Izbjegavati slotove (UTC): MMK availability 08:40/12:40/16:40/20:40 (~17 min), MMK near-term 10:50/16:50, MMK full 06:00–07:30, NauSys availability 10:20/16:20/22:20, NauSys retry 06:15/10:15/15:15, NauSys offer 23:00+.
6. Praćenje 24 h: broj `Connection is not available` po satu (prije 100–400 u naletima), `Heavy query shed` (zasićenje/timeout), 499/503 na boat stranicama u nginx logu cusma1/cusma5.

**Rollback:**

- Samo granice (bez novog jara): u `boat4you_vars.env` (backup `*.bak-YYYYMMDD-heavyq` prije edita) npr. `HEAVY_QUERY_DISTRIBUTION_MAX_CONCURRENT=12`, `HEAVY_QUERY_SEARCH_LIST_MAX_CONCURRENT=12`, `HEAVY_QUERY_STATEMENT_TIMEOUT_MS=60000` + restart; provjera da JVM vidi env: `tr '\0' '\n' < /proc/$(systemctl show -p MainPID --value boat4you.service)/environ | grep HEAVY_QUERY`.
- Cijeli: `mv webservice.jar.prev webservice.jar` + restart (cusma2; cusma3 kroz isti gate). Nema migracija pa nema ni DB koraka.

**nginx timing log (PRIPREMLJENO, NIJE PRIMIJENJENO) — cusma1, cusma2, cusma5:** sada je na sva tri nginx 1.24 s `access_log /var/log/nginx/access.log;` (combined, `/etc/nginx/nginx.conf` linija 40) pa se p95/p99 ne mogu mjeriti. `log_format` mora stajati PRIJE `access_log` koji ga koristi (conf.d se uključuje kasnije), zato ide u `nginx.conf`. Combined ostaje prefiks, nova polja su na kraju, pa postojeći parseri i dalje rade:

```nginx
# /etc/nginx/nginx.conf, http { … }, umjesto linije 40 (access_log …):
log_format timed '$remote_addr - $remote_user [$time_local] "$request" '
                 '$status $body_bytes_sent "$http_referer" "$http_user_agent" '
                 'rt=$request_time urt=$upstream_response_time uct=$upstream_connect_time ucs=$upstream_cache_status';
access_log /var/log/nginx/access.log timed;
```

Primjena po nodu: `sudo cp -p /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak-$(date +%Y%m%d)-timing` → edit → `sudo nginx -t` (MORA biti ok) → `sudo systemctl reload nginx` (reload, ne restart). Rollback: vratiti backup + `nginx -t` + reload. cusma5 `conf.d/cusmanich.conf:81 access_log off;` ostaje. p95 nakon toga: `awk -F'rt=' '/\/boat\//{split($2,a," "); print a[1]}' /var/log/nginx/access.log | sort -n | awk '{v[NR]=$1} END{print "p95", v[int(NR*0.95)], "p99", v[int(NR*0.99)]}'`.

## 2026-10-01 — Hand-verified twin pairs in the one-card-per-boat rule (V9_70, Desafinado 481 / 13163) — ✅ LIVE cusma2 09:01 UTC (jar `bb7901f8`, commit `58742ad`); cusma3 13:03 UTC

Verified 1.10.: cusma2 Flyway "Migrating schema public to version 9.70 - yacht twin manual pair" → "Successfully applied 1 migration" (0.618 s), API health 200 after ~18 s; Desafinado `…-13163` and `…-481` both resolve to 481 with `listingCanonicalSlug` null; undated HR CATAMARAN+POWER_CATAMARAN listing 898 → 897 with only 481 (both before); twins `…-6047` (slug `…-7576`, lcs `…-6047`) and `ilia-8079` (lcs `…-3528`) unchanged; Stage B all 0 new FAIL (3 transient SM1 sitemap timeouts at 09:1x, 200 on refetch). cusma3 (Flyway pinned 1.43): "Schema up to date. No migration necessary", started 13.3 s, jar `bb7901f8`.

SEO regression 1.10.2026 (SM4, Mario „sve sredi"): Fountaine Pajot Saona 47 "Desafinado" is 481 (NauSys, "Trogir, Yachtclub Seget (Marina Baotić)") and 13163 (MMK, "Marina Baotic"). `yacht_listing_twin` (V9_69) never paired them (the base names differ), so every listing and the sitemaps of boat4you, CC, CY and EY carried both, while the boat page shows one copy for either URL (twin-canonical manual group `[481, 13163]`, coverage-first: 13163 on 24.6., 481 on 1.10.) — the sitemap's `…-13163` declared `…-481` as canonical.

- `V9_70__yacht_twin_manual_pair.sql`: new table `yacht_twin_manual_pair` (no FK to yacht — a FK would lock the sync-hot yacht table at start), seeded with (481, 13163) guarded by the production names + build year (a mismatch inserts nothing); `yacht_listing_twin` rebuilt (DROP + CREATE WITH DATA + unique index + grant): the name-rule pairs are unchanged, the manual pairs join them, and the copy shown follows the same stable rule (a week left to sell → directly bookable → lower id). The hidden copy carries `listingCanonicalSlug`, which all 7 sites read since 1.10.
- `application-prod.yml`: comment — mirror every `twin-canonical.manual-groups` group as a pair in the table.
- **Rehearsal on the prod copy (`b4y-rehearsal`, rolled back):** V9_69 196 pairs → V9_70 197; the only difference is `13163 → 481`; seed matched (1 row); migration 0.22 s; `REFRESH … CONCURRENTLY` works afterwards.
- Tests: new `YachtListingTwinTest` case (a pair the name rule cannot match shows one copy by the same rule); `ListingTwinTestSupport` and `CharterFactsTestDb` run V9_70 too. Full suite 422, same 31 pre-existing failures. ktlint clean.
- **Deploy:** cusma2 applies V9_70 (V__ only there); cusma3 only to keep the jars equal (its refresher refreshes the matview by name). After: `/public/yachts/fountaine-pajot-saona-47-desafinado-13163` → `listingCanonicalSlug` = the copy shown (or self if 13163 is the one shown); `/public/yachts?…` undated lists only one Desafinado; Stage B on CC/CY/EY/boat4you without the 13163 SM4 row.

## 2026-09-29 — One inquiry per submit, enforced in the backend — ✅ LIVE cusma2 10:31 UTC (jar `0acbaa29`, commit `f5e6b02`, includes `1d0ac4a`); cusma3 10:36 UTC same jar (gate 0 sync lines)

Mario 27.9. (via BOAT4YOU 3): the inquiry form may send only one inquiry per submit; the boat4you-web guard is in-memory per Node process. `InquiryMutationService.createNewInquiry` builds a key (lower(trim(email)), yacht, dates, phone digits, trim+lower name/surname, whitespace-collapsed message — same as the web guard; a corrected phone/name/message is a new inquiry), takes `pg_advisory_xact_lock(hashtext(key))` and returns 200 without a row or e-mail when an identical inquiry from that e-mail exists in the last 10 minutes. E-mails stay inline — EmailService already defers the SMTP submit to afterCommit; wrapping the call in our own afterCommit (first draft) would have silenced every inquiry e-mail (Spring never runs a synchronization registered inside afterCommit) — caught in review before deploy.

- Prod history: 3 of 7 same-email pairs (4.8 s / 33 s / 91 s apart) would have collapsed; a changed message after 7m48s stays separate.
- Sister sites do not call /public/inquiries (their /api/yacht routes mail via nodemailer) — only their own web guard protects them.
- Pre-existing, NOT changed: an exception while rendering an inquiry e-mail marks the transaction rollback-only → the lead is lost with a 500 (SMTP failures are async and safe). Candidate fix: `noRollbackFor` on the two InquiryEmailService send methods.
- Tests 421 (+3 InquirySubmitKeyTests), same 31 pre-existing failures.

## 2026-09-29 — Renamed partner charge listed once in admin client offers; partner id for admins only; MMK offer extras follow renames — ✅ LIVE cusma2 10:22 UTC (jar `90099988`, then `0acbaa29` 10:31); cusma3 10:36 UTC with `0acbaa29` (commit `1d0ac4a`; admin `4d3fa3f` LIVE 10:21 UTC first)

Mario: the client offer e-mail for Fico - Premium line (13311, 11–18.9.2027) listed "Premium Line Pack (… Outboard Engine)" and "(… Outboard Engine; 1 SUP)" — one MMK charge (id 37419011718800129) renamed in place. ae64ce7's yacht-sync rename (28.9 06:10) gave the catalogue the new name; the matched offer 8773185 (product CREWED) kept the old one because `MmkYachtOfferSyncService` never rewrote `offer_extras.name` (~60k future obligatory rows on ~1,070 MMK yachts). MMK live quotes one offer for that week (Crewed, 08:00, new name); our second row 11996909 (product UNKNOWN, 09:00) is a duplicate from another sync path — separate issue, not touched.

- `YachtExtrasDto.externalId` (partner row id) is set only for SYSTEM_ADMIN (`YachtExtrasMapper.partnerIdForAdmin`), serialized as a string (ids > 2^53). Anonymous /public/yachts/{slug} verified after deploy: 0 of 26 non-null. No cache holds the DTO; nginx caches /public/image only.
- Admin Offers (`4d3fa3f`): cart key stays the `e.key` chain (NOT externalId — NauSys obligatory offer rows carry synthetic ids); an offer row with a catalogue row's partner id under another key drops that catalogue row (like mergeYachtAndOfferExtras). Admin deployed BEFORE the backend (the old admin keyed on `e.externalId ?? e.key` and would have split NauSys twins). Review simulation: 13311 card 22 → 21 rows, pack once; 12284 card now matches /calculate (SERVICE PACK 26 / A.P.A. 26 twins dropped).
- MMK offer sync rewrites an existing offer extra's name (blank partner names ignored, also in the yacht sync).
- Brokers: re-login once (a stale token falls back to anonymous → no id → old behaviour) and re-add yachts already sitting in the offers cart (localStorage carts are not re-merged).
- Tests 418, same 31 pre-existing failures; Opus review SHIP_WITH_NITS (nits applied).

## 2026-09-27 — `/public/yachts/{slug}` returns `hasBookableFutureOffer` — ✅ LIVE cusma2 17:12 + cusma3 17:12 UTC (gate 0 sync lines; jar `c9718df7`, commit `517582e`, includes `e6e896a`)

Mario (27.9., via BOAT4YOU 3): boats with no future offer leave every listing and sitemap on all 7 sites; their page stays reachable as an inquiry form without price/calendar (no 404, no noindex); the web renders that from this field. Definition = the undated `/public/yachts` predicate (search-view row with date_from >= today, offer_status <> 'UNAVAILABLE'; CUSTOM rows without dates count), so page and listings never disagree; lags the view by <= 10 min; ~3 ms. The listing side needed no change: the undated predicate (419db41) already drops them (60/60 past-only boats checked) and boats with no offer at all are not in the view. Prod: 1,233 visible yachts false (859 without any future row + boats whose future rows are all UNAVAILABLE). Verified: 4788 Saxdor 270 GTO false, 1883 GOLDFINCH true. The 300 €/week placeholder floor is NOT part of it (owner decision 5 pending). Tests 418, same 31 pre-existing failures.

## 2026-09-27 — `/public/yachts?includeUnavailable=true` is admin only (agency names leaked anonymously) — ✅ LIVE cusma2 17:04 UTC (jar `1feeef38`); cusma3 17:12 UTC with jar `c9718df7` (commit `e6e896a`)

Found in the 26.9 unpriced-yachts analysis (point 12), confirmed on prod by BOAT4YOU 3: `GET /public/yachts?did=c-54&includeUnavailable=true` returned `agencyName` for anyone ("Butterfly Water Sports Croatia", "Euromarine charter", …) — owner rule: never show who runs a boat. The replacement path (`getYachtsForReplacement`, admin Create-Reservation wizard) now runs only for SYSTEM_ADMIN (1 user on prod, 0 MANAGER); everyone else falls through to the regular search, where YachtMapper already nulls agencyName/commission. Verified anonymous after deploy: 5 rows, agencyName null. Tests 418, same 31 pre-existing failures.

## 2026-09-27 — A partner charge renamed in place shows once on bookings (Transit log ×2 on 1441015/2027) — ✅ LIVE cusma2 14:27 + cusma3 14:27 UTC (jar `b4b4d1d1`, commit `ae64ce7`)

Mario: admin booking 1441015/2027 (MMK, Bali 4.2, yacht 8382) listed "Transit log" twice under "Obligatory - Paid at marina" and summed 900 € instead of 450 €. MMK renamed partner extra 6452581432503926 in place ("Transit Log (… cooking gas)" → "Transit log (… mooring fees for first and last night)"); offers + the booking carry the new name, `yacht_extras` kept the old one because the MMK yacht sync update branch never rewrote `name`. Admin and my-bookings merge booked extras with the catalogue by key (extras_id or name), so both showed. Price calc already merged by partner id — nobody was charged twice.

- `MmkYachtSyncService`: existing catalogue rows are renamed (normalized); the first product carrying the id names it, so per-product labels (69 yacht+id pairs) never flip daily. Next run 06:10 UTC renames ~969 rows on ~890 yachts (~850 extrasKey changes; a checkout spanning 06:10 can lose an optional selection on its next /calculate — accepted, narrow).
- `ReservationMappers` (admin + my-bookings `services`): a catalogue row with a booked row's partner id under another key takes the booked name + key, so the existing key merge shows it once. The partner id stays server-side — MMK ids end in the operator's company id (id % 10000 = agency, 1,206/1,206), so exposing them publicly was rejected in review.
- Emails (option/confirmation `ReservationEmailService`, `PaymentPendingNotificationService`, `OptionExpiryService` ×2): "available at the marina" also skips booked partner ids.
- NauSys: booked rows carry synthetic per-offer ids, so nothing changes there; catalogue vs offer name differences on NauSys are different endpoints' names, not renames.
- Rejected on the way (3 Opus reviews): exposing `externalId` on `YachtExtrasDto`/`ExtrasPriceDto` — would have switched admin Offers' dormant `e.externalId ?? e.key` key and split NauSys catalogue/offer twins in client offer e-mails, and leaked operator ids. Admin unchanged.
- Verified: 1441015 catalogue row 823573 → renamed → takes the booked key (shows once, marina 450); 1441012/2026 rows same key today, covered after the rename. Tests 418, same 31 pre-existing failures. cusma3 restart gated (0 sync lines in 10 min).

## 2026-09-26 — 26.9 audit fixes: charter facts like-for-like, canonical region names, landing scope, listing twin (V9_67–V9_69 + R__1_07) — ✅ LIVE cusma2 21:07 + cusma3 21:08 UTC (jar `467b275f`, merge `361065c` = fix27/backend + main d25eadd)

**✅ First run of the new facts code 27.9. 08:00 UTC:** 750 rows. Croatia c-54: activeBoats 3,668 = listing total; months Oct 2026–Sep 2027 (no partial September), no median < 300 €, "most expensive" July 2027, skipper median 1,400 €. Greece c-86: 3,124 = listing; cheapest Mar 2027, priciest Aug 2027. Valencia r-156: only Oct 2026 shown (thin data), no ranking claim. Facts block renders on EN/DE/HR landings. Regression 08:24 UTC: 0 new FAIL, F7 passes everywhere except the known Ionian pair (B13). `ops_charter_facts_backup_20260926` can be dropped after a week.

Commits `cd03919` … `e4e492e` (fix27/backend): B11 facts over 12 full months, 300 €/week placeholder floor + 12 % typo guard, month shown only with ≥50 % coverage, like-for-like panel; B01 canonical region names (syncs set a name only on create, partner spellings in `region_alias`, V9_68 pins r-3 Zadar / r-4 Šibenik / r-5 Split / r-193 Istria / Kvarner); B13 one name per base (`location_same_place`, V9_67), generic models out; B14 undated searches list a boat only if the row starts and ends in the destination or the boat is based there (same `DestinationScopeSql` for facets, relax, facts); B15 one offer per card on dated searches, no ≤ 0 / placeholder prices anywhere; B17 `yacht_listing_twin` (V9_69) — one card per physical boat, stable shown copy; B03 contract: yacht sitemap shards by id range.
Deploy (script `scratchpad/audit_backend_deploy.sh`): cusma3 quiet check 0 lines → stop scheduler → `charter_facts` backed up to `ops_charter_facts_backup_20260926` (683 rows) and emptied (the rows were computed by the OLD code with the false "September most expensive"; block hidden until the new code's 08:00 UTC run) → cusma2 swap (Flyway 9.67/9.68/9.69 + R__1_07 in 0.7 s, started 13.4 s) → checks: regions Zadar/Šibenik/Split/Istria / Kvarner, region_alias 198, location_same_place 2, yacht_listing_twin 577, `did=l-611&CATAMARAN&week` 48 all ACI Marina Dubrovnik, Greece week 3,128 → cusma3 start (up to date, 11.9 s).
Tests: 418, 31 failing = the same pre-existing date/env tests as on main. Coordinated with the parallel ADMIN session (no backend deploys in the window).
Rollback (both nodes together): `DROP VIEW public.location_view;` on cusma4 BEFORE starting the previous jar (old R__1_07 has 4 fewer columns), then `webservice.jar.prev`. Never run the old cusma3 jar after this migration (its region sync would rename r-3/4/5/193 again).
Open (ADMIN session): Mario's 26.9 rule — dated searches must not show boats without a real MMK/NauSys price; this jar shows them as "price on request" (CHOICE_NO_PRICE) instead of a fake price.

## 2026-09-26 — MMK phantom long / stale-option offers hidden by the free-offer reverifier — ✅ LIVE cusma2 19:15 + cusma3 19:16 UTC (jar `e780d657`, commit `dc06528`)

Mario: a Split catamaran search for 3–10.7.2027 opened Lagoon 43 EMA (12210, Adriatic Sailing) on 12.6–10.7 (28 nights, 33,772 €) instead of the week. MMK quotes nothing for EMA in 2027 (exact-date `[]` for every period, twice; 2026 periods quoted). The 21.9 hand sweep hid only FREE rows; EMA's 14/21/28-night rows were `OPTION` with no live option, which the read path demotes to FREE (OPTION_ECHO_GRACE_HOURS), so they won the LONGER match.

- `findShownFreeMmkCombos` (was `findFreeWeeklyMmkCombos`): future FREE rows of ANY length + OPTION rows with no live partner option/booking (same 48 h rule as the read path) and no `reservation_flow`. OPTION_WAITING untouched. Prod: 470,507 periods / 6,516 yachts, 1.7 s. 11,466 stale OPTION rows on 1,912 yachts (8,474 longer than 7 nights).
- `markPeriodUnavailable` (was `markWeekUnavailable`): exact dates only, same guards re-checked at write time. Rolled-back prod test: no option 9 rows, live option (expiry NULL / −47 h) 0, expired −49 h 9, overlapping RESERVATION 0, RESERVATION from checkout day 9, other dates 0.
- Groups are per yacht × year × shape (7-night / other), each sampled from its own periods; a phantom long block is found even while the weeks sell (adversarial review major). Breakers per shape: week groups keep the calibrated fleet abort (≥ 50 decided, ≤ 10 % empty, ≤ 10 % unknown); non-weekly groups are skipped (ERROR log) above 10 % empty once ≥ 50 are decided; per-agency 50 % cap per shape (a suspect week feed also blocks the agency's other lengths).
- Positive control 26.9: 114/120 random FREE 3–28-night rows quoted by the same exact-date call; the 6 empty ones start within 2 days or are a Sunday check-in MMK does not sell.
- Hidden period shown again without a quote → new two-day cycle (strike upsert resets `hidden_on`); zero-row flip → strike cleared, not counted as hidden. Combos copied off the JPA projection (heap on cusma3). Job log/counter: "periods hidden" (`hiddenPeriods`).
- Tests: reverifier 16/16 (+5); suite 383, same 31 pre-existing failures. Two Opus adversarial reviews (major + minors fixed; OPTION_WAITING gap intentional).
- **EMA cleaned by hand 19:02 UTC** (Mario's report, evidence = two exact-date passes + control): 11 periods (2026-12-19/26→2027-01-02, 2027-06-12…07-10 ×6, 2027-08-07→08-14/21/28) → UNAVAILABLE, backup `ops_offer_phantom_backup_20260926`, 0 reservation_flow. Undo: `UPDATE offer o SET status=b.old_status FROM ops_offer_phantom_backup_20260926 b WHERE o.id=b.offer_id AND o.status='UNAVAILABLE'`. Search verified: EMA gone, all 18 results carry 3–10.7; 3 spot-checked results quoted by MMK.
- First run with the wider scope: 27.9 13:30 UTC = first strikes only; hiding starts 28.9 13:30. Watch: `journalctl -u boat4youscheduler | grep "free-offer reverify"` (per-shape shares, agency escalations). cusma3 restart gated (0 sync lines in 10 min, 19:16, clear of the 20:40 MMK slot).

## 2026-09-26 — Every obligatory partner charge on an offer is charged (Skipper + Skipper's liability insurance) — ✅ LIVE cusma2 18:38 + cusma3 18:40 UTC (jar `15619260`, commit `f467270`)

Mario: "when there is a skipper, some agencies make the skipper's insurance obligatory too". `Offer.filterDuplicateExtras` and the yacht+offer merge collapsed obligatory OFFER rows sharing our fuzzy catalogue key (extras_id via Matchers; "Skipper's liability insurance" ⊂ Skipper) → one charged, which one by load order. Partner truth on all colliding future offers: MMK obligatoryExtrasPrice counts every row (512/512), NauSys advance total 38/38 determinable; all colliding rows have distinct non-null partner ids. Now: obligatory offer rows distinct by `OfferExtra.partnerIdentity()` (externalId, else name+price), stable order by id; `mergeYachtAndOfferExtras` (extracted, tested) never lets an obligatory offer row overwrite an earlier offer row (a second row under an owned key may only take a same-name yacht twin); NauSys re-quote skips services already in the price by name. **Kept as one (the dearest) — Mario's decision 26.9. (option A; the difference is settled with the agency):** rows whose names differ only by a season qualifier ("Nineteen APA / High season (30%)" vs "Low season I/II", 246 MMK offers — MMK lists and bills them all, e.g. 58,250 vs our 39,650). Known 1-offer exception: MMK "One Way Fee" 120 + 195 (MMK counts 195, we now 315).
Verified live before→after = partner: Skipper + insurance 6,199.15 → 6,549.15; NauSys Preparation fee ×2 3,342.88 → 3,862.88; Svalbard + Wintersailing skipper 11,930.88 → 18,259.30; APA seasons unchanged 39,650; Elda 1,730.35 and NauSys 446578 3,476.54 unchanged; 0 ERROR. Tests 375 (+10 new ObligatoryExtrasDedupeTests), same 31 pre-existing failures. cusma3 restart gated on 0 sync lines in 10 min (18:40, clear of the 20:40 MMK availability slot).
Also 26.9.: Mario confirmed card surcharge (5 %) applies to extras paid with the booking incl. APA, and Cat&Go "Skipper" (ADVANCE_PAYMENT, text says "only at the base") is paid in advance — no change needed.

## 2026-09-26 — NauSys offer obligatory ADVANCE_PAYMENT extras paid with the booking (V9_66) — ✅ LIVE cusma3 08:57 + cusma2 08:59 UTC (jar `2646abd1`, commit `eec8cdb`)

Second half of the V9_65 rule, for NauSys (signal = calculationType, not payableInBase). NauSys's advance total (`totalPriceWithExtras` = `offer.ext_total_price`, "total price to be paid in advance by the Agency to the Fleet operator, including … extras marked as advance payment") contains the offer's obligatory ADVANCE_PAYMENT extras on every future NauSys offer that has them: = `ext_client_price` + those items to the cent on 80,691 / 87,392, never without them (rest: extra taxes/percentages on top). SEPARATE_PAYMENT not in it (186,464 exact) → stays ON_SITE; INCLUDED_IN_PRICE already WITH_BOOKING. `fromNausysOfferObligatory` for `nausysOffer.obligatoryExtras` only; optional + yacht-level NauSys rows unchanged.
Data: 169,408 future rows (ADVANCE_TO_OPERATOR, payable_in_base=false, all PER_BOOKING) on 87,620 offers / 1,755 yachts; +450 € median per offer, p95 1,312 €, max 36,140 € (preparation/handling fees, comfort/charter packs, BVI/Bahamas taxes, damage waiver, insurance). 0 unknown calculationType logged in 14 days (both nodes) → backfill set = ADVANCE_PAYMENT set; 0 re-quote key mismatches (mergeNausysObligatory double-count risk); 48 offers with two rows on one catalogue key. **Undo snapshot:** cusma2 `/home/cusma2/backups/v966_offer_extras_payment_type_before_20260926.csv(.gz)`.
Order: snapshot → cusma3 swap 08:57 → manual UPDATE 9 × 20k (31 s, 0 left) → cusma2 swap 08:59 (API 200 after 17 s, V9_66 3.7 s). Verified: `/calculate` on 8 NauSys offers = client + ADVANCE items = NauSys's own advance total on all 8; SEPARATE items still at base; MMK (Elda 1,730.35) unchanged; existing NauSys bookings 100184/1441002/1441005 unchanged; 0 ERROR.
**⚠️ Incident:** the cusma3 restart at 08:57 interrupted the 08:40 MMK availability sync (~1,010 of ~1,063 agency-years done). The pre-restart check printed 1,307 sync lines in 10 min but did not stop the script. The remaining ~5 % keep availability from 20:40 UTC 25.9 until the 12:40 slot; the ShedLock lock (PT1H) expires 09:40, so nothing blocks the next run. Rule reinforced: the cusma3 sync-activity check must ABORT, not only print.
Flag for Mario: Cat&Go obligatory "Skipper" 2,450 € is ADVANCE_PAYMENT and inside NauSys's advance total (131/132 offers), but its description says "to be paid for only at the base with the skipper company" → partner data contradict themselves; we follow the billing. Open items as in V9_65 (admin offer e-mail wording, sister inquiry "Charter price"), plus pre-existing: mergeNausysObligatory puts promoted SEPARATE_PAYMENT items into our online total.

## 2026-09-26 — MMK offer obligatory extras billed with the booking are paid with the booking (V9_65) — ✅ LIVE cusma3 08:22 + cusma2 08:24 UTC (jar `b977a3de`, commit `8bd7be1`)

Rule (Mario): what the partner bills with the booking is paid with the booking here too — new reservations only, MMK first (NauSys next). MMK adds every obligatory offer extra with `payableInBase=false` into the reservation clientPrice (165/165 reservations of our account 2026-2027: clientPrice = basePrice × (1 − discount%) + those items, to the cent). `fromMmkOfferObligatory` classifies the offer's obligatoryExtras WITH_BOOKING (true → ON_SITE, 0 → INCLUDED); yacht-level MMK extras keep `fromMmkPayableInBase` (MMK bills only what the offer lists; yacht rows back the extras list on existing bookings).
Data: 341,102 future MMK offer rows (275,549 ON_SITE + 65,553 ADVANCE_TO_OPERATOR, all PER_BOOKING) → WITH_BOOKING; 187,751 offers, added per offer median 415 €, p95 12,059 € (APA, Greek VAT 6.5 %, cleaning, admin fees, BVI taxes). **Undo snapshot:** cusma2 `/home/cusma2/backups/v965_offer_extras_payment_type_before_20260926.csv(.gz)` (id, old payment_type).
Order used (review: one 341k-row UPDATE at cusma2 start could deadlock with a cusma3 MMK sync and keep the only API node down): cusma3 swap 08:22 (quiet, started 15 s, Flyway up to date) → same idempotent UPDATE by hand in 18 × 20k batches, `lock_timeout 5s` (39 s, 0 left) → cusma2 swap 08:24 (API 200 after 17 s, V9_65 applied in 2.2 s). **Node order matters:** an old-jar node rewrites these rows to ON_SITE/ADVANCE on its next MMK offer sync; if order slips, re-run the V9_65 UPDATE once both run the new jar.
Verified live: `/calculate` on 10 MMK offers → total = client_price + prepaid obligatory to the cent (= offer.total_price), payableInBase=true items (port tax, transit log) still at base; existing bookings 1441012/1441009 unchanged (stored extras at base, totals 1,081.10 / 12,920); 0 ERROR after restart.
Known / open: (1) 510 future offers (0.27 %) carry two obligatory prepaid rows on one catalogue key — `Offer.filterDuplicateExtras` keeps one (seasonal APA variants — probably right; "Skipper" + "Skipper's liability insurance" 350 € — undercharge vs MMK); pre-existing. (2) Card surcharge (5 %) and the voucher 1,500 € threshold now include these pass-through items. (3) Displays that read clientPrice as "advance": sisters' YachtFormExpandContent "To pay in advance", sister inquiry "Charter price" (= totalPriceEur), admin offer e-mail "payable separately / total on arrival". (4) 31 pre-existing unit-test failures (date-dependent PaymentPhases/NauSysDateTimeWrapper, Matchers, context load) — same 31 on clean HEAD.

## 2026-09-26 — Undated landings priced per week (`priceBasis=week`) + positive-price fallback — ✅ LIVE cusma2 23:13 UTC 25.9. (jar `5a2bddc2`, merge `175630d`); cusma3 ✅ 26.9. 07:52 UTC (quiet window, 0 sync lines, started 12 s)

`GET /public/yachts?priceBasis=week` (web sends it only on undated fetches): each yacht is priced by its cheapest bookable 7-night offer (from today, > 0 €, not RESERVED/SERVICE; typo guard: cheapest < 12 % of the dearest week → no price, card "Price on request"); unpriced yachts sort last. Default path (sister sites, admin, AI chat): the fallback MIN prefers positive prices (18 future 0 € offers existed). No migration; count query unchanged.
Review (adversarial, EXPLAIN on a 4× local copy): page query +12 % default / +36 % weekly, HashAggregate 17.9 MB, no spill; custom plans kept (`plan_cache_mode=auto` — never force_generic_plan). Prod: 0 offers with list price 0 and client price > 0 (the per-column MIN caveat is moot).
Live 23:16 UTC: API Greece `did=c-86` default = 1/2/3/7-day mix (Sofia 211/1 d) vs week = all 7 d (508, 509, 687 …); web Greece, Croatia × catamaran and Split Region landings show only "Price for 7 days".
Follow-ups (minor): compare the cheapest week with a typical week instead of MAX (3/11.8K yachts lose a real price to one dear holiday week); exclude 0-night rows (date_to = date_from) from weekly candidates.

## 2026-09-25 — Sea charter only: inland (river/canal/lake) vessels, operators and bases blocked in the sync (V9_63 + V9_64) — ✅ LIVE cusma2 17:51 + cusma3 17:52 UTC (jar `2c45c2f8`)

**Live check 17:55 UTC (prod DB):** Flyway 9.64; `location.inland` = 93 rows (all id+name guards matched); visible yachts at inland bases 0; visible yachts of river builders 0; 17 river/lake agencies inactive; visible fleet 13,187. `/public/yachts/17148` (Le Boat) → 400, web boat page 404. Same day, before the deploy: the 13 river agencies (15:51 UTC) and the 4 lake agencies + 28 lake-base yachts (~16:05 UTC) were switched off by hand (backup tables `ops_river_agency_backup_20260925`, `ops_lake_yacht_backup_20260925`); API restarted 15:53 to drop caches. Reviews: `REVIEWS_ENABLED=true` added to both env files 15:45 UTC (backups `*.env.bak-20260925-reviews`), first sweep 26.9. 09:10 UTC.

Le Boat "Caprice Comfort 51" was live on www.boat4you.com: since 5.7.2026 the MMK agency mirror auto-creates every
unknown company ACTIVE, and 13 river operators came in that way (their cruisers are MOTORBOAT / MOTOR_YACHT, so the
VesselType skip never caught them). The 13 agencies were switched off by hand in prod at 16:51 UTC (backup table
`ops_river_agency_backup_20260925`); V9_63 records that fix (idempotent, `AND active` → 0 rows on prod).

- `InlandVesselRules` (catalouge/utils): inland-only builders (Le Boat, Nicols, Pénichette, Linssen, De Drait, Gruno,
  houseboat/Hausboot, ...) + river-operator company names (le boat, riverly, canal/kanal, péniche, river, ...).
- MMK + NauSys yacht sync: a yacht from an inland builder is skipped (MMK SkipReason INLAND_VESSEL via shipyardId →
  our manufacturer name; NauSys via Model.manufacturer), and one already imported is set `sys_active=false` (no delete).
  The weekly inventory applies the same rule ("fali kod nas" does not count them).
- Agency mirrors (MMK + NauSys): a NEW company whose name matches the operator rule is created `active=false`,
  `sync_deactivated_by=NULL` (manual OFF, never re-activated by the mirror) + WARN "Created NEW agency ... INACTIVE".
  Existing agencies untouched.
- Lake charter (Mario, same day): 4 lake agencies (Ahoj Czarter 1335, New Port (Nowy Sztynort) 698, Okej-Czarter 1326,
  Waterfront Jachtcharter 1464) set `active=false`, `sync_deactivated_by=NULL` and 28 yachts of sea agencies at lake bases
  (Starsails @ Lemmer 1723, Aalsmeer/Leimuiden 1095, Yachthafen Rapperswil 1058, Marina di Navene 1309) `sys_active=false`,
  by hand in prod ~16:05 UTC. **V9_64** records it and keeps it: new column `location.inland` (NOT NULL DEFAULT false),
  set true for 93 river/canal/lake bases (80 checked in prod 25.9. + 13 river-operator bases from the 27.5. prod
  snapshot; each id guarded by its name — ids differ between DBs), the 4 agencies
  (id + name guarded, idempotent), and every `sys_active` yacht at an inland base → false. V9_63 got the same name guard
  (not applied anywhere yet).
- MMK + NauSys yacht sync: a yacht whose base location is `inland` is handled exactly like an inland builder
  (INLAND_VESSEL: not imported, an imported one switched off, counted in `inland=`); the weekly inventory follows
  (partner base ids of inland locations, one query per system). Operator rule: "Canal Yachting" (Corinth Canal, sea,
  agency 843) no longer matches; builders + Riverboating Holidays / "River Boat" and Nicols "Estivale" models.
- **🔴 Deploy only inside a quiet window with no sync running on cusma3** (07:50-08:00 / 13:00-16:15 / 17:50-20:35 /
  21:05-22:15 UTC; not 08:00-08:40 until the `Charter facts: … rows` line — CharterFactsJob reads `location` in one
  long transaction). V9_63 + V9_64 run with `lock_timeout 5s`; V9_64 holds ACCESS EXCLUSIVE on `location` until it
  commits. **cusma2 first** (applies V9_63 + V9_64; cusma3 is pinned to Flyway 1.43, and the new jar maps
  `location.inland` under `ddl-auto: validate`, so on cusma3 before V9_64 it fails startup), **then cusma3 immediately
  in the same window**: the old cusma3 jar has no inland rules and its yacht sync would set `sys_active=true` again.
  If Flyway reports `lock timeout`, restart cusma2 inside the window; the migrations are transactional and idempotent.
  Until deployed, the yacht sync (MMK 06:10 daily) brings the 28 hand-switched lake-base yachts back; V9_64 switches
  them off again.
- **After deploy:** check V9_63 + V9_64 applied on cusma2 (`flyway_schema_history`) and
  `SELECT count(*) FROM location WHERE inland` → 93 (all 93 matched on the 27.5. snapshot; fewer = a name no longer
  matches: find it by id). V9_64 switches off ALL active yachts at these bases, also those of the already inactive river
  agencies (not only the 28). The first MMK / NauSys yacht sync may switch off inland-builder or inland-base yachts that
  SEA agencies also list (WARN "Deactivated MMK|NauSYS yacht ... inland builder or base") — expected; check the counts
  (`inland=` in the MMK take-back line).

## 2026-09-25 — V9_61 charter facts + V9_62 review collection ✅ LIVE cusma2 13:50 + cusma3 13:56 UTC

Jar md5 `784f2919…` (HEAD `d562fc0`). Flyway 9.61 + 9.62 applied on cusma2 in 0.041 s; cusma3 "up to date" (deployed after
the 13:30 MMK free-offer reverify finished, 0 sync lines). Verified: reservation_review FKs = reservation, users, review_request,
self (no yacht FK); review_request 0, reservation_review 0, charter_facts 0; REVIEWS_ENABLED not set (sending OFF);
`/public/charter-facts?did=c-54` 404 (first nightly run 08:00 UTC 26.9.), `did=x-1` 400, `/public/reviews/request/abc` 404.

## 2026-09-25 — Review collection (V9_62) review fixes: off by default in prod, edits re-moderated, unsubscribe, admin re-send, no yacht FK — ⏳ BUILT, not deployed

Follow-up to the V9_62 entry below (same unreleased feature; V9_62 was edited in place — it was never applied
anywhere: not pushed, not deployed, local DB is at 1.89).

- **`REVIEWS_ENABLED` default false in application-prod.yml** (was true + a note to override it on both nodes). Why:
  sending is at-most-once and the raw token is never stored, so a mail sent before the web page exists burns that
  customer's request for good (UNIQUE (reservation_id, kind) blocks every re-send) — a missing env line at restart
  would do it to up to 200+200 customers at the 09:10 sweep plus every payment. Turn on = `REVIEWS_ENABLED=true` in the
  env file on cusma2 AND cusma3 + restart, once the page is live; flip the yml default in a follow-up commit then.
- **Customer edit (24 h window) → back to moderation:** the UPDATE now sets `status = 'NEW'` and clears
  `status_changed_at` / `status_changed_by_user_id` when the review had been moderated — replaced text or a withdrawn
  publish consent can no longer stay PUBLISHED.
- **Unsubscribe:** the requests honour `users.marketing_opt_out` (like the birthday mail) but had no way out. Both
  templates now show "Prefer not to receive e-mails like this? Unsubscribe" (9 languages + default) linking to the
  existing web page `{SERVER_HOST_PUBLIC}/unsubscribe/{users.unsubscribe_token}` (browser-side POST, scanner-safe), and
  every mail carries RFC 8058 `List-Unsubscribe: <{SERVER_HOST}/public/users/unsubscribe/{token}>` +
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (existing permitAll POST endpoint). No token → no link/headers.
- **Admin re-send** `POST /admin/reviews/requests/{reservationId}/resend?kind=BOOKING|YACHT` (SYSTEM_ADMIN, both
  nodes): one transaction — drops the unanswered request and mails a fresh link (timing rules skipped, base
  eligibility kept: confirmed, real, paid, not GDPR-deleted, not opted out). → 200 `{"outcome":"SENT"}` /
  409 `{"outcome":"DISABLED"}` (flag off) / 409 `{"outcome":"ALREADY_REVIEWED"}` / 404 `{"outcome":"NOT_ELIGIBLE"}`;
  nothing changes unless SENT. For customers whose link could not be used.
- **V9_62: no FK on `reservation_review.yacht_id`.** Creating an FK takes SHARE ROW EXCLUSIVE on the referenced table;
  yacht is written by the syncs for hours, so with `lock_timeout 5s` Flyway could fail and the node not start (on
  cusma2 = API outage). The column is informational (review hangs off reservation_id; admin list LEFT JOINs yacht).
  FKs to reservation / users stay (CASCADE for the spam purge). **🔴 Start this jar only inside a quiet window with no
  sync running** (07:50-08:40 / 13:00-16:15 / 17:50-20:35 / 21:05-22:15 UTC), cusma3 then cusma2 as usual; if Flyway
  still hits `lock timeout`, just restart inside the window (the migration is transactional + idempotent).
- **Not changed (explained):** the two SQL suites keep a hand-written minimal schema instead of the real Flyway chain
  — the chain cannot be replayed on an empty database (V9_18 inserts `location_region` for location 2029, a prod-only
  row); they now run on postgres:18-alpine (prod major) and every referenced column was cross-checked by hand against
  the migrations.
  **Tests (31, green):** ReviewCollectionIntegrationTest (9, PG18: + PUBLISHED review edited → NEW with stamp cleared,
  unsubscribe token reaches the mailer, re-send replaces the link / old token 404 / 48 h rule bypassed / refused when
  reviewed, opted-out, unpaid, unknown or disabled), ReviewControllersTests (5: + re-send status mapping, kind required),
  ReviewEmailTemplateRenderTests (4: + footer link in 2 templates × 9 languages, List-Unsubscribe headers, no token →
  no link), ReviewTokensTests (7), ReviewValidationTests (6).
  **After deploy:** `\d reservation_review` shows no yacht FK; flag off → `SELECT count(*) FROM review_request;` = 0.
  **Undo:** `webservice.jar.prev`; migration undo unchanged (see V9_62 entry).

## 2026-09-25 — Charter facts (V9_61) review fixes: 08:00 UTC slot, search-consistent boat count, skipper by extras_id, ?force, stale alert — ⏳ BUILT, not deployed

Follow-up to the V9_61 entry below (same unreleased feature, deploy both together). No new migration.

- **Schedule 04:20 → 08:00 UTC** (`CharterFactsJob.CRON = "0 0 8 * * *"`, zone UTC). Why: the NauSys nightly
  yacht + offer sync starts 23:20 and was measured at 6 h 39 m (`NausysSyncJob.runYachtSync`, PT10H lock), so 04:20
  sat in the middle of the heaviest write load of the day — the facts would read a half-refreshed offer grid while
  adding a 12-month window-sort/percentile scan (work_mem 128MB) on the cusma4 PG that cusma2 also uses. 08:00 is
  inside the 07:50-08:40 quiet window, after NauSys (~06:00), the 05:30 cleanup and the 06:00-07:20 MMK morning
  runs, so the facts are also the freshest. The old note "far from NauSys 23:00" was wrong.
- **Boats counted like search counts them:** search hides `UNAVAILABLE` rows (`YachtQueryingService`, every public
  caller), but a boat whose every week was UNAVAILABLE (MMK reverifier withdrawal V9_59, owner-blocked) still counted
  in activeBoats and all per-boat figures. Now `cf_week.has_bookable` (any non-UNAVAILABLE row in the yacht-week);
  did membership needs ≥1 bookable week based there; topBases counts only bookable bases; month / check-in figures use
  all weeks of MEMBER boats (their UNAVAILABLE weeks stay in the availability denominators).
- **Skipper = canonical Skipper extra:** `(ye.extras_id = 1 OR ye.name ILIKE 'skipper%')` — extras_id 1 is the site's
  "Skipper" key (R__1_04, "Skipper" anywhere in the name, e.g. "Professional skipper"), which the pricing/detail pages
  group by. SKIPPER_EXCLUDE and the 500-7000 EUR/week band unchanged.
- **`POST /admin/charter-facts/recompute?force=true`** (cusma3, SYSTEM_ADMIN) → 202 `{"status":"STARTED_FORCED"}`:
  bypasses the "fewer than half of the stored rows" guard for a legitimate large shrink (shorter
  `charter-facts.countries`, big agency blocked); the empty-result guard always applies. Without force: unchanged
  (202 `STARTED` / 409 `ALREADY_RUNNING`).
- **Stale alert:** after every daily run the job checks `max(computed_at)`; older than 48 h (or table empty) → ERROR
  `Charter facts are STALE: newest computed_at = …` every day until fixed (the refusal alone only logged once/day
  while the endpoint kept serving old facts indefinitely).
  **Tests (21, green):** CharterFactsComputeServiceTest (5, now PG18: fixture + yacht 19 all-UNAVAILABLE must not count
  anywhere, "Professional skipper" extras_id 1 counts, force replaces a shrink, force never overrides the empty guard),
  CharterFactsJobTests (5: + force passthrough, staleness 24 h/49 h/empty, cron = 08:00 UTC), controller 4, math 7.
  Mutation-checked: reverting the has_bookable filter or the extras_id match fails the suite.
  **After deploy (cusma3):** first cron run next 08:00 UTC, log `Charter facts: N rows …`; no `STALE` line.
  **Undo:** `webservice.jar.prev` (no schema change in this fix).

## 2026-09-25 — Review collection: booking + yacht review requests, magic-link form, admin moderation (V9_62) — ⏳ BUILT, not deployed

**Flag:** `application.reviews.enabled` now ships **false in application-prod.yml** too (see the "review fixes" entry
above) — nothing is mailed until `REVIEWS_ENABLED=true` is set on both nodes after the web page
`/[locale/]review/{token}` is live. With the flag off the endpoints and tables work regardless.

**What / why:** phase 1 of the review plan (memory `project_review_voucher_plan_future`), collection only — nothing
is displayed publicly, no vouchers/incentives. Two kinds per reservation:

- **BOOKING** (the Boat4You booking experience) — requested right after the customer's FIRST payment. Trigger:
  `ReservationPaymentRecordedEvent`, published by the Stripe webhook (`StripePaymentService.handleWebhookEvent`) and by
  the admin bank-transfer confirm / mark-paid endpoints; `ReviewPaymentListener` = `@TransactionalEventListener(AFTER_COMMIT,
fallbackExecution)` → virtual thread, so a rolled-back payment never mails and the payment flow can never fail
  because of the review e-mail. "First payment" = the earliest `paid_on` of the flow is < 48 h old (a pre-existing
  booking paying its balance never qualifies). Safety net: the daily job re-checks the last 48 h.
- **YACHT** (boat / charter) — daily job, charters with `date_to` 3-14 days ago, confirmed + at least one paid
  instalment. Sent to every eligible reservation, NOT only to those with a booking review (deviation from the plan's
  "upgrade path": the submit accepts a yacht review regardless and stores `booking_review_id` when a booking review
  exists). The 14-day lookback means the first run does not mail old charters.
- Eligible (both): `sys_status = RESERVATION`, `external_id` set and not `FICTITIOUS`, user not GDPR-deleted, user not
  `marketing_opt_out` (same courtesy-mail opt-out as the birthday mail); booking: no BOOKING request in the yacht-swap
  chain (an admin replacement booking with a carried-over instalment does not ask twice).
- **Once only:** `review_request` has UNIQUE (reservation_id, kind); the sender claims the row
  (`INSERT … ON CONFLICT DO NOTHING`) and only the claimer mails, in the same transaction; EmailService submits after
  commit. A template error rolls the claim back (retried next run); an SMTP failure is logged, not retried
  (at-most-once by design).
- **Job:** `ReviewInvitationJob` 09:10 UTC, scheduler node only (`@Profile("data-sync")`, ShedLock
  `reviewInvitations`, PT30M), max 200 per kind per run. Slot: after the 09:00 birthday mail, before 09:25 MMK reverify
  / 09:32 pre-charter / 09:40-09:50 trip jobs. Queries touch a few hundred reservations; nothing heavy on cusma2.
- **Link:** 32 random bytes, URL-safe base64 (43 chars); DB stores only SHA-256 hex; valid 60 days; reviews editable
  24 h after the first submit via the same link. URL = `SERVER_HOST_PUBLIC` + `/{lc}` (not for en) + `/review/{token}`;
  the e-mail also has 5 star links `…?rating=1..5` (form preselect).
- **E-mails:** `email/reviewRequestBooking` + `email/reviewRequestYacht` (redesigned header/footer family), in
  `users.language` (all 9 bundles: en/hr/de/fr/it/es/pt/pl/nl + default), recipient `"Full Name <email>"`, boat as
  Manufacturer + Model + Name (manufacturer not repeated when the model name starts with it). Inquiry e-mail untouched.

**Endpoints:**

- `GET /public/reviews/request/{token}` → 200 form context (kind, scoreKeys, reservationNumber, yachtId, yachtFullLabel,
  yachtMainImageId, dateFrom/dateTo, baseName/baseCountry, customerFirstName, locale, linkExpiresAt, submitted,
  editable, editableUntil, review = existing values only while editable), `Cache-Control: no-store`; 404 code 7001
  for unknown / malformed / expired link.
- `POST /public/reviews/request/{token}` body `{rating 1-5 (required), scores{…kind keys 1-5}, title ≤120,
text ≤3000, publishConsent, locale}` → 201 new / 200 edit / 400 (1102, field map) / 404 (7001) / 409 (7002, edit
  window over). Rate limit 10/min/IP (`application.rate-limit.public-review.*`) → 429.
- `GET /admin/reviews?kind=&status=&page=&size=` (SYSTEM_ADMIN, both nodes) → PagedModel newest first;
  `PATCH /admin/reviews/{id}` `{"status":"PUBLISHED|HIDDEN|NEW"}` → 200 / 400 / 404 (7003). Reviews arrive as NEW.

**GDPR:** `softDeleteForGdpr` now also anonymises the user's reviews (text/title/country/consent cleared, HIDDEN,
rating kept). Not yet in the Art. 20 data export (follow-up).

**Migration V9_62:** two new tables `review_request` + `reservation_review` (FKs to reservation ON DELETE CASCADE so
the spam purge keeps working, users ON DELETE SET NULL; yacht_id without FK — see fixes entry), CHECKs on kind/status/scores/text length, indexes for
the admin list. `lock_timeout 5s`, idempotent (IF NOT EXISTS). No existing row touched.

**Tests (28, all green):** ReviewTokensTests (7), ReviewValidationTests (6), ReviewControllersTests (4),
ReviewEmailTemplateRenderTests (3: both templates × 9 languages with the real bundles), ReviewCollectionIntegrationTest
(8, Testcontainers PG17: real V9_62 run twice, eligibility fixture, once-only sweep, event path, form create/edit/409/
expiry, yacht↔booking link, admin paging/moderation, GDPR anonymisation, purge cascade).

**After deploy (flag still off):** `SELECT count(*) FROM review_request;` = 0; `GET /public/reviews/request/xyz` → 404
7001; `GET /admin/reviews` → empty page. **After the web page is live:** set `REVIEWS_ENABLED=true` on both nodes,
restart inside the cusma3 window; next 09:10 UTC log line `Review request sweep: ReviewSweepResult(bookingSent=…,
yachtSent=…, failed=0)`; a Stripe payment logs `BOOKING review request sent for reservation N (lc)`.
**Kill switch:** `REVIEWS_ENABLED=false` + restart. **Rollback:** `webservice.jar.prev` on both nodes (tables stay,
unused). **Undo the migration** (only if wanted; deletes collected reviews):
`DROP TABLE IF EXISTS reservation_review; DROP TABLE IF EXISTS review_request;` +
`DELETE FROM flyway_schema_history WHERE version = '9.62';`.

## 2026-09-25 — Charter facts for landing pages (V9_61) — ⏳ BUILT, not deployed

**What:** landing pages (`/search?destinations=<slug>[&boatTypes=X]` → did c-/r-/l-) get a block of real inventory
facts. New table `charter_facts` (did, vessel_type NULL = all types, computed_at, payload jsonb; unique index on
`(did, COALESCE(vessel_type,''))`), filled daily by `CharterFactsJob` **08:00 UTC on the scheduler node only**
(`@Profile("data-sync")`, `@SchedulerLock("charterFactsRecompute", PT1H)`). _(Was 04:20 in the first build — wrong,
that is inside the NauSys nightly sync; corrected, see the "review fixes" entry above.)_
**Why precomputed:** cusma2 is the only API node (OOM history) — it only does one indexed row read per request.
**Endpoints:**

- `GET /public/charter-facts?did=c-54[&vesselType=CATAMARAN]` → 200 payload + `computedAt`,
  `Cache-Control: max-age=3600, public`; 404 no row; 400 malformed did (`^[clr]-\d{1,12}$`), missing did or
  unknown vesselType. `/public/**` is already permitAll — no security change.
- `POST /admin/charter-facts/recompute` (SYSTEM_ADMIN, **cusma3 only** like the other /admin job triggers) → 202
  `STARTED` (runs in background under the SAME ShedLock lock as the cron) / 409 `ALREADY_RUNNING`;
  `?force=true` → 202 `STARTED_FORCED` (skips the < 50 % guard, never the empty guard).
  **Computation (set-based, one transaction, temp tables ON COMMIT DROP, `SET LOCAL statement_timeout 600s`,
  work_mem 128MB, jit off):** population = search's (EXTERNAL, sys_active, agency active + not availability_blocked),
  7-night offers with date_from in [today, +12 months), pickup marina in the 12 promoted countries
  (`charter-facts.countries`, default BS,ES,FR,GD,GR,HR,IT,ME,MQ,SC,TR,VG). One row per yacht-week (BAREBOAT/CREWED/
  one-way rows collapsed: price = cheapest non-UNAVAILABLE client_price EUR, available = any row FREE). did membership by
  pickup marina: c- via country.code2, r- via location_region with the search's own-country guard, l- = marina + its
  same-name siblings (findMarinaIdsByFoldedName fold). Keys: every promoted c- with boats; r-/l- with ≥10 boats; per
  vessel type with ≥10 boats. Fields: activeBoats, priceByMonth (p25/median/p75, months ≥5 offers), cheapest/
  priciestMonth, availableShareByMonth + mostBookedMonth, skipperWeekly (name starts "Skipper", no training/cook/…,
  per night ×7, per week/booking/boat/amount ×1, plain "Skipper" row preferred, 500-7000 EUR/week plausibility band),
  obligatoryExtrasWeekly (per-boat fees only — per-person items excluded; deposit/waiver/insurance excluded; boats with an
  obligatory percentage APA or no extras rows left out), deposit min/median/max (EUR ≥100 only), checkInDays,
  medianBuildYear, topModels (8), topBases (8, c-/r- only), boatTypeMix (all-types rows). Any figure with n<5 is omitted.
  **Safety:** the table is replaced (DELETE + INSERT) inside the same transaction — readers see old or new, never half.
  A run producing < 50 % of the stored rows (e.g. offer table emptied by an incident) is rolled back, old facts kept,
  ERROR logged. Measured on the local DB copy (145k yacht-weeks, 11k boats, 939k extras): whole SQL ≈ 3.5 s,
  685 keys / 264 dids.
  **Migration V9_61:** new empty table + index, `lock_timeout 5s`, idempotent (IF NOT EXISTS). No data touched.
  **After deploy:** cusma2 → `GET /public/charter-facts?did=c-54` = 404 until the first run (expected). On cusma3
  trigger once: `POST /admin/charter-facts/recompute` (202) → log line `Charter facts: N rows (M dids) from … yacht-weeks`
  → `SELECT count(*), max(computed_at) FROM charter_facts;` → GET on cusma2 returns 200. Frontend wiring is separate.
  **Tests:** CharterFactsMathTests (7), CharterFactsControllerTests (4), CharterFactsJobTests (2),
  CharterFactsComputeServiceTest (5, Testcontainers PG17: real V9_61 + real aggregation SQL on a hand-checked fixture).
  **Rollback:** `webservice.jar.prev` on both nodes (the table stays, unused). **Undo the migration** (only if wanted):
  `DROP TABLE IF EXISTS charter_facts;` + `DELETE FROM flyway_schema_history WHERE version = '9.61';`.

## 2026-09-25 — Unusable/missing request parameters answer 400, not 500 (ce80f75, ✅ LIVE cusma2 07:57 + cusma3 07:58 UTC)

cusma2 review: ~300 "Unhandled exception" ERROR lines a day were `MethodArgumentTypeMismatchException`
— bots copy the srcset descriptor into image URLs (`/public/image/N?width=1080 1080w`) and send
`?startDate=null` — answered with 500. `ApiErrorHandler` now handles `MethodArgumentTypeMismatchException`
and `MissingServletRequestParameterException`: 400, code 1102 INVALID_REQUEST_PARAMETERS, WARN log,
body names only the parameter + expected type (`{width=must be a valid Integer}`), never the raw value.
Test `ApiErrorHandlerParameterTests` (2). No migration. Jar md5 `1d473b05…`; both nodes restarted inside
the cusma3 window (0 sync lines). Verified: bad width → 400 JSON, good width → 200 image/webp,
`startDate=null` → 400, health 200. Rollback: `webservice.jar.prev` on both nodes (= V9_60 build).

Housekeeping the same morning on cusma2 (no code): `/tmp/opencv_openpnp*` ×52 (3.2 GB — OpenCV/openpnp
extracts native libs into a new /tmp dir on every JVM start and never deletes it), two stale
`/tmp/api7*.log` dumps (330 MB) and 4 redundant old jars (880 MB) removed → disk 75 % → 57 %.
Permanent: systemd drop-in `boat4you.service.d/opencv-tmp-cleanup.conf` with
`ExecStartPre=/bin/sh -c "rm -rf /tmp/opencv_openpnp*"` (active from this restart on).

## 2026-09-24 — ⛵ Partner "One man crew" extras shown as "Skipper" (V9_60) — ⏳ BUILT (jar `603d7fa1`), deploy cusma2 + cusma3

**LIVE cusma2 24.9.2026 16:52 UTC** (jar md5 `603d7fa1…`, commits `a663e1a` + `763a5f2`; restart ~21 s to 200). Flyway `9.60` applied in 4.8 s. Verified on cusma4: 0 rows left matching `one[ -]*man[ -]*crew` in `yacht_extras`/`offer_extras`; renamed 36 × "Skipper (Caribbean)" (NSS Charter 1526) + 15 + 27 × "Skipper (+ boarding)…" (Marina Yacht Charter 1041). API `/public/yachts/12615` now returns `"Skipper (Caribbean)"`. Snapshot for undo: `cusma4:/home/cusma4/extras_one_man_crew_snapshot_20260924.csv` (78 rows, id + old name). 2 yachts now carry both a plain "Skipper" row and the renamed one (pre-existing partner duplication). **cusma3 (scheduler) LIVE 17:53 UTC** (same jar md5, window 17:50-20:35 UTC, 0 sync lines in the 3 min before; Flyway "up to date", Started in 12 s). Both nodes on `a663e1a`.

**Mario:** show it as Skipper everywhere. NSS Charter (agency 1526, `One man crew (Caribbean)`, 36 yacht_extras rows)
and Marina Yacht Charter (1041, `One man crew (+ boarding)` 15 + `One man crew (+ boarding) : Compensation due directly
to the Skipper, …` 27) sell the skipper under that name — the customer extras tab and e-mails did not read as a skipper,
and the admin offer builder (finds the skipper row by the keyword "skipper") did not find it at all.
**What ships (`fe7cd61`):** `ExtraNameNormalizer` (rule table `SYNONYMS`; "one man crew" — case-insensitive, whole
words, one-man / oneman / double-space variants — → "Skipper", prefix/suffix kept; any other name byte-for-byte
unchanged) applied at the 6 places the MMK / NauSys yacht + offer sync write a partner extra name. **The sync writes
the name only on INSERT** (updates match on externalId and leave the name alone), so V9_60 renames what already exists.
Matching (catalogue `match_keys`, externalId, payment-type keywords) still uses the raw partner name — every row keeps
the `extras_id` it has today (the rename never maps a row onto our Skipper label).
`NauSysObligatoryExtrasService` joins stored names back to NauSys services by name: it now also resolves the
normalized name (raw exact name wins) and returns the normalized name, otherwise a renamed NauSys skipper would stop
triggering "Damage Waiver obligatory once a Skipper is added" and the re-quote would count it twice.
**V9_60:** `yacht_extras` + `offer_extras`, one transaction, `lock_timeout 5s`, idempotent. Expected **78 / 0** rows.
Both UPDATEs are a sequential scan (no index on name) — offer_extras ~4M rows = a few seconds at startup. The SQL was
run on a throwaway PostgreSQL 17 with the unit-test strings (7 renamed, "Two man crew" / "Crew change" / "Skipper" /
"someone man crew" / NULL untouched, 2nd run UPDATE 0). Not touched: `extras` (our labels), reservation_extras and
external_reservation_extras (booking snapshots — old bookings keep "One man crew").
**Before deploy (cusma4), snapshot = the only exact undo:**

```sql
\copy (SELECT 'yacht_extras' AS t, id, name FROM yacht_extras WHERE name ~* '\mone[[:space:]-]*man[[:space:]-]*crew\M'
       UNION ALL SELECT 'offer_extras', id, name FROM offer_extras WHERE name ~* '\mone[[:space:]-]*man[[:space:]-]*crew\M')
      TO '/root/v9_60_one_man_crew_backup.csv' CSV HEADER   -- expect 78 lines (+ header)
-- same yacht already has a row with the new name? (0 expected; else the name-keyed dedupe shows only one of the two)
SELECT a.yacht_id, a.name, b.name FROM yacht_extras a JOIN yacht_extras b ON b.yacht_id = a.yacht_id AND b.id <> a.id
   AND lower(b.name) = lower(regexp_replace(a.name, '\mone[[:space:]-]*man[[:space:]-]*crew\M', 'Skipper', 'gi'))
 WHERE a.name ~* '\mone[[:space:]-]*man[[:space:]-]*crew\M';
```

**After:** `flyway_schema_history` has V9_60 success; the first query returns 0; admin offer builder on an NSS
Caribbean yacht finds the Skipper row. Web ISR pages may show the old name until their revalidate (≤ 1 h).
**Rollback:** `webservice.jar.prev` (V9_60 stays applied; the old jar shows the new names, only its NauSys obligatory
re-quote no longer finds a renamed NauSys row by name — restore the names too if that matters). **Undo the rename:**
NOT `regexp_replace(name, '\mSkipper', 'One man crew')` — that also hits genuine "Skipper" rows and the "…directly to the
Skipper" text. A re-sync after reverting the code does NOT restore it either (the name is only written on insert).
Restore from the snapshot by id: load the CSV into a temp table `b` and `UPDATE yacht_extras y SET name = b.name FROM b
WHERE b.t = 'yacht_extras' AND y.id = b.id` (same for offer_extras).
**Side effects (intended):** new bookings with these extras get "Skippered charter" in the charter agreement (it keys on
"skipper" in the extra name); an obligatory renamed row now shares the base name "skipper" with an obligatory offer
"Skipper" anchor in `ExtrasVariantResolver` (treated as a variant sibling, as for any other same-named row).

---

## 2026-09-22 — 👻 MMK free-offer reverifier (V9_59) — the permanent fix for phantom FREE weeks — ⏳ BUILT (jar `9c4b8e7f`), deploy cusma2 + cusma3

**Mario:** "podaci kod nas trebaju biti isti kao na MMK i NauSys". NauSys already flips a FREE week the partner stopped
returning to OPTION_WAITING (its own disappearance pass). MMK had nothing in that direction since `58d4623` (20.7.)
made the agency sweep upsert-only — hence the 21.9. one-off (10,375 rows on 189 yachts hidden by hand).
**What ships:** `MmkFreeOfferReverifyService` + daily job `runDailyFreeReverify` at **13:30 UTC** (data-sync profile,
ShedLock PT2H30M), the mirror image of the 09:25 UNAVAILABLE→FREE reverifier. Per yacht-season (FREE 7-night weeks
grouped by yacht + year): probe first/middle/last week with the ONLY trusted MMK call (exact dates, `flexibility=1`,
single `yachtId`); if all three are empty, ask every FREE week of that season. **A week is hidden only when two daily
runs on different days both found it empty** (`mmk_free_week_strike`, V9_59: first_empty_on → hidden_on, hidden_rows;
a quote deletes the row). **Evidence scope == write scope:** only the probed 7-night rows are flipped
(`markWeekUnavailable`), never a 14/21/28-night row that merely overlaps — that was the shape of the reverted 55de710.
**Breakers, all before any write:** < 50 decided seasons → abort ("partner unreachable"); > 10 % of probes failed →
abort ("degraded"); > 10 % of decided seasons empty (3× the 2.9 % baseline of 21.9.) → abort ("outage"); an agency
with ≥ 5 decided seasons and > 50 % empty is **escalated in the ERROR log and NOT hidden** (a broken feed and a
withdrawn list look identical — a human decides); ≤ 400 seasons verified per run; 100-minute wall-clock budget
(`getOffers` retries 3× with a 60 s read timeout, an outage must not run into the 16:40 availability slot).
**Back-off:** the 09:25 reverifier skips a week this job hid for 7 days (`findStaleUnavailableMmkCombos`), and clears
the strike when MMK quotes it again — so a published price list re-opens the week within a day, with the new price.
**Reviewed** by two adversarial Opus passes (`_mmk-phantom-audit-2026-09-21/` has the 21.9. probe data); every blocker
and major is in the version above (narrow write scope, two-day evidence, unknown-share and low-decided breakers,
per-agency cap, per-run cap, time budget, 13:30 not 13:15, strike table as audit trail). Tests: 8 behavioural
(`MmkFreeOfferReverifyServiceTests`) — the native SQL itself is not covered by a Testcontainers test (Docker is not
available here), so the first run is watched: `journalctl -u boat4youscheduler | grep "free-offer reverify"`.
**Expected first days:** day 1 ≈ 0 hidden, only first strikes (the 21.9. sweep already hid the known phantoms); the
strike table fills, day 2 hides what stayed empty. **Rollback:** `webservice.jar.prev`; the table is additive and
harmless to an older jar. Undo of a run: `UPDATE offer o SET status='FREE' FROM mmk_free_week_strike k WHERE
k.yacht_id=o.yacht_id AND k.date_from=o.date_from AND k.date_to=o.date_to AND k.hidden_on = <date> AND o.status='UNAVAILABLE'`.
**Not covered on purpose:** partial seasons where the first/middle/last week is quoted but a block in between is not
(under-hides, never over-hides); NauSys (has its own pass).

---

## 2026-09-21 — 👻 MMK phantom FREE weeks hidden (ops data fix, no jar) — ✅ APPLIED 21.9. ~23:20 UTC

**Mario:** LA MAR (yacht 11990, Ionian Charter) shows the whole of 2027 free with prices while the agency has not made
next season's price list. **Proven:** MMK quotes nothing for that yacht in 2027 (exact dates, `flexibility=1`, single
`yachtId`: `[]`; a 2026 week still returns an offer; a control yacht returns 2027 offers). Our 70 FREE 2027 rows carry the
2026 base prices - MMK once quoted 2027 from the old list, then stopped.
**Root cause:** since `58d4623` (20.7.) `syncOffersForAgency` is upsert-only (the agency-level yearly feed is incomplete
and its withdrawal pass had hidden ~81k bookable weeks), and `MmkStaleOfferReverifyService` only heals
UNAVAILABLE -> FREE. **Nothing covers FREE -> "the partner no longer sells it".** Occupancy is covered by the availability
sync, but a withdrawn price list is not occupancy.
**What was done (no deploy):** read-only probe from cusma2 of all 6,929 MMK yacht-years with FREE weekly offers from
2027-01-01 (same yacht selection as the reverifier): per-yacht year screen, then exact-date checks. 0 API errors.
199 yacht-years fully unquoted (163 in 2027, 36 in 2028) + 8 yachts partly; then EVERY free week of those candidates
checked by exact dates (5,909 calls: 5,088 empty, 821 quoted; the 88 empty weeks of mixed yachts confirmed twice).
One transaction on cusma4: **10,375 offer rows FREE -> UNAVAILABLE** (8,389 weekly + 1,986 multi-week rows overlapping a
confirmed-empty week) on **189 yachts**; no reservation flow referenced any of them. Most affected: Le Boat 43 yachts,
Yachtcharter De Drait 61, Adriatic Sailing 13, Ionian Charter 11, Ultra Sailing 9.
**Reversible and self-healing:** the rows match `findStaleUnavailableMmkCombos`, so the nightly reverifier (09:25 UTC)
turns a week back to FREE with the right price the morning after the agency publishes it. Evidence in
`ops_mmk_phantom_weeks_20260921`, row backup in `ops_offer_phantom_backup_20260921`. Undo:
`update offer o set status=b.old_status from ops_offer_phantom_backup_20260921 b where o.id=b.offer_id and o.status='UNAVAILABLE';`
Scripts + raw results: `boat4you-delivery/_mmk-phantom-audit-2026-09-21/`.
**Know-how:** the per-yacht `flexibility=6` year call is NOT reliable either (99 yacht-years answered 0 for the year while
exact weeks were quoted) - only exact-date calls are evidence. The reverifier's nightly load grows by ~5k combos.
**Still open (needs a jar):** this was a one-off sweep. The permanent fix is the mirror image of the reverifier - a
scheduled FREE-side check (sample weeks per yacht-season, escalate to every week on an empty sample, exact-date only,
HTTP 200 + empty confirmed twice, abort the run when an unusual share comes back empty). Not covered at all: yachts that
are quoted for part of a year and phantom for the rest, and NauSys.

---

## 2026-09-19 (b) — 🛡️ Booking path hardened after the morning incident (V9_58 + alert) — ✅ LIVE cusma2 22.9. 07:26 UTC (V9_58 applied in 9 ms) + cusma3 07:58 UTC; web `81d720fd` LIVE 07:28 UTC (BUILD_ID `y4M4frrDu66tpFBorIi6t`)

**Mario: "sredi da se to više ne događa".** Three Opus finders + nine skeptics went through everything that can fail a
booking after the partner option exists, then three reviewers attacked the diff (raw results:
`boat4you-delivery/_booking-failure-audit-2026-09-19/`). What ships in this jar:

1. **V9_58 — two partner-text columns lose their length limit** (`external_reservation_extras.name` 200,
   `reservation_extras.yacht_extras_key` 255 → unbounded `varchar`, the same type `reservation_extras.name` already is;
   entities drop `@Size`, `length = Integer.MAX_VALUE`). The second column was the same bug one step earlier and was
   **live**: `extrasKey()` falls back to the partner NAME when an extra has no catalogue mapping, 17 obligatory extras of
   264 chars on MMK yacht 7828 made **33 free future offers unbookable**, failing in `createReservationFlow` before the
   partner was called, with no log that named the yacht. The key is never cut (it is matched back against
   `extrasKey()`). The morning's `take(200)` stop-gap is gone: the full partner wording is stored again. No view, index
   or constraint depends on either column (checked on production); `SET LOCAL lock_timeout = '5s'` so a busy table
   makes the migration fail fast and retry on the next systemd restart instead of queueing every reader behind it.
   Mixed versions are safe both ways (old jar validates against the wider columns; rollback to `.prev` is fine).
   For up to ~20 min after the migration cusma3 may log `cached plan must not change result type` on
   `reservation_extras` reads until its connections recycle or it is restarted — expected, not a regression.
2. **Partner strings on `Reservation` are fitted at every copy site** — `externalStatus` (30, also AFTER the
   OPTION→RESERVATION replace, which makes it longer), `paymentNote` / `bankDetails` / `note` (2000, WARN names the
   flow; the full payload stays in `reservation.response`). `crewListUrl` over 1000 is **dropped, not cut** (a cut URL is
   a dead link; null is a state every consumer handles; ERROR log, admin can paste it). These columns are projected by
   `reservation_view`, which is why they are fitted rather than widened. `currency` is only trimmed/upper-cased.
   `externalReservationCode` is **never cut**: NauSys gets it back as the `uuid` on confirm (Stripe webhook, after
   capture) and cancel. A code over 100 is refused inside the adapter while the option can still be released.
3. **Both adapters release an option whose response cannot be mapped** (`ExternalOptionMappingException`). The booking
   controller can only release an option it holds a wrapper for, so a mapping failure used to leave the option
   dangling at the partner (yacht blocked there, FREE with us) until it expired. Released by id straight on the
   client, because `cancelOption()` maps the response again. Deliberately not in the controllers' allow-list → 502.
4. **`BookingFailureAlertService` — a failed booking mails every admin** with the customer's name, e-mail, phone, the
   yacht (Manufacturer Model Name), dates, price and the scrubbed root cause (PostgreSQL `Detail:` lines never leave).
   Two kinds, told apart in the subject: `🚨 Rezervacija NIJE prošla` (our defect) and `ℹ️ Partner odbio rezervaciju`
   (yacht gone at the partner — not an incident, but a customer who just tried to pay for a week is a lead). Also
   fires when `createReservationFlow` itself dies on a non-domain exception (no flow row exists → built from the
   request). Throttle: one mail per customer+offer per 30 min (armed only AFTER the mail is queued), 20 mails/hour
   overall, then the ERROR log line is the alert. No Reply-To on purpose (internal diagnostics in the body).

**Tests:** 12 new (`ExternalReservationExtraNameTests` 2, `MmkOptionMappingReleaseTests` 2,
`NausysOptionMappingReleaseTests` 1, `BookingFailureAlertServiceTests` 7), ktlint clean. Full suite: 31 of 268 fail, all
pre-existing and untouched (26 × `ReservationPaymentPhasesServiceTest` hard-coded 2026 dates, `NauSysDateTimeWrapperTests`
same, `Boat4youWsApplicationTests` needs DB credentials, `MatchersTests` 1, `ReservationOptionsCombinationProviderTests` 2).
**Web counterpart:** boat4you-web `81d720fd` (translated persistent failure panel ×9 locales + pre-filled quote modal).
Its copy promises "our team has been notified" — **deploy this jar first**, the web after.
**Not done, on purpose:** admin list of failed flows (the mail carries the contact), retry/idempotency guard on rapid
re-submits, `updateCrewListUrl` admin input length, fetch timeout on the web booking call, Stripe-path English strings.

---

## 2026-09-19 — 🚑 Booking hotfix: a partner extra name over 200 chars took the whole booking down (`87a674b`) — ✅ LIVE cusma2 10:12 UTC, ⏳ cusma3 parity

**Report (Mario, from a customer):** "ne može napraviti rezervaciju online". **Measured:** every `POST /public/reservations`
and `POST /secured/reservations` answered **502** from 09:49 UTC — 8 attempts in 14 minutes by two accounts (the customer
registered a second one hoping it would help), all on one yacht (Stelina, id 9020, MMK 2537536420000103140, offer 595995,
16.-23.10.2027). The API itself was healthy; the 502 was the application's own `BookingCreationException`.
**Root cause:** MMK returns the obligatory extra "Charter pack (Includes: end cleaning, bed linen/ bath towels, …)" with a
**222-character name**. `ExternalReservationExtra.name` is `@Size(max = 200)` / `varchar(200)`, so bean validation threw at
flush (`ConstraintViolationException … size must be between 0 and 200`), the booking transaction rolled back AFTER the MMK
option had been created, and the B2 compensation cancelled the option and set the offer back to FREE — which is why every
retry could create a fresh option and fail the same way. **Not a one-off:** 37,485 offer extras on 35,142 offers carry a name
over 200 characters (mostly Greek "Charter pack" descriptions), so any booking whose partner response echoes such an extra
failed the same way. Latent since the entity was written; nothing to do with the 18.9. deploys.
**Fix:** `ReservationMutationService.createReservationExtras` cuts the partner name to
`ExternalReservationExtra.NAME_MAX_LENGTH` (one constant now drives `@Size`, the column length and the mapper). The stored
name is a display-only copy of partner text. Test `ExternalReservationExtraNameTests` (3) pins both halves: the raw name
violates, the cut name validates. ktlint clean.
**Verified:** Flyway "up to date" (189), health 200 after ~16 s, offer 595995 FREE, no dangling partner reservation for the
yacht in `external_reservations`, no "Compensation: … failed" lines. A real test booking was NOT made on purpose (it would
place a real option on the customer's yacht) — confirmation is the customer's next attempt.
**Pre-existing, unrelated:** `ReservationPaymentPhasesServiceTest` fails 26 of 67 — hard-coded 2026 dates vs. logic that now
lands in 2027. Not touched here.
**Follow-up worth doing:** widen the column to `text` (no view depends on the table) so the full partner wording survives,
and alert on `BookingCreationException` — this was found by a customer, not by us (see `UPTIME-PLAN-2026-09-19.md`, 0.6).

---

## 2026-09-18 (b) — 🔐 TLS hygiene: http://www redirect, HSTS ×6 sisters, HTTP/2 on the API, certificate monitor — ✅ LIVE (ops only, no jar)

**Why:** Mario asked where we stand on TLS. Certificates themselves were fine (Let's Encrypt ECDSA P-256 everywhere,
certbot.timer on cusma1/2/5, cPanel AutoSSL for wp./mail./webmail./cpanel./autodiscover.), but four gaps were open.
Nothing here touches the application; all four are nginx/systemd changes. Scripts are versioned in `scripts/ops/tls/`.

1. **cusma1 — `http://www.boat4you.com` answered 404.** There was no port-80 server block for `www`, so it fell
   through to the default server (the apex and admin had one). Added a certbot-shaped block (`listen 80; listen [::]:80;`
   → `301 https://$host$request_uri`). Verified: http://www → 301, path + query preserved; `certbot renew --dry-run`
   on cusma1 succeeds for both lineages afterwards (http-01 follows the redirect).
2. **cusma5 — HSTS on the six sister vhosts** (boat4you already had it). `max-age=31536000; includeSubDomains` with
   `always`, in three places per vhost: the www 443 block, the apex 443 block (so the 301 carries it too) and the
   `/wp-content/` location — that location has its own `add_header` lines and nginx add_header inheritance is
   all-or-nothing, so the server-level header would silently vanish there. **croatia-yachting.com gets HSTS WITHOUT
   `includeSubDomains`** on purpose: `webmail.`, `cpanel.` and `autodiscover.croatia-yachting.com` serve a certificate
   for a different name (AutoSSL does not cover them; an `openssl s_client` without `-verify_hostname` reports
   "Verify return code: 0 (ok)" for these and hides it). Add `includeSubDomains` there once AutoSSL is fixed.
   Know-how: HSTS is host-bound, not port-bound — under includeSubDomains the plaintext cPanel ports (2082/2086/2095)
   of those zones stop being reachable in a browser that has seen the header; use the TLS ports (2083/2087/2096).
3. **cusma2 — HTTP/2 on api.boat4you.com** (`listen 443 ssl http2;` — nginx 1.24, the separate `http2 on;` directive
   is 1.25.1+). Verified ALPN h2, HTTP/1.1 clients still served, image cache still MISS→HIT.
4. **cusma3 — daily certificate monitor** `/home/cusma3/bin/cert_expiry_monitor.sh`, system units
   `cert-expiry-monitor.service` + `.timer` (08:31 UTC, Persistent). Let's Encrypt stopped sending expiry e-mails in
   June 2025, so a silently failing renewal would only show up as an outage. Per host (53: every public name of the
   7 sites + adriapixel + cusmanich.hr, plus `mail.boat4you.com:465`): certificate received, chain trusted, **name
   matches** (`-verify_hostname`), days left. The threshold is relative — alert under 1/4 of the certificate's own
   lifetime (22 d for today's 89-day certs; LE renews at 30 d, so an alert means renewal has been failing for a week) —
   because lifetimes are being cut to 47 d by 2029 and a fixed day count would rot. Silent when healthy; heartbeat
   mail every 7 days (elapsed-time stamp, not weekday); SIGTERM is trapped and mails partial results; the SMTP password
   goes to curl via `-K <(…)`, never argv; exit code 1 when a mail could not be delivered, so `systemctl --failed`
   shows it. SMTP settings come from `boat4youscheduler_vars.env`. Proven with a negative run (stubbed mail):
   hostname mismatch, expired, self-signed and unreachable hosts all reported; closed SMTP port → exit 1.
   Last result on the box: `/home/cusma3/bin/cert_expiry_monitor.last`. Manual run: `cert_expiry_monitor.sh --test`.

**How it was applied:** `scripts/ops/tls/nginx_apply.sh <sha256-of-editor> <tag> <mode>:<conf>…` — refuses to run
the editor as root unless its checksum matches, backs up to `/var/backups/nginx/tls-20260918/` (outside nginx's
include paths), edits with the idempotent `tls_edit.py` (asserts every anchor, exit 3 on mismatch), `nginx -t`,
reload, restores every file on any failure. Live confs were md5-checked against the fetched originals first (no drift).
**Rollback:** `cp -p /var/backups/nginx/tls-20260918/<file> /etc/nginx/conf.d/ && nginx -t && systemctl reload nginx`
(HSTS itself cannot be "rolled back" in browsers that saw it — that is its point; every HSTS host serves valid TLS).
**Open (needs Mario, cPanel login):** CAA records `0 issue "letsencrypt.org"` for the 7 zones (DNS lives on the cPanel
box; every certificate we serve, including AutoSSL's, is Let's Encrypt). Do it now that the monitor is live.
Not done on purpose: HSTS preload, certbot upgrade (apt 2.9.0 is fine), DNSSEC/SPF/DMARC (Pondi's area).

---

## 2026-09-18 — 🔴 cusma2 load incident: bounded image resizes + unknown `did` no longer widens (`245e577`) — ✅ LIVE cusma2 (20:27 UTC) + cusma3 (21:07 UTC)

**Incident (14.-18.9.2026):** the API node was kernel-OOM-killed 9× in 3 days (14.9. 05:54, 13:49, 14:33, 19:52,
21:40; 15.9. 00:38, 01:37, 15:58; 16.9. 21:04 — always at anon-rss ≈5.6 GB on a 5.8 GB box with -Xmx3072m) and kept
exhausting the Hikari pool (9,120 timeouts on 16.9., 1,133 on 18.9.; 5,892 in two hours on 15.9. evening → 5,691
SSR fetch failures on Europe Yachts alone). systemd restarts the API in ~20 s, so users saw bursts of 500/502.

**Root cause 1 — OOM = unbounded concurrent OpenCV resizes, NOT a leak and NOT the heap.** A 60 s tracer
(`/home/cusma2/memtrace.sh` → `memtrace.log`) caught the 16.9. kill: RSS 2,301 MB → 5,236 MB inside ONE minute while
NMT total (2,015 MB), heap committed (1,584 MB) and thread count stayed flat and the request rate was normal.
`GET /public/image/{id}?width=N` `imread`s the FULL original (up to 7360×5520 ≈ 120 MB of native BGR memory per
request) and nothing bounded how many run at once: nginx logged 100-459 image requests in flight in a single second
(91 at the 15.9. 01:37 kill). Tomcat allows 200. MALLOC_ARENA_MAX=2 (Part 5) had already cut the slow native creep;
this is the burst. Fix: `YachtImageService` takes a fair `Semaphore` around the OpenCV call only —
`application.images.max-concurrent-resizes` (env `IMAGE_MAX_CONCURRENT_RESIZES`, default **4**; cusma2 has 2 cores),
waits at most `application.images.resize-wait-ms` (env `IMAGE_RESIZE_WAIT_MS`, default **2000**), bounds the parked
threads to 8 per permit (32) and otherwise sheds with **503 + `Retry-After: 2`** (`ImageResizeBusyException`, error
code `IMAGE_RESIZE_BUSY` 1603). One throttled WARN per minute with the cumulative count ("Image resize gate
saturated…"). `server.tomcat.threads.max: 200` is now pinned explicitly (it is Spring's default — documentation, not
tuning). Paired ops change on cusma2 (same day, live before this jar): **nginx disk cache for `/public/image/`**
(`proxy_cache b4yimg`, 5 GB, `proxy_cache_lock on`, serves stale on 5xx; backup `boat4you.conf.bak.pre-imgcache`) —
each (id, width) variant reaches the JVM once, so the gate should rarely engage. Know-how: a shed image is a broken
`<img>` for that page view (browsers ignore Retry-After; CDNs and crawlers honour it) — if the WARN fires steadily,
raise the permits via env before anything else.

**Root cause 2 — DB load: an unknown `did` WIDENED the search to the whole catalogue.** `did=l-l-19` returned 897
yachts (all HR catamarans) instead of the 2 in marina l-19; `did=l-9999999` returned all 13,609 in 2.3 s.
`YachtDistributionService`/`YachtRelaxSuggestionService` already answered zero rows; the search path did not.
Europe Yachts + Croatia Yachting double-prefixed the marina id on every yacht detail render (`l-` + an id the detail
endpoint already returns prefixed) → **216,955 such whole-catalogue queries in 5 days** on a 2-core DB. Fix:
`YachtQueryingService.resolveSearchDidScope()` — tokens supplied but none resolves → `cb.disjunction()` (verified in
Hibernate 6.6 sources that it renders a real `1 <> 1`, not an empty junction that gets dropped); some resolve → as
before; the admin replacement flow returns an empty page for the same case; one-char tokens no longer 500. A throttled
WARN ("unresolvable did …") is the only signal that a landing page went blank because of a stale id.
**Audit before deploy (lead):** all 24 country and 65 of 68 region tokens seen in 2 days of production traffic
resolve; the three that do not are `r-1`, `r-2` (174 requests, old crawled URLs + a hard-coded Istria `r-1` in
Croatia Charter's itinerary extras → fixed to `r-193`) and `r-191` (2 requests). Web-side fixes ship separately
(see the six sister repos + boat4you-web, same date).

**Tests:** `YachtSearchDidScopeTests` (7), `YachtImageResizeGateTests` (5; cap, waiter bound, timeout → busy,
permit released on failure) + the two warm-policy classes — 22 green; ktlint clean on touched files.
**Deploy:** cusma2 (API) → verify `X-Cache-Status`, a resized image, `did=l-l-19` → 0 rows, `did=l-19` → 2;
cusma3 in a safe window for jar parity (no scheduler behaviour change). Rollback `webservice.jar.prev`.
**Outcome (first hours):** jar md5 `f760ad8a…` on both nodes; cusma3 restarted idle at 21:07 UTC, Flyway "up to date"
(189 migrations). Malformed `did` requests 40-55/min → 0 after the sister deploys; catalogue fetches 1,772/10 min → ~0;
API RSS ~1 GB, 0 Hikari timeouts since the restart; the gate shed 17 of ~700 images in the first 10 minutes while the
nginx cache was cold, none after.

## 2026-09-13 — 📧 Users: resend the sign-up invite (`9401056` + `75e1e81`) — ✅ LIVE cusma2 19:20 UTC

**Why (Mario):** a guest paid through the booking flow but never registered. The booking flow
invites them once (`ReservationFlowMutationService` → `UserInviteService.inviteUsers`) and the
link expires after 7 days (`application.invites.user.expiration`), so there was no way back in.

**1. `forceEnglish` is now an optional query param on `PUT /users/invite/{ids}`** (openapi +
`UserController`). Omitted → `true`, i.e. the historic admin behaviour is untouched (Mario rule
3.5.2026: admin invites are team/operational comms and stay English). The new admin
"Resend invite" button passes `false`, so a paying guest gets the mail in the language they
booked in. No new migration; `inviteUsers` already regenerated code + timestamp on every call
and only refuses `ACCEPTED`, so a resend also revives an expired link.

**2. Fixed while wiring it up — the invite locale followed the REQUEST, not the recipient.**
`resolveEmailLocale(user.language, forceEnglish)` falls back to `LocaleContextHolder` when the
user has no language stored. On the booking-flow invite that is the guest's own request, so it
was harmless; on an admin-triggered resend it is the ADMIN's `Accept-Language` — a Spanish
customer would have received a Croatian e-mail. `UserInviteService` now resolves strictly from
`user.language` with an English fallback, which is what its own KDoc already promised. This
matters in practice: 20 of 22 active users have no language stored.

All 9 locales carry `userInvite.subject` and the template is fully localised, so sending in the
recipient's language is safe.

**Deploy:** jar `8000458cca410b68394b908c3d5e66fd` → cusma2 only (API-only change; the scheduler
never serves `/users/**`). Restart 19:20 UTC, Flyway "up to date", API 200 after 27 s, no ERROR.
`PUT /users/invite/34?forceEnglish=false` unauthenticated → 403 (route + param accepted).
Rollback `webservice.jar.prev`. **cusma3 is intentionally one commit behind** — fold this jar in
with the next scheduler deploy (safe windows 07:50-08:40, 13:00-16:15, 17:50-20:35, 21:05-22:15 UTC).

## 2026-09-05 — 🔎 Part 5: search served from the DB, freshness via the scheduler (merges 89d8201 + 6c4bac9; branches part5/search e3aee6c, part5/nearterm 83a12f3+7330a3e; + e02f9a1 past-date/in-flight filters) — ✅ LIVE cusma2 (11:09, 2nd cut 11:21 UTC) + cusma3 (13:05 UTC)

**Why (Mario, 5.9.2026):** "We already pull prices nightly, availability 3×/day and options — why does every search ask
the partner again? Show it from the DB." Verified before deciding: the nightly stores, per agency, every interval
generated by ReservationOptionsCombinationProvider (always Sat→Sat 7 d, Sat→Sat minimalDuration, every allowed
checkin/checkout pair at minimalDuration) 18 months ahead; the search prefers an exact-range offer and otherwise falls
back to the earliest overlapping one (yacht still shown); the final price is re-quoted at booking. The per-search "warm"
(YachtController → ExternalSyncService.syncYachtOffers(start,end,locations) → NauSys /freeYachtsSearch + MMK /offers,
3 h marker) ran 1,362×/day and failed 681× (357 NauSysRateLimitedException after 6 attempts + 150 NauSys 502). Its
429s were spread over all 24 hours — it was user traffic, not the nightly, burning the NauSys quota.

**1. Search path (`SearchWarmPolicy`, `YachtController`, `ExternalSyncService`)**

- Weekly ranges (duration % 7 == 0, any start day: 7/14/21/28 d) fire NO partner warm at all — neither NauSys nor MMK.
- Non-weekly ranges keep the live async warm. New: the NauSys leg no longer swallows failures; a 429 / 5xx / timeout /
  ExternalSystemException parks the (dates, NauSys countries/regions/locations) request in the new table
  **`nausys_search_sync_retry` (V9_55)** with ONE WARN, the MMK leg still runs, the 3 h marker is still saved (no
  hammering). 4xx → ERROR, no enqueue. When none of the searched `did` tokens maps to a NauSys location the NauSys call
  is skipped entirely (previously an UNFILTERED world-wide freeYachtsSearch — the single heaviest wasted call).
- Per-yacht warm on the yacht page and the booking flow are unchanged.

**1b. Two more warm filters (added 11:30 UTC after watching 5 min of live traffic):** 226 of 365 dated requests
carried a start date in the PAST (stale sister-site/crawler URLs) and one paginated 12-day sister search fired the
identical warm 5× within a second. `SearchWarmPolicy.shouldWarm` now also requires `start >= today`, and
`ExternalSyncService` keeps an in-JVM set of in-flight warm keys (same hash as the 3 h marker) so a duplicate that
arrives while the first is still running returns immediately. Together with D1 this removes ~90 % of the remaining
non-weekly warms (measured: 43 past + 4 duplicates of 53 non-weekly warms in 5 min).

**2. Search retry drain (scheduler only, `NausysSyncJob.runSearchRetryDrain`)** — cron `0 5,20,35,50 * * * *`,
ShedLock `nausysSearchRetryDrain` PT10M, ≤25 rows oldest `next_attempt_at` first, back-off 15 min × attempts, give up
after 6 (WARN), stops early on a 429 or after 5 min, skips (DEBUG) while another NauSys job holds `nausysBusy`; also
chained after the agency-queue drain in the nightly/backup runs. Attempt accounting: the failed live warm counts as
attempt 1 → 1 live + 5 scheduler replays.

**3. Near-term intraday refresh (scheduler)** — NauSys `runNearTermOfferRefresh` cron `0 40 10,16 * * *`, ShedLock
`nausysNearTermOfferRefresh` PT3H, waits ≤30 min for the gate (else skips), same per-agency offer grid capped at
today+84 d via `yachtOfferSync(horizonEnd)` (null = nightly behaviour, byte-identical), then drains the agency queue;
marker `SCHEDULED_NAUSYS_NEAR_TERM_OFFER`; one summary INFO line with 429 count. MMK `runNearTermOfferRefresh` cron
`0 50 10,16 * * *`, ShedLock `mmkNearTermOfferRefresh` PT2H, one `/offers` call per company bounded today..today+84 d,
responses clipped to the window before upsert (nothing outside the window is touched); new `MmkRequestStats` 429
counter. Both estimated ≈1 h — measure and record.

**4. Locks and gate** — `nausysYachtSync` lockAtMostFor PT7H → **PT10H** (run measured 6 h 39 m); STARTED_MARKER_FRESHNESS
8 h → 10 h to match. The nightly and backup runs now WAIT up to 15 min for `nausysBusy` (shared `acquireGate` helper)
instead of skipping — without this the :20 search drain could win the gate at 23:20:00 and cancel the whole night.

**5. Log discipline (D8)** — nightly per-group/per-slot "Doing sync" INFO → DEBUG, MMK per-batch INFO → DEBUG, MMK nightly
"took" line now carries the run summary. Known leftover: `Marked offer X as SYNTHETIC_DISAPPEARANCE` per-row INFO will
now fire 3×/day — demote in a follow-up if noisy.

**6. cusma2 memory (ops, not code)** — kernel OOM killed the API 2.9. ×2 and 4.9. 13:28 (RSS 5.6 GB with -Xmx3072m =
native growth, ~1.3 GB/18 h; heap is not the problem). `boat4you_vars.env` (backup `.bak-20260905-part5`) now has
`MALLOC_ARENA_MAX=2` and `JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary`; active from this restart. After 48 h:
`jcmd <pid> VM.native_memory summary` shows the native consumer. Do NOT raise Xmx.

**Tests:** 78 new/changed tests green on the merged tree (SearchWarmPolicy, YachtController warm policy,
ExternalSyncService search warm, search retry queue + Testcontainers repo, async drain, NausysSyncJob gating/near-term/
lock assertions, MMK near-term, interval provider). Full suite: see below.

**Deploy order:** cusma2 first (applies V9_55; restart also activates the memory flags — expect "Picked up
JAVA_TOOL_OPTIONS" in the journal), health `GET /public/settings/card-surcharge` 200; then cusma3 ONLY in a safe window
(13:00–16:15 UTC today; from now on 10:40–≈12:00 and 16:40–≈18:00 are near-term refresh windows). Rollback = `webservice.jar.prev`
(V9_55 is additive; old code ignores the table).

**Deployed:** cusma2 2026-09-05 11:09 UTC (V9_55 applied, "Picked up JAVA_TOOL_OPTIONS", MALLOC_ARENA_MAX=2 in environ, NMT baseline) and again 11:21 UTC with the past-date/in-flight cut (jar md5 d4ef8466093d58726b7e92e930b7ebf4; rollback chain `.prev` = Part 4 jar, `.prev2` = first Part 5 cut); cusma3 2026-09-05 13:05 UTC in the 13:00-16:15 gap (MMK availability finished 12:53), started in 16 s, Flyway "up to date", env verified, no WARN/ERROR. Measured effect on the API node: live partner warms from search ~20/min → 3.6/min (weekly rule) → 0-1/min (all rules), 194 dated+did requests in 5 min without a single partner call; YACHT_SEARCH markers per 10 min 69 → 1-13.

**Verify after 24 h:** cusma2 async warm runs ≈ only non-weekly ranges (expect −70 %+ vs 1,362/day) and
"Error while syncing NauSYS yac" ≈ 0; `nausys_search_sync_retry` drains (`select count(*), max(attempts)`); near-term
summary lines at 10:40/16:40 (NauSys) and 10:50/16:50 (MMK) with durations; total NauSys 429 down on both nodes;
nightly still completes and ends ≤ 06:30; `Skip absent-reconcile`/MMK wire/leak counters stay 0.

## 2026-09-03 — 🔄 Part 4: sync reliability + data completeness (merge fb90905; branches part4/nausys 7b2897f, part4/matview 5af2997, part4/txhikari 388ea24, part4/logging 9d6d316) — ✅ LIVE cusma2 (2.9. 23:57 UTC) + cusma3 (3.9. 07:53 UTC)

Goal (Mario): "sync must run undisturbed and we must have ALL the data". Fleet-audit backend findings, each
investigated read-only against prod logs/DB first, then implemented in 4 isolated worktrees and merged.

**1. NauSys resilience + data completeness (`domains/external/nausys/**`, V9_54)**

- Company 1981 (Set Sail) failed every night since 28.8: NauSys sends `calculationType: INCLUDED_IN_PRICE`, our
  generated enum only knew ADVANCE_PAYMENT/SEPARATE_PAYMENT → whole response unparseable → 0 intervals synced,
  449 offers frozen at 2026-11-23, no 2027 season. Fix: spec enum extended + a dedicated NauSys ObjectMapper that
  maps ANY unknown enum string to null with a once-per-JVM WARN (12 generated enums were equally fragile).
- 429 storm (cusma2 6-13k/day, cusma3 200-400/day; agency 132 Dream Yacht = 692 yachts aborted 2 nights running):
  retries were nested (interceptor 5 × @Retryable 3 = up to 18 HTTP attempts, ~3.7 min sleeps, Retry-After ignored).
  Now ONE retry layer: interceptor honours `Retry-After`, ladder 1/2/5/10/20 s + jitter, throws typed
  `NauSysRateLimitedException` (extends ExternalSystemException → friendly EXTERNAL_* to clients) when exhausted;
  `@Retryable` on getFreeYachts/getFreeYachtsSearchForAsync has `noRetryFor` it. WARN only on first-of-chain and
  exhaustion (intermediate retries INFO). `NauSysRateLimitStats` counter logged per sync run.
- Per-JVM concurrency gate (fair Semaphore) on bulk endpoints (freeYachts, freeYachtsSearch, allYachts,
  getOccupancyByYear); booking endpoints bypass. **Env: `NAUSYS_MAX_CONCURRENT=2` on cusma2, `=1` on cusma3**
  (`NAUSYS_GATE_WAIT_MS` optional, default 20000). Fleet total 3 in-flight vs the empirically failing 7.
- Per-interval failure isolation: a failed week no longer aborts the agency; the interval is upserted into the new
  table **`nausys_offer_sync_retry` (V9_54)** and drained at the end of the nightly run and at the 06:15/10:15/15:15
  backup slots (15 min × attempts backoff, give up after 6 with ERROR). Survives scheduler restarts.
- Availability sync: 429/5xx are no longer a PartnerAccessGuard strike (a transient could pause an agency 24 h).
- Location-warm TTL 1 h → 3 h (8.8-22k freeYachtsSearch/day for only 1.8-2.9k distinct requests). Prices on warmed
  searches may lag up to 3 h; availability is live from external_reservations.
- **Schedule (UTC, effective after the cusma3 restart):** nausysCatalogueSync 01:00→**23:00**, nausysYachtSync
  01:30→**23:20** with lockAtMostFor PT4H→**PT7H** (runs 5-5.7 h; the old lock expired mid-run); the first
  availability pass is **chained** after the offer sync (the 04:20 slot that ran in parallel with it is removed),
  others 10:20/16:20/22:20; deleteExpiredReservationsAndOffers 06:00→05:30; availabilityIntegrityDetector
  06:40→09:55; mmkStaleOfferReverify 09:15→09:25; consistencyVerifier SUN 09:30→10:00. In-JVM `nausysBusy` gate
  makes NauSys jobs sequential; backup slot skips a second full sync while the nightly one is still running.
  README_PROD.md job table regenerated. **cusma3 safe restart windows (new timetable): 07:50-08:40, 13:00-16:15,
  17:10-20:35, 21:05-22:15 UTC.** "06:15 backup" in old notes = NauSys backup-sync cron, NOT a DB backup.

**2. yacht_search_view refresh (cusma4 load) (`domains/catalouge/**`, R__1_03)**

- Every 5 min REFRESH CONCURRENTLY diffed 1.7M rows with work_mem 32 MB → ~1.8 GB temp files per run,
  **530 GB/day of temp writes**, 288 × ~50 s/day; the admin on-demand refresh from cusma2 has been failing since 9.7
  (1 GB temp_file_limit). Now `YachtSearchViewRefresher` runs the refresh with session `work_mem=768MB`,
  `lock_timeout=180s`, `jit=off` (diff measured to fit in 741 MB → zero temp), cron **/10 with a change-aware skip
  (pg_stat counters of the 8 source tables), on-demand path reuses it.
- R__1_03 (repeatable → re-runs on the first cusma2 start): dead correlated `count(*) FROM yacht_extras` removed from
  recommended_score (1.7M index probes / 73M heap fetches per refresh; column unused since the agency_recommended
  sort — build 23 s → ~4 s); head/tail rewritten to **build-and-swap** (`_build` relation + indexes built first,
  then DROP+RENAME inside the transaction — readers block only ms instead of failing at lock_timeout for ~45 s);
  two dead indexes dropped (offer_status_idx: 4 scans/12 d, agency_id_idx: 12 scans/12 d). Verified on prod:
  no dependent views on the matview; owner boat4you_owner, GRANT SELECT to boat4you_app kept.

**3. Transactions vs partner HTTP (`ExternalSyncService`, `ReservationSyncService`, retryable clients)**

- The only confirmed prod leak/kill source: per-yacht warm held a read-only JPA tx + advisory-lock connection across
  the MMK/NauSys call (idle-in-transaction kills at ~01:00 nightly). Now: short read tx resolves the target, partner
  call runs tx-free, writes stay REQUIRES_NEW. Inert `@Transactional` on `processReservation` removed (self-invoked).
  Audit rows no longer wrapped in an extra TransactionTemplate (2 connections → 1 per audit).
- JDBC URL accepts `${DB_JDBC_PARAMS:}`. **Env: cusma2 `DB_JDBC_PARAMS=?socketTimeout=300&tcpKeepAlive=true`
  (NOT cusma3 — REFRESH may run 400 s); cusma3 `SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD=180000`
  (181/182 "leaks" were the >60 s refresh).** Stale "5 min" comments → 180 s (role idle_in_transaction timeout).

**4. Logging / PII (`ExternalAvailabilityReconcileService`, MMK services, `LogMasking`, `ApiErrorHandler`)**

- "Skip absent-reconcile … ZERO reservations" (4,660 WARN/day) → outcome enum + ONE run-summary INFO line per run
  (agencies, calls, empty per year with ids, unmappedNonEmpty, removed, breakerTripped); WARN only for the two real
  signals (partner rows but none mapped; breaker tripped — now WITH the agency id). Delete semantics untouched
  (the EMPTY-guard protects 4 flapping MMK companies).
- MMK per-request wire log (59 % of the scheduler journal, POST bodies with client names) OFF by default; **env
  `MMK_WIRE_LOG=true`** re-enables for incidents (bodies of POST/PUT redacted to byte counts).
- PII: inquiry acknowledgement log masks the e-mail; `service_call.request_body` for NauSys createInfo drops
  email/phone/mobile/passport/birthday/address/zip/skype/instagram (name/surname/countryId kept).
- Per-row INFO/WARN (offer flips, synthesized disappearances, image failures, yacht skips, unpriced offers) → DEBUG
  with counters in the run summaries; expected client 4xx (yacht not active etc.) → INFO with URI; taskExecutor
  saturation WARN throttled to 1/min with cumulative count.

**Tests:** 51 new (deserialization, retry interceptor, gate, retry policy reflection, per-interval isolation + queue
drain, NausysSyncJob gate, Testcontainers repo + refresher, tx-boundary Testcontainers, reconcile outcomes,
summaries, masking) — all green. Full suite: 185 tests, 31 failures — all PRE-EXISTING and unrelated (date-dependent
ReservationPaymentPhasesServiceTest ×26, ReservationOptionsCombinationProviderTests ×2, NauSysDateTimeWrapperTests,
MatchersTests, Boat4youWsApplicationTests context needs DB creds); see follow-ups.

**Pre-deploy baseline (cusma4, 2.9. 23:20 UTC):** temp_files 127,212 / temp_bytes 6,823,181,029,017; yacht_search_view
seq_scan 170,356, n_tup_ins 2,112,776 / del 1,961,957, dead 173,924; idx_yacht_extras_yacht_id idx_scan 5,686,314,327;
matview 1587 MB, 11 indexes; agency 1981: 449 offers, max date_from 2026-11-23. Journals 24 h: cusma2 6,984 "NauSys
429" lines / 397 exhausted / 367 saturated; cusma3 200 × 429, sync errors 132+1981, 4,624 skip lines, 20,403 MMK wire
lines, 30 leak WARNs, refresh 288 × 51 s.

**Deployed:** cusma2 2026-09-02 23:57 UTC (V9_54 + R__1_03 rerun 18 s, API 200 after 35 s, matview 1587 → 841 MB, 11 → 9 indexes); cusma3 2026-09-03 07:53 UTC in the old-timetable gap after the MMK lang sync (started 13 s, Flyway "up to date", env vars verified in /proc/PID/environ, no WARN/ERROR at start). cusma4 temp_bytes at cusma3 restart: 7,031,264,295,214 (+191 GB overnight = the OLD 5-min refresh still running on cusma3; expect flat from here). Both `.prev` = 28.8 build.

**Deploy order:** cusma2 first (applies V9_54 + re-runs R__1_03 → ~30-40 s extra cusma4 CPU at startup, searches keep
serving), env vars above, health check; then cusma3 with its env vars **only in a safe window** (old timetable is
live until its restart: busy 01:00-07:30 UTC + :20/:40 availability slots). Rollback = `webservice.jar.prev` (table is
additive; old code ignores it; R__1_03 old checksum would re-run the old definition on rollback).

**Follow-ups (not in this batch):** ReservationIntegrationService/OptionExpiry tx split (TX P2/P3), Stripe webhook
promote outside tx (P7, money path — Mario's call), expired rows out of the matview (item 6), service_call GDPR
purge on account deletion, data-fix of 36 MMK + 16 NauSys frozen over-blocking rows (V9_55, after the summary names
the agencies), warm marker written even when the warm failed (now 3 h skip), date-dependent unit tests, cusma4
pg_stat_statements at the next PG restart, PG 18.1 → 18.6.

## 2026-09-02/03 — OPS: cusma2 heap 4096→3072 MB, needrestart list-mode ×5, NTP ×5, logrotate, jar cleanup — ✅ APPLIED

Fleet audit (2.9.2026) found 9 kernel OOM kills of the API JVM on cusma2 in 12 days (anon-rss 5.6 GB at
kill = 4 GB heap + ~1.6 GB off-heap on a 5.8 GB box). Changes applied live at 22:00 UTC:

- `boat4you.service` ExecStart `-Xmx4096m` → `-Xmx3072m` (backup `boat4you.service.bak-20260902`), controlled
  restart, API up in 12 s. If the app ever throws java.lang.OutOfMemoryError under this heap, that is the
  signal to fix the in-process caches (Ehcache entry-sized pools, OpenCV per-request native work) — see
  FLEET-AUDIT-2026-09-02-v2.md backend section — not to raise Xmx again.
- needrestart set to list-only (`/etc/needrestart/conf.d/50-no-auto-restart.conf`) on cusma1-5:
  unattended-upgrades was restarting the API (06:42), scheduler (06:51), nfs-server and PostgreSQL (6× in
  7 days) unsupervised in the 06:00-07:00 UTC window. Security patches still install; service restarts are now
  a manual decision.
- NTP: all 5 boxes had never synchronized since boot (clocks 66-169 s behind; DHCP-pushed Hetzner NTP
  unreachable). `/etc/systemd/timesyncd.conf.d/ntp.conf` → ntp1-3.hetzner.de + pool fallback. All synced.
- journald capped at 500 MB on all 5; logrotate installed on cusma1/2/3 (nginx access.log on cusma2 was a
  single 2.17 GB file); 24 stale jar copies removed on cusma2 and 25 on cusma3 (kept webservice.jar,
  webservice.jar.prev, webservice_old.jar). Disk: cusma2 76→44 %, cusma3 73→46 %.
- Deploy scripts should stop leaving `webservice.jar.bak.*` copies behind — keep at most `.prev`.

## 2026-07-12 — Retention reaper + fix silent 06:00 rollback (BE 918a1d7+41bcf6c, V9_37, DEPLOYED, backlog DRAINED)

**New nightly 03:40 job (cusma3, ShedLock `retentionReaper`)** deletes in bounded batches,
each batch its own autocommit tx: service_call >60d, dead offers (past >30d, no
reservation_flow ref; children via one atomic data-modifying CTE), expired
external_reservations >30d (zero FKs). Manual: `POST /admin/maintenance/retention-reaper`
(SYSTEM_ADMIN, data-sync node). `V9_37` = index on service_call(received_at) (applied in 3s).

**ROOT-CAUSE INCIDENT:** the old 06:00 `deleteExpiredReservationsAndOffers` ran offers +
reservations + option purge in ONE giant @Transactional that **silently never committed** —
identical "Purge mirror" orphan counts on consecutive nights (83,925 on 7.7 AND 8.7) proved
full nightly rollback; hence 26k offers / 78k mirror rows / 3.2M service_call backlog since
January. That job now runs ONLY purgeExpiredOptions (own tx); `deleteExpiredOffers` +
`deleteExpiredReservations` repo methods REMOVED (single owner = reaper).

**Backlog drained via 3 manual passes (~70s total):** service_call −3,169,468 (now 2.85M,
all <60d), offers −27,269 (+68k extras, +27k plans), external_reservations −78,494.
Post-checks: 0 stale left, future offers intact (1.68M), API/web 200. Space note: freed
pages are dead tuples until autovacuum; files don't shrink without VACUUM FULL/pg_repack
(optional, off-hours) but growth stops and space gets reused.

⚠️ **V9_36 CLASH (2nd time!):** my index migration was first numbered V9_36 — the parallel
session's `V9_36__agency_inquiry_only` was already applied in prod. Renamed to V9_37
(41bcf6c) BEFORE deploy — caught by the `unzip -l jar | grep V9_` pre-deploy check, which is
now clearly mandatory. Rollback jars: `webservice_pre_reaper.jar` (both nodes).

## 2026-07-06 — Image download: interleave oldest+newest (BE 5e50ac4, DEPLOYED)

**What:** `ImageDownloadJob` fetched un-synced images newest-id-first only
(`findBySyncedFalseOrderByIdDesc`). When ~500 MMK/NauSys agencies auto-created at once
(~58k new images), the nightly sync kept adding higher-id un-synced rows that leapfrogged
the backlog → old low-id agencies (FX Yachting) starved for days. Fix = `downloadImages()`
now splits the page ½ newest (DESC) + ½ oldest (ASC) when `count > pageSize` (strictly
disjoint → no double-download); `count ≤ pageSize` takes all. New repo method
`findBySyncedFalseOrderByIdAsc`. No migration, no config/entity/sync-write change,
`image-sync-count` untouched (5000, ~21min ≪ PT2H lock → no double-run).

**Deploy:** ONE combined jar from clean worktree @ 5e50ac4 (HEAD — linear, contains inquiry
534150f + image fix + V9_33–36; tree clean, no parallel WIP). Job is `@Profile("data-sync &
image-sync")` = cusma3 only, but jar shipped to BOTH nodes to keep the "isti webservice.jar"
invariant. **cusma3 first** (Started 16:17:42, image fix live before next 16:50 UTC run),
then **cusma2** (inert change there, API 200 local+edge, Started 16:18:54). Jar verified
pre-deploy: V9_30–36 present + `findBySyncedFalseOrderByIdAsc` compiled + `isInquireOnly`
intact. Rollback: `webservice_pre_imgfix.jar` on cusma2/3.

**Baseline for verification (16:19 UTC):** pending 37,509; min pending id 545 = yacht 30
"Alexandros" (oldest starved). After the 16:50 UTC run (interleave, ~2500 oldest/run) the
low-id front (545+) should flip synced → verify Alexandros images download.
⚠️ Committed by the parallel MMK/NauSys session; I did the single combined deploy so the
two sessions don't ship competing jars (5.7 concurrent-deploy outage lesson).

**RESULT + one-time drain (16:52→18:20 UTC):** interleave confirmed (Alexandros/low-id
blanks filling). To skip the ~15h wait at 5000/2h, bumped cusma3 env `IMAGE_SYNC_BATCH
10→30` + `IMAGE_SYNC_COUNT 5000→40000` (restart) → one run cleared **37,509 → 602**
(blank yachts 3309→5, blank main 3331→19). Residual 602 = dead partner URLs (neg-cache 7d).
Reverted env to 10/5000 + restart (steady-state), backup `.bak.imgdrain` removed.
⚠️ Learned: cusma3 image throughput is **CPU-bound** (2 cores, webp encode), ~260/min avg
(bursts 2500/min); batch beyond ~30 doesn't help. Frequent cusma3 SSH (monitor loops) trips
**fail2ban** ("Permission denied", not a bad pw) — poll pending via cusma2, spare cusma3.

## 2026-07-06 — Agencies: inquiry-only flag (BE 534150f, admin cc9b0f7, DEPLOYED)

**What:** per-agency "Inquiry mode" toggle in the admin /agencies edit modal (right of
Recommended). When ON, every yacht of that agency becomes inquiry-only — no direct/live
reservation, only the inquiry form — exactly like CUSTOM boats.

**Mechanism (no web change):** `Agency.inquiryOnly` (V9_36 `inquiry_only BOOLEAN NOT NULL
DEFAULT false`) → `Yacht.isInquireOnly()` now also true when `agency.inquiryOnly`. That one
method already feeds BOTH the web detail DTO (`YachtMapper.toDetailsDto.inquireOnly` →
web `resolveGate` shows the inquiry form) AND the booking guard
(`ReservationFlowMutationService.createReservationFlow:84` throws "Yacht is inquire only").
Agency is loaded within the @Transactional at both call sites (no LazyInit). Admin threads
the flag via `AgencyDto.inquiryOnly` + `toDto`/`updateBlockWithModel` (null-coalesced so a
partial PUT never clears it).

**Deploy:** jar built from clean detached worktree @534150f (Xmx5g, no-daemon). cusma2 FIRST
(Flyway applied V9_36 clean → `now at version v9.36`, Started 11.7s, `/public/countries` 200
local+edge), then cusma3 scheduler (Flyway-pinned, skips V9_36; column already present → clean
boot 12.9s). Admin dist → cusma1 `/var/www/admin.boat4you.com/html` (entry `index-DZLf54rx.js`,
`inquiryOnly` in bundle, `api.boat4you.com` baked 0× localhost). Rollbacks:
cusma2/3 `webservice_pre_inquiry.jar`, cusma1 `html.old`.

**Verified live (A/B on cusma2):** yacht 15932 (Allure, FX Yachting 1616) → FX flag OFF
`inquireOnly=false`, flag ON `inquireOnly=true`, reverted OFF `inquireOnly=false`. Adversarial
review: no real bugs. FX left at inquiry_only=**false** (feature shipped disabled everywhere —
Mario toggles per agency; 0/2061 agencies currently on).

## 2026-07-06 — Trip: crew push on document/crew-list add + install banner (BE 778662a, web -5SRuex, DEPLOYED)

**Push when we add something (Mario 6.7.):** admin uploads a customer-visible travel document
(BOARDING_PASS / CREW_LIST / PREFERENCE_LIST) or first sets the crew-list link → the crew's
subscribed devices get a web-push ("📄 New document in your trip" / "📋 Crew list ready").
Weather + charter-announcement pushes already exist (TripPushJob T-7/T-1/day-of).
TripPushService.notifyCrew resolves the token + sends after commit. Internal admin docs stay silent.

⚠️ **Review (HIGH) fixed pre-deploy:** the after-commit push ran on the request thread while the
outer tx's Hikari connection was still bound, and web-push send() blocks with NO timeout — a hung
push endpoint could pin a pooled connection on cusma2 (single no-swap API node). Fix:
`sendToReservationAsync` hands the blocking HTTP to a dedicated bounded pool (1-3 daemon threads,
queue 500, DiscardPolicy) so the request connection frees immediately and no DB connection is held
across the sends. Also applied to the pre-existing concierge chat push (same pattern). TripPushJob
(cusma3, scheduled) stays sync.

**Web install banner:** in browser mode (not standalone) a dismissible top banner (sticky, X →
localStorage) prompts adding to the home screen for notifications; Android drives the native
`beforeinstallprompt`, iOS shows the Share→Add-to-Home-Screen steps. (Note the Google Play Protect
"unsafe app" warning on Samsung Internet is a Google/WebAPK quirk, not ours — use Chrome / "Install
anyway".)

**Deploy:** built ONE unified jar from a CLEAN worktree at HEAD (778662a) — main now carries the
parallel session's V9_34 + charter-update (both committed + already applied, schema 9.34) plus my
trip changes, so Flyway skipped V9_34 (already applied) and both nodes booted clean on 9.34,
health 200. Rollback: webservice.jar.bak.pre-docpush (both). Web .next.bak on cusma1.

---

## 2026-07-05 (noć) — Trip: 10-day GDPR photo retention + ⚠️ concurrent-deploy outage

**Feature (Mario 5.7.2026):** trip photos are kept only 10 days after the charter, then deleted
(DB rows + NFS files). `TripPhotoRetentionJob` (cusma3, daily 09:50) purges photos of charters
ended >10d ago; uploads now close at +10; the T+1 "album ready" concierge post states the exact
download deadline (dateTo+10) + that we remove them afterwards (GDPR); the hub album shows the
deadline. Web: BUILD BSDKGYZhHgJu83CykyniT. Backend built from a CLEAN git worktree at my HEAD
(4c52b01) to exclude the parallel session's uncommitted WIP; deployed to cusma3 (retention runs
there, @Profile data-sync) and cusma2. First purge deletes nothing real yet (only future-dated
Zen test photos exist).

⚠️ **PRODUCTION INCIDENT — ~5 min API outage (~20:29–20:34 UTC):** TWO work streams were deploying
to cusma2 at the same time (mine = trip; a parallel one = booking-number prefix 1001→1441 **V9_33**

- "reservation charter update" **V9_34**). Their concurrent restarts + a transient Flyway
  "Migrations have failed validation" during the migration transition crash-looped cusma2 for ~5 min
  (api.boat4you.com 502). It SELF-RECOVERED: the parallel jar (size 229964138) settled, applied V9_34
  (schema now **9.34**), booted healthy (Tomcat 20:35:03). Final state verified stable: api/www/admin/
  trip all 200/307, cusma2 no further restarts.

**Resulting split (benign, but note for next deploy):** cusma2 (API) runs the PARALLEL jar (9.34,
charter-update, and it DOES carry my trip code — album.zip 403-guard present, so they built from
main incl. my commits). cusma3 (scheduler) runs MY jar (9.33, retention job). Job profiles are
disjoint (data-sync only on cusma3) and only cusma2 serves the API, so both features work. V9_34 is
applied to the DB but its FILE is NOT yet on origin/main (parallel session's local WIP) — a unified
rebuild isn't possible until they push it. **⚠️ On cusma3's next restart, Flyway will see DB 9.34 as
a future migration (warn, no-op) — watch it comes up clean.**

**LESSON: never run two concurrent production backend deploys to cusma2.** Coordinate; deploy
serially. Rollbacks: webservice.jar.bak.pre-retention (cusma3 = my jar; cusma2 was overwritten by
the parallel deploy).

---

## 2026-07-05 (noć) — Trip: GDPR-minimal admin + album ZIP download (BE 05cdd55, web tfBhU7f…, admin, ALL DEPLOYED)

**Mario 5.7.2026:** the broker must NOT have casual access to the crew's private trip content.

- **Admin now has NO chat / participants / photo-browsing.** AdminTripController reduced to:
  album-summary (counts only), album.zip (?marketingOnly=true = only consented), regenerate-token.
  Removed the chat DIGEST email (it carried guest PII) and the 💬 unread badge; also removed the
  `tripChatUnread` field from the reservation view/entity/DTO/mapper (it leaked a crew-chat-activity
  signal to the broker on every bookings-list fetch — review find). Physical `admin_chat_seen_at`
  column left unused (no migration, avoids V9 clash).
- **Album delivery:** the crew hub gets a "⬇ Download all photos" (ZIP) button; the T+1 concierge
  automation now posts "your photos are ready — download them" (chat + push) when photos exist =
  the crew's download link. Admin keeps an on-demand ZIP (all, or marketing-consented) for when a
  guest asks / for approved marketing reuse.
- **ZIP is STREAMED** (StreamingResponseBody + Files.copy, one photo at a time, OUTSIDE any tx) —
  never buffered whole in heap (would OOM cusma2, the single no-swap API node) and never pins a
  Hikari connection across the NFS reads (review HIGH+MEDIUM). Service returns only the file list
  in a short tx; the controller streams.

⚠️ **V9_33 COORDINATION:** a parallel session's `V9_33__reset_booking_sequence_new_prefix.sql`
(booking-number prefix 1001→1441, resets booking_sequence to 0) was already applied to cusma2's DB
(schema at v9.33) and its 1441 `BookingNumberService` code is on origin/main (in HEAD). My jar
(max migration 9.32) was built with V9_33 PARKED OUT of the migration dir so it doesn't carry it;
Flyway treats the DB's 9.33 as a future migration (warn, no-op) and re-ran the repeatable R__1_02.
My jar DOES contain the 1441 code (from HEAD), so it's consistent with the reset counters —
first new online booking = 1441001/{year}, no collision with legacy 1001…. **If ever rolling my
jar back, the 1441 code stays (it's committed), so no counter hazard from my side.**

**Verified live on Zen:** crew album.zip 200 (valid ZIP, streamed), wrong key 403, admin endpoints
security-gated. Rollback: webservice.jar.bak.pre-gdpr (both nodes); web .next.bak on cusma1;
admin html.old. Test artifacts cleaned from Zen (real crew Cvijo/Jadranka/Mario kept).

---

## 2026-07-05 — Reservation-number prefix 1001 → 1441 + counter reset (V9_33)

**Rule (Mario 5.7.2026):** online bookings switch prefix `1001` → `1441` and the per-year
sequence restarts at 1, zero-padded to 3 digits → next is **`1441001/2026`**, then
`1441002/2026`; a 2027-start charter → `1441001/2027`. Reason: parallel bookkeeping this
year (legacy back-office reservations + new online ones) — the two streams must not clash.

- Code: `BookingNumberService.PREFIX="1441"` + `padStart(3,'0')`.
- `V9_33` = `UPDATE booking_sequence SET last_sequence = 0` (was 2026→84, 2027→3 on 5.7.).
  Existing `1001…` numbers untouched; `1441…` never collides (different prefix).
- **Deploy order: cusma2 FIRST** (applies Flyway; cusma3 pinned won't). Bookings are created
  on cusma2, so both the reset + new code land there together at boot (no window).
- **⚠️ ROLLBACK HAZARD:** rolling the jar back to old `1001`/unpadded code while the counters
  stay 0 → old code regenerates `10011/{year}` etc. → unique-constraint failure on booking
  creation. If you roll back the jar, ALSO restore the counters (2026→84, 2027→3) or bump
  each year past its highest used legacy sequence. Rollback jar: `webservice_pre_prefix1441.jar`.

## 2026-07-05 (navečer) — Trip hub → app-like bottom tabs (web 3a657a1, BUILD -eCPbHLPinSZWmIgCmHbP)

Per Mario + the approved design: the trip hub's single long scroll became a tabbed
app. Persistent hero on top; fixed safe-area bottom nav with 4 tabs — **Trip**
(gallery, owner payments, weather, SOS), **Documents** (travel docs + empty state),
**Chat** (crew + chat + album), **More** (push reminders, install guide, support).
Pure web layout change; no backend. Verified live: 200, all four tab labels served,
build -eCPbHLPinSZWmIgCmHbP. Rollback .next.bak on cusma1.

---

## 2026-07-05 (kasno navečer) — Trip album upload fix: nginx 413 (SERVER CONFIG + web a8a79ad)

**Symptom:** photo upload from Mario's iPhone silently did nothing. **Cause:** nginx on
cusma2 had NO client_max_body_size anywhere → default 1 MB; phone photos (3-10 MB) got
413 before reaching Spring (access log confirmed). Admin doc uploads never hit it (small
PDFs/Word).

**Fixes:** (1) `/etc/nginx/conf.d/boat4you.conf` — `client_max_body_size 25m;` in both
api server blocks (backup `.bak.pre-bodysize`, nginx -t + reload OK). ⚠️ MANUAL SERVER
CONFIG — not in git; restore it if the box is ever rebuilt. (2) Web (a8a79ad, BUILD
E-Q-IcLBAjUlxzS-62d_8): photos downscale client-side to 2048 px JPEG before upload
(keeps every phone photo ~1 MB, fast on marina Wi-Fi, also under the 10 MB server image
cap), upload failures now show a message instead of silently doing nothing, accept
widened to image/* (HEIC decodes in canvas → JPEG). Verified: 5.6 MB upload → 200 →
webp served; probe rows cleaned from Zen (Test Gost + poruka ostavljeni kao demo).

---

## 2026-07-05 — Boat4You Trip PHASES 3+4: crew, chat, album, admin console (BE b499987, web 876f006, admin fcc6d80 — ALL DEPLOYED)

**The full Trip product is live.** V9_32: trip_participant (secret per-device key,
roles OWNER/GUEST/SKIPPER/CONCIERGE, soft remove), trip_chat_message (automation_tag
for idempotent scheduled posts), trip_photo (per-upload marketing consent),
reservation.admin_chat_seen_at.

**Closed group:** guests join with just a name (unlocks after 1st payment / status
RESERVATION, max 20, refusal reasons LOCKED/FULL/FINISHED/CANCELLED); the owner
enters pre-claimed through his web session (/secured/trip/{token}/owner — stable
key across devices); leader/admin remove members; admin can regenerate the link
(now also deletes push subscriptions so old devices can't receive the new URL).

**Chat:** PG rows + SSE fan-out on cusma2 (heartbeat 25s, X-Accel-Buffering:no) +
30s full-resync poll (scheduler posts have no SSE emitters + fixes id-cursor gaps);
writable until dateTo+14d; concierge posts (admin or automation) push to crew
devices AFTER COMMIT. TripChatAutomationJob 09:45 (cusma3): itinerary T-14 (+push),
ready T-1 (no double push, TripPushJob covers it), daily digest email to admins;
per-post transactions (review fix — one bad reservation no longer poisons the batch).

**Album:** webp ingest to NFS trip-photos/{id}, consent checkbox per upload,
uploader/leader/admin delete (= GDPR consent-withdrawal path), uploads close
dateTo+30d. **Admin:** Trip chat & crew panel in the booking detail (concierge
composer, participant chips w/ remove, photo grid w/ MKT-consent badges + delete),
💬 unread badge in the bookings list (concierge posts excluded).

**QRs:** my-bookings (desktop→phone, next to the Trip app button) + hub invite
card (owner: QR + navigator.share). Push isOwner is now VERIFIED via the OWNER
participant key (client boolean was spoofable — review find).

**Review:** 22-agent adversarial workflow, 15 confirmed findings fixed pre-deploy
(tx poisoning, commit-ordered SSE/push, newest-200 history, badge filter, docs
+30d listing window, key-leaking album anchors removed). Known accepted: key in
query strings for GET/SSE/img (EventSource/img can't send headers; nginx logs are
internal), removed guests can rejoin under a new name (leader just removes again).

**E2E verified live on Zen:** join→chat post→history→SSE ':connected'→photo
upload(webp)→raw 200→self-delete 204; 403 wrong key; /admin/trip 403 unauthed.
Test rows left as demo: participants 'Test Gost' + 'SSE Probe', 1 chat message —
removable via the new admin panel. Rollbacks: webservice.jar.bak.pre-trip3 (oba),
.next.bak-20260705165603, admin html.old.

---

## 2026-07-05 — Boat4You Trip PHASE 2: push + analytics (BE 819390c, web 7b75ae0, ALL DEPLOYED)

**Web-push reminders + day-1 analytics are live.** V9_30 adds trip_push_subscription
(endpoint-unique upsert, is_owner flag) + trip_event; V9_31 backfills ACI Marina Split
coords (43.5024, 16.4295 — the only reservation marina without lat/lon; sync never
writes coords so it sticks). New deps: nl.martijndwars:web-push 5.1.1 (bcprov-jdk15on
excluded — we ship jdk18on).

**Endpoints:** POST /public/trip/{token}/push-subscriptions (400 on non-https endpoint,
404 wrong token) + /events (whitelist HUB_VIEW/SITE_CLICK/PUSH_SUBSCRIBE/PUSH_OPEN/
DOC_OPEN). TripDto gains vapidPublicKey (null = push off, hub hides the card).

**TripPushJob (cusma3, daily 09:40, shedlock):** T-7, T-1 + Open-Meteo forecast, day-of
welcome + forecast, T+1 thank-you, installment reminders 7/2 days before deadline —
owner-only devices, NO amounts ever (crew shares the hub). Click-through =
/trip/{token}?push=tag → hub logs PUSH_OPEN. Dead subscriptions (404/410) auto-delete.

**Web:** sw.js bumped b4y-v2 with push/notificationclick; hub gets a "Get trip
reminders" card (SW registered on /trip, hidden on unsupported browsers/finished trips),
analytics events fire-and-forget. iOS: card appears only inside the installed PWA.

**VAPID:** keypair generated 5.7, appended to cusma2 boat4you_vars.env + cusma3
boat4youscheduler_vars.env (backups *.bak.pre-vapid). Rollback jars:
webservice.jar.bak.pre-trip2 (both nodes); web .next.bak-20260705123210.

**Verified live:** V9_30+V9_31 applied (schema v9.31), Zen payload carries marina
coords + vapid key, sw.js v2 served, events 204→row in trip_event, 404/400 guards.
⚠️ First real push goes out at the 09:40 job — check `TripPushJob: delivered` in the
cusma3 journal after someone subscribes (Mario's phone is the E2E test).

---

## 2026-07-05 — Boat4You Trip PHASE 1 (commits 73fcf5d/858a54a/84d943f + web 4ceeccd + admin 161f1eb, ALL DEPLOYED)

**The PWA trip companion is live.** Every reservation carries an unguessable `trip_token`
(V9_29 — ⚠️ was V9_28 but the parallel agency-mirror session took that number; the clash
crash-looped cusma2 for ~90 s until the rename redeploy. LESSON: `git pull` + check migration
numbers against origin BEFORE building a jar). `GET /public/trip/{token}` serves the hub
payload (yacht+gallery+specs+slug, marina+coords, dates, crew-list link, agency phone, TRAVEL
docs only — no prices/PII); token-scoped travel-doc download; token in my-bookings + admin DTOs
via reservation_view (R__1_02 re-ran).

**Web:** `/trip/[token]` standalone EN mobile hub (own root layout; `trip` excluded from the
locale-middleware matcher!): countdown hero, gallery→boat page, Open-Meteo 7-day forecast for
the marina coords, country-aware SOS card, leader-only payments card (session+reservation-number
match), cancelled/finished modes, per-token manifest (`/trip/{t}/manifest.webmanifest`),
noindex. My-bookings shows a navy "Trip app" button. **Admin:** Trip hub panel with QR
(qrcode dep) + copy/open in the booking sidebar.

**Verified live on Zen (#100183/2026):** page 200 + SSR content, manifest OK, wrong token 404,
API 200, admin 200. Known gap: ACI Marina Split has NULL lat/lon → weather hidden there
(coords backfill = phase 2 item). Zen token: 718b59ec456c48c2b291ca2893ebbff8.

---

## 2026-07-05 — Agency mirror: auto-create/auto-deactivate partner agencies (V9_28)

**Rule (Mario 5.7.2026):** the partner's company list IS our agency list — every new MMK/NauSys
company (e.g. FX Yachting, MMK 8304) is auto-created with a primary source and its fleet follows
on the next yacht/offer sync; a company the partner stops returning is auto-deactivated
(active=false → yachts drop out of yacht_search_view). Deactivation is stamped
`sync_deactivated_by` (1=MMK, 2=NauSys): only the same system may re-activate, admin toggleActive
(resets to NULL) is never overridden, no cross-system ping-pong. Reconcile guards: empty response
= skip; >30% absent = truncated response, skip (PartnerWithdrawalGuard); legacy dual-primary rows
are never deactivated (logged). NauSys VAT/name match never merges into an MMK-sourced agency
(one row per system — dual VAT duplicates exist in prod, findAllByVatCode + filter).

**Pre-deploy checklist:**

- `V9_28` = ALTER TABLE agency (ACCESS EXCLUSIVE!) + unique index on
  agency_source(external_system_id, external_id) (prod pre-checked: 0 duplicates 5.7.).
  ⚠️ Before restarting cusma2: check `pg_stat_activity` for idle-in-transaction backends
  holding agency locks (29.6. lesson) — `pg_terminate_backend` first if any.
- Deploy order: **cusma2 FIRST** (applies Flyway; cusma3 is pinned FLYWAY_TARGET_VERSION=1.43
  and would not apply V9_28), then cusma3 (scheduler runs the actual mirror).
- Expected first run (measured 5.7. against live partner lists): MMK +367 created / ~168
  deactivated; NauSys +139 / ~24. 23 MMK + 52 NauSys agencies are inactive-with-us but still
  partner-listed — they STAY off (sync_deactivated_by=NULL = treated as manual) — Mario decides.

## 2026-07-03 — Travel documents: type + crew-list CTA (commit e7a1f87, DEPLOYED all 3 apps)

**Feature (Mario 3.7.2026):** near charter start the customer needs the crew list and the
boarding pass / base info. Decision: crew list = the PARTNER's own editor link (agency files
it with the port authority — no transcription by us); Kavas-style Word forms and boarding-pass
PDFs = admin-uploaded documents (existing reservation_document pipeline, reused).

**Backend:** `V9_27` adds `reservation_document.document_type` (BOARDING_PASS/CREW_LIST/
CONTRACT/OTHER, default OTHER — verified live) + threading through entity/repo projections/
DTO/upload endpoint (`type` param, lenient parse). Pre-charter reminder email gains a
conditional "Complete your crew list" CTA (partner `crew_list_url`; null → omitted), i18n in
9 email locales.

**Admin (b0dafed, deployed cusma1 /var/www/admin.boat4you.com):** "Upload as" type select on
the customer-visible drawer + type chip; crew-URL editor gains Open ↗ test button + onSaved →
reloadSelectedBooking (fixes stale-store bug after save).

**Web (80eb7ba, deployed cusma1 BUILD klrZoif73Nw4LSOK9Syt7):** sidebar Documents stack →
"Travel documents": typed rows (human label, filename · size · date meta, Open/Download
action), passport-accuracy note under the crew-list row. 7 new keys × 9 locales.

**Round 2 (same day, DEPLOYED):** `PREFERENCE_LIST` document type (backend f19930f — enum only,
document_type is VARCHAR so no migration; admin c427f6b adds it to the Upload-as select; web
3062697). Web also gains the **TravelDocumentsBar** — a prominent button strip rendered directly
UNDER the yacht images on /my-bookings/{id} (crew list link + uploaded crew form + boarding
pass/base info + preference list; renders nothing until something exists) so the customer can't
miss the travel documents. Boarding-pass label now reads "Boarding pass / Base info".

**Deployed 2026-07-03:** cusma2 (V9_27 applied) → cusma3 (flags preserved); admin dist swap
(rollback `html.old`); web .next swap (rollback `.next.bak-20260702225950`). Backend jar
rollbacks: `webservice.jar.bak.pre-docs` (both).

## 2026-07-02 — Taken-back yacht image purge (commit 5e9818e, DEPLOYED)

**Rule (Mario 2.7.2026):** yacht removed from the partner → its images go too. `ImageDownloadJob`
now purges partner-sourced images of deactivated yachts (rows + NFS files, 500/batch; main image
kept because sent reservation emails hotlink `/public/image/{mainImageId}`); `deleteYacht()` now
deletes files too. Backfill target measured pre-deploy: **6.5k rows / 335 inactive yachts**.
No migration.

**Deploy (DONE 2026-07-01 ~22:31 UTC):** jar from `5e9818e` → cusma2 (22:31, `/public/countries` 200) + cusma3 (22:32, started 10.9s). This jar also carried the two pending items below — both are
now LIVE (V9_26 was already applied at 22:28 by the parallel session's own cusma2 deploy of
`07fcff5`; this deploy supersedes that jar).

**Verify (DONE, first run 22:50 UTC):** `Purged 6221 images of deactivated yachts` + DB check:
exactly 335 rows (all main images) remain for inactive yachts. Summary line live:
`Image sync: 477 of 478 images failed for 139 yachts; 1 skipped as known-dead`.

**Follow-up `23c9ee2` (DEPLOYED both nodes ~22:56 UTC):** 6 residual ERRORs/run were partner images
over the 10 MB save cap (deterministic) — `IllegalArgumentException` from saveImage now joins the
7-day negative cache as WARN; ERROR stays for real disk/NFS failures only. Restart wiped the
in-memory cache, so the 00:50 run bursts ~477 WARNs once more; steady state (~12 WARN summaries/day,
0 image ERRORs) from 02:50 on.

---

## 2026-07-02 — cache-warm Hikari connection-pinning fix (commit 5c7aa53, DEPLOYED via 5e9818e jar 22:31 UTC)

**Overnight verify (22:55→06:26 UTC, 7.5 h) + follow-up:** the 5-min zombie mechanism is dead —
0 idle-in-transaction kills, 0 sync TimeoutExceptions (pre-fix 1.5–4k/day), 0 executor drops
(pre-fix ~16k/day), 1146 completed warm syncs, cache markers being written. Residual: 3 pool-
exhaustion bursts (03:32, 04:00, 05:32 — 102 errors total vs ~200+/day pre-fix), ALL inside the
nightly NauSys/MMK sync window; at those seconds NO connection was held >60 s (no leak WARNs
around them) → remaining bursts are pure throughput (cusma4 slow under sync writes + matview
refresh, 19–25 conns churning multi-second queries, 20 s waiters expire together). The ~188
overnight leak WARNs are the BOUNDED per-yacht warm path (partner call w/ retries can exceed the
60 s leak threshold; always unleaks) — expected noise, not a leak.

**Follow-up (06:31 UTC): `DB_POOL_MAX=35` on cusma2** (`boat4you_vars.env`, was default 25;
backup `boat4you_vars.env.bak.pre_pool35`; restart 06:30:59, verified in `/proc/<pid>/environ`,
health 200). PG headroom fine: max_connections=100, total in use ~21. cusma3 left at 25 (no
bursts there). Watch next night's 03:00–06:00 window: `journalctl -u boat4you | grep -c
"Connection is not available"` — expect 0; if bursts persist at 35, next lever is cusma4 query
perf during sync, not more connections.

Root cause of the nightly/daily "Connection is not available" bursts (18–41 errors in one
second, booking flow → "technical difficulties"): `ExternalSyncService`'s class-level
read-only transaction pinned a Hikari connection while the location-path cache-warm waited
up to 5 min on nested @Async tasks that starved/dropped on the same 6-thread pool
(F1-064 handler drops leave futures that never complete). Postgres
(`idle_in_transaction_session_timeout=5min` on cusma4) killed the session each cycle
(SQLSTATE 08006). Steady state: 6/25 connections gone + warm markers never written →
same ranges re-warmed forever. Fix: no ambient transaction on the location path,
partner syncs run in-thread sequentially (bounded by HTTP timeouts), per-yacht path
keeps its bounded read-only tx. NO migration in this commit — safe to ride along with
the V9_26 deploy below (any jar built from main ≥ 5c7aa53 carries it).

**Verify after deploy (cusma2):**

- `journalctl -u boat4you --since "<deploy time>" | grep -c "Apparent connection leak"` → should stay 0
  (pre-fix: ~1700/day in 5-min lockstep on AsyncThread-*).
- psql on cusma4: `SELECT count(*) FROM pg_stat_activity WHERE client_addr='192.168.55.2' AND state='idle in transaction' AND now()-xact_start > interval '90 seconds'` → 0 across a few samples
  (pre-fix: constantly 3–6).
- "Failed to sync yacht offers … TimeoutException" should drop to ~0 (pre-fix 1.5–4k/day);
  "Connection is not available" bursts should disappear over the following day.

---

## 2026-07-02 — V9_26 phone trunk-zero data fix (commit 64b8dd4, DEPLOYED — migration applied on cusma2 22:28 UTC)

`V9_26` rewrites stored `+3850…` phone numbers to `+385…` (reservation_flow 18, inquiry 2 —
customers typed national format 098… and the old PhoneInput stored the trunk zero; undialable
abroad). The FE fix (boat4you-web `ba943da`, PhoneInput strips trunk zero except IT/SM) is
ALREADY LIVE on cusma1. Deploy backend cusma2 (applies V9_26) + cusma3 when the parallel
image-spam session (commit 38a8d9d, same jar) finishes its own deploy — do NOT race two
deploys of the same service. Verify after: `SELECT count(*) FROM reservation_flow WHERE
phone LIKE '+3850%'` → 0.

---

## 2026-07-01 — First-payment deadline clamped to option expiry (commit f2c09c2, DEPLOYED)

**Bug (Mario):** payment page / emails said "pay by 08.07" while the NauSys option expired
06.07 23:59 (Zen 100183/2026). A customer paying between the two dates pays for a boat the
agency may already have re-let. Root cause: the first-phase deadline comes from the PARTNER
payment plan ("first installment within N days"), which is independent of the option window.

**Fix:** `ReservationMutationService.clampFirstPaymentDeadlineToOptionExpiry` — at reservation
creation (customer path only), the EARLIEST unpaid phase deadline is clamped to
`optionExpiresAt.toLocalDate()`. Later installments keep the partner schedule (the option
ceases to matter once the first payment confirms). Null expiry → untouched (never invent
deadlines). Same transaction → payment page, wire emails, and reminders all read the clamped
date. `V9_25` fixed pre-existing rows (live unconfirmed options, earliest unpaid phase only —
prod dry-run + actual: exactly 1 row, the Zen reservation: 08.07 → 06.07).

**Deployed 2026-07-01 ~21:52 UTC** cusma2 (V9_25 applied, verified Zen phase 88 = 2026-07-06)

- cusma3. Rollback: `webservice.jar.bak.e2106ce` (both). Note: an already-open booking session
  caches phases in sessionStorage — fresh page loads / my-bookings / emails read the DB.

---

## 2026-07-01 — Payment-method fees: card +5% / bank transfer 32 EUR (commit e2106ce, DEPLOYED)

**Policy (Mario 1.7.2026):** card payments +5% processing fee on the amount being paid
(per installment); bank transfers a fixed 32 EUR per reservation split evenly across
installments (2 phases → 16 EUR per wire, mandatory); all fees whole-euro (no cents).

**How it works:** the fee infrastructure already existed (settings `CARD_PAYMENT_SURCHARGE`

- `BANK_TRANSFER_FIXED_FEE`, public endpoints, FE display) — card surcharge was already
  applied to the Stripe charge but the setting was unset (0), and the bank fee was
  display-only cosmetics. This deploy: `V9_24` seeds 5/32 (admin-editable later); Stripe
  surcharge now rounded HALF_UP to whole EUR; NEW `BankTransferFeeShare` splits 32 whole-euro
  across phases (earlier phases absorb remainder: 3 phases → 11/11/10); the wire "Transfer
  amount" in fewMoreDetails / optionExpiryReminder / reservationPaymentPending emails now
  carries the phase's share + a localized mandatory-fee notice (all 10 email locales).
  **Payment phase rows keep the base charter price** — the fee is a payment-channel
  surcharge applied at charge/communication time, so card payers never pay the wire fee
  and vice versa; no phase mutation, no confirmed-price interaction.

**Frontend (boat4you-web 5e888c2, deployed cusma1 BUILD_ID GJ1QezPFzD74fW8uoKH22):**
UnifiedPaymentStep + PayNowModal mirror the backend math exactly (Math.round card fee;
per-installment bank share via `bankFeeShareForPhase` — was showing the full 32 on one
installment).

**Deployed 2026-07-01 ~21:35 UTC:** cusma2 (V9_24 applied, `/public/settings/*` return
5/32), cusma3 (scheduler jar for reminder emails; flags preserved), cusma1 FE swap.
Rollbacks: `webservice.jar.bak.78b8027` (both), `.next.bak-202607012138` (cusma1).

---

## 2026-06-30 — NauSys createOption INSUFFICIENT_DATA fix for strict agencies (commit 797f9bd)

**Symptom:** customers could not place an option on yachts of _strict_ NauSys agencies
(Navigare = our agency 286 / NauSys companyId 122957; Dream Yacht Charter). The boat-detail
"enter-your-details" step failed; createInfo returned OK (with a price) but `createOption`
returned `INSUFFICIENT_DATA (201)`. Reported via Nedo (yacht 4548 / NauSys 37302180).

**Root cause (proven live, not guessed):** for strict agencies NauSys `createOption` requires the
client to carry a **COMPLETE postal address**. With only name+surname the option is rejected.
Surprisingly, supplying a client **email** _also_ triggers `INSUFFICIENT_DATA` (NauSys then tries a
registered-client lookup that needs more fields). Live isolation matrix on 2026-06-30:

- name+surname only → createOption INSUFFICIENT (Navigare); OK for lenient agencies.
- name+surname + **address** (no email) → createOption **OK** for Navigare AND all 4 lenient agencies tested.
- address + **email** → INSUFFICIENT again. So: address required, email must be omitted.

**Fix:** `NausysReservationIntegrationService.createOption` now builds the createInfo `RestClient`
with name + surname + the **broker agency's registered address** (Vrboran 37, 21000 Split,
countryId=1=HRV — Cusmanich d.o.o., matches the NauSys agency profile) and **no email**. We don't collect the customer's address, and the option is a hold
we place as the broker, so the broker address is correct. Constants live in a `private companion object`.

**Scope:** API node only (`createOption` runs on the booking request path = cusma2). The scheduler
(cusma3) never serves bookings, so this is functionally a no-op there — sync its jar to 797f9bd at the
next idle window for consistency (preserve the `-Dreconcile.shadow-mode=false` ExecStart flag).

**Deploy (DONE 2026-06-30 ~23:05 UTC):** built JDK21 bootJar, scp to cusma2 `webservice.jar.new`,
atomic swap (rollback backup `webservice.jar.bak.c6b88c5`), `systemctl restart boat4you`. App up in
10.5s, `/public/countries` → 200. Verified: live createOption for Nedo (37302180/122957) with the exact
deployed recipe → OPTION created (price 7743.50 EUR), test hold stornoed. Lenient agencies unaffected
(4 tested, both old and new recipe succeed).

---

## 2026-06-29 — Permanent availability-mirror reconcile fix (natural-key + shadow + V9_23 cleanup + detector)

**What:** the absent-reconcile no longer depends on `external_mapping` integrity. It now matches our
reservations to the partner's complete response by NATURAL KEY (yacht + dates + status), so stale
(cancelled-at-partner) RESERVATION/SERVICE rows are removed even when their mapping is missing (96k
legacy rows) or duplicate-mapped to another yacht (the Vi La Ut case). Ships behind a SHADOW flag.

### Deploy order (standard backend deploy)

1. **cusma2 FIRST** — applies Flyway `V9_23` (FLYWAY_TARGET_VERSION=latest live). Restart `boat4you`.
2. **cusma3 SECOND** — scheduler (Flyway-pinned 1.43 → does NOT apply V9_23). Restart scheduler.

### PRE-DEPLOY dry-run (29.6.2026 ~18:30 UTC, prod)

`V9_23` deletes self-contradictory future hard-blocks (RESERVATION/SERVICE, option_expiration NULL,
date_to>today, overlapping one of OUR FREE offers on the same yacht):

- **438 reservations across 334 yachts** will be deleted.
- **Includes Vi La Ut res 283386 (yacht 4736, 08/08→15/08)** → that boat reappears as bookable.
  **VERIFY post-migration:** Flyway-deleted count ≈ 438 (`SELECT count(*)` with the same criteria → 0 after).

### POST-DEPLOY (cusma2)

- The search hard-block reads `external_reservations` LIVE (correlated NOT EXISTS), so the fix is
  effective the instant the migration commits — **no manual matview refresh required.** (The
  `yacht_search_view` 5-min refresh updates the FREE/price display in due course.)

### SHADOW → LIVE (the catastrophe firewall — do NOT skip)

- Ships `RECONCILE_SHADOW=true` (default in code: `reconcile.shadow-mode:true`). While ON,
  `reconcileAbsent` LOGS what it WOULD delete (`[SHADOW] reconcile WOULD delete ...`) and deletes
  NOTHING. The migration above still runs (it is independent), so the 888/438 customer-facing damage
  is fixed on deploy regardless.
- After **3–7 full sync cycles**, review the `[SHADOW]` log on cusma3:
  - every WOULD-delete line must be a real cancellation / known-stale row,
  - **zero** WOULD-delete lines on a row a live partner read confirms is still booked,
  - per-agency counts in the low tens, not thousands (a thousands spike = breaker should fire = key bug).
- **DONE 29.6.2026 ~20:51 UTC** — after shadow evidence (tiny per-agency fractions, 30% breaker fired
  correctly) + 2 live partner spot-checks (Vi La Ut on NauSys, Eleonora on MMK), flipped to LIVE via
  the systemd ExecStart `-D` flag (NOT an env var). Live deletion drains the 96k mapping-less +
  duplicate backlog over normal cycles, within the per-agency 30% breaker. Verified first live run.

### cusma3 systemd state (server-only ops config — NOT in git; recorded here for reproducibility)

Current live `ExecStart` in `/etc/systemd/system/boat4youscheduler.service`:

```
ExecStart=java -Xmx2048m -Dreconcile.shadow-mode=false -jar /home/cusma3/boat4you/webservice.jar
```

- `-Xmx2048m` — heap cap (from the 29.6 sync-freq deploy; was 6144m). Backup: `~/boat4youscheduler.service.bak.6144`.
- `-Dreconcile.shadow-mode=false` — reconcile in LIVE delete mode. Backup: `~/boat4youscheduler.service.bak.shadow`.
- REVERT reconcile to shadow (deletes nothing): drop the `-D` flag → `daemon-reload` → restart.

### Verify (post-deploy)

- Vi La Ut (yacht 4736) week 08–15.08.2026 shows bookable on the site; DB has no res for that week.
- `AvailabilityIntegrityDetectorJob` (06:40 daily) logs: contradictions → trending to ~0, mapping-less
  → trending down, duplicate partner-ids → 0. WARN if contradictions > 25.
- Reservation count snapshot before/after the shadow flip must drop only by the projected shadow count.
