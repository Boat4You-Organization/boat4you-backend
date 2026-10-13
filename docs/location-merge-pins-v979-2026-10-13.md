# Web pinovi za V9_79 (bliske duple marine), 13.10.2026

Backend migracija `V9_79__location_near_duplicate_marinas_one_name.sql` daje svakom mjestu koje je Mario odobrio
(`docs/location-near-duplicates-review-2026-10-13.md`: „A sve da", „C može", plus Olympic Marine, Marina di Olbia i ACI
Marina Split) jedno ime i upisuje parove u `location_same_place`. boat4you-web landing nosi ime reda s najvećom flotom
(`destinationDid.ts`), URL je to ime malim slovima, a druga pisanja dobivaju 308 samo dok ih nešto još razrješava
(`landingRedirectPath`). Ovaj dokument kaže koji pinovi (`src/config/landing-identity.config.ts`), koji alias teksta
(`src/utils/static/curatedSeoSlug.ts`), koja izmjena link-blokova (`landingNav.ts` / `catalogueHubs.ts`) i koje brisanje teksta
s imenom agencije moraju biti LIVE **prije** migracije — sve četiri stvari u jednom web deployu.

## Kako je provjereno (13.10.2026, samo čitanje)

- Prod redovi (cusma4, `BEGIN READ ONLY`): svih 2.036 `location` + mappingi + regije + `location_same_place`; svih 113 redova
  migracije ima današnje ime, grad, mapping i koordinate iz migracije.
- Live `sitemap-categories.xml` (2.916 URL-ova) + `sitemap-locations.xml` (531), live `/public/locations?size=5000`,
  `/public/locations-count`, `/public/countries-count`, fleet po tipu s `/public/yachts?did=…&vesselType=…&size=1`.
- Simulacija: backend lista (`mergeDualSourceMarinas` + `containmentPairs`), tražilica (`findSamePlaceMarinaIds`), facts
  (`MarinaPlaces.placeIds`) i web resolver (`requireDestinationIndex` + `resolveDestinationName` + pinovi) prepisani 1:1.
  Simulacija današnjeg stanja = živa lista (775/775 marina redova, ista spojena imena i id-evi). Za svih 169 indeksiranih
  odredišta iz sitemapa uspoređena su četiri stanja: danas, samo pinovi (prije migracije), migracija bez pinova, migracija
  + pinovi.

## OBAVEZNO prije V9_79

Bez točaka 1 + 2 **90 indeksiranih URL-ova daje 404**, a `marina di golfo aranci` nema tekst (noindex). Bez točke 3 spojeni
landinzi ispadaju iz link-blokova svojih regija (Sardinija već s pinom „Olbia"). Točka 4 ide u isti commit kao 1 + 2, nikad prije.

Dodano nakon dva nezavisna review-a 13.10. (podaci + web), ponovno provjereno na produkciji (read-only 17:23 UTC) i na live
API-ju: točke 3 i 4, brojke flote nakon blizanaca, sitemap i „Zatečeno".

### 1. Pinovi (`src/config/landing-identity.config.ts`)

Postojeći pin „Lavrion Main Port" se **zamjenjuje** (dodan `l-2022` i njegova pisanja); ostalih pet je novo.

```ts
  {
    // l-150 (NauSYS "Lavrion, main port"), l-1069 (MMK "Lavrion Main Port |
    // Lavrion") and l-2022 (MMK "Lavrion Marina | Lavrion"): one port, one
    // name since V9_78 / V9_79 (owner 13.10.2026).
    name: 'Lavrion Main Port',
    dids: ['l-150', 'l-1069', 'l-2022'],
    kind: LocationType.MARINA,
    countryCode: 'GR',
    aliases: [
      'Lavrion, main port',
      'Lavrion Main Port | Lavrion',
      'Lavrion Main Port, Lavrion',
      'Lavrion Marina',
      'Lavrion Marina | Lavrion',
      'Lavrion Marina, Lavrion',
    ],
  },
  {
    // l-27 (NauSYS "Marina Zadar (ex. Tankerkomerc)") and l-1941 (MMK "Marina
    // Zadar | Zadar"), 21 m apart; one name since V9_79. Owner 13.10.2026: the
    // landing moves to "marina zadar", the old spelling 308s.
    name: 'Marina Zadar',
    dids: ['l-27', 'l-1941'],
    kind: LocationType.MARINA,
    countryCode: 'HR',
    aliases: ['Marina Zadar (ex. Tankerkomerc)', 'Marina Zadar | Zadar', 'Marina Zadar, Zadar'],
  },
  {
    // l-135 (NauSYS "Palairos") and l-828 (MMK "Marina Paleros | Palairos"),
    // 25 m apart; one name since V9_79. Owner 13.10.2026: "palairos" 308s.
    name: 'Marina Paleros',
    dids: ['l-135', 'l-828'],
    kind: LocationType.MARINA,
    countryCode: 'GR',
    aliases: ['Palairos', 'Marina Paleros | Palairos', 'Marina Paleros, Palairos'],
  },
  {
    // l-411 (NauSYS) and l-1550 (MMK, named after a charter agency until
    // V9_79 - an agency name is never public), 27 m apart. Owner 13.10.2026:
    // the old URL 308s. Server-only alias; never rendered.
    name: 'Marina di Golfo Aranci',
    dids: ['l-411', 'l-1550'],
    kind: LocationType.MARINA,
    countryCode: 'IT',
    aliases: ['Mare Fun Charter & Marina', 'Marina di Golfo Aranci | Golfo Aranci', 'Marina di Golfo Aranci, Golfo Aranci'],
  },
  {
    // l-710 (MMK "Olympic Marina | Lavrion") and l-139 (NauSYS "Lavrion -
    // Olympic Marine", coordinates were the town port): one marina (owner
    // 13.10.2026: "sigurno da, spoji"). The catalogue name is the marina's own
    // "Olympic Marine" since V9_79; the landing keeps its indexed URL.
    name: 'Olympic Marina',
    dids: ['l-139', 'l-710'],
    kind: LocationType.MARINA,
    countryCode: 'GR',
    aliases: [
      'Olympic Marine',
      'Lavrion - Olympic Marine',
      'Olympic Marine | Lavrion',
      'Olympic Marine, Lavrion',
      'Olympic Marina | Lavrion',
      'Olympic Marina, Lavrion',
    ],
  },
  {
    // l-222 (NauSYS "Olbia") is Marina di Olbia = l-609 (MMK), owner
    // 13.10.2026 "JE, SPOJI"; l-225 (NauSYS "Marina di Olbia Yachting
    // Services", no active boats) is already one row with l-609 in the list.
    // Catalogue name "Marina di Olbia" since V9_79; the landing keeps its
    // indexed URL. Molo Brin (l-1208) stays apart.
    name: 'Olbia',
    dids: ['l-222', 'l-609', 'l-225'],
    kind: LocationType.MARINA,
    countryCode: 'IT',
    aliases: ['Marina di Olbia', 'Marina di Olbia Yachting Services', 'Marina di Olbia | Olbia', 'Marina di Olbia, Olbia'],
  },
```

### 2. Alias teksta (`src/utils/static/curatedSeoSlug.ts`, `DESTINATION_ALIAS_SOURCE`, blok „Bases")

```ts
  'marina di golfo aranci': 'golfo-aranci',
```

Danas indeksirani `CATAMARAN` landing čita `catamaran-charter-mare-fun-charter-marina.html` (tekst nosi ime agencije, 9
jezika). Za „Marina di Golfo Aranci" ne postoji datoteka po imenu, pa bez aliasa landing nema teksta → noindex, a 9 URL-ova bi
dalo 308 na noindex stranicu. S aliasom čita `golfo-aranci-catamaran-charter.html` (tekst o Golfo Aranciju, bez agencije);
nijedno drugo mjesto ne čita taj prefiks (`Porto di Golfo Aranci` je `porto-di-golfo-aranci`). Fleet `l-411,l-1550`
CATAMARAN = 10 (prag je 10, isto kao danas).

### 3. Link-blokovi regija (`src/utils/server/landingNav.ts`, `src/utils/server/catalogueHubs.ts`) — isti web deploy kao pinovi

NauSys red i njegov MMK blizanac imaju **različite regije** (`location_region`, prod 13.10.): 609 Marina di Olbia je u 179
„Sardinia / Corsica" + 181, a 222/225 u 51 „Sardinia"; 2012 Netsel u 174 „Marmaris / Fethiye / Gocek", 185 u 85 „Aegean";
744 Andratx u 40 + 141 „Mallorca", 573 samo u 40; 1550 Golfo Aranci u 179, 411 u 51; 1813 Mandalina u 4 + 190 „Kornati", 49
samo u 4; 710 Olympic / 910 Zeas u 21 + 165, 139 / 116 u 24. `liesInside` (REGION) traži da **svi** `entry.dids` budu u regiji
(`/public/locations-count?regionId=` vraća pojedinačne redove), pa spojeni landing ispada iz bloka „Popularna odredišta" svih
svojih regija. Već sam pin „Olbia" (`l-609`) izbacuje Olbiju iz Sardinije: blok Sardinije danas ima točno 4 linka (Portisco
SAIL/CAT, Olbia CAT/SAIL = `MIN_POPULAR_LINKS`), s 2 pada na fallback „u Italiji". Nakon migracije isto za `marmaris / fethiye /
gocek` (Netsel), `sardinia / corsica` (Golfo Aranci), `mallorca` (Andratx), `kornati` (Mandalina), a Olympic Marina (već s
pinom) i Marina Zeas ispadaju iz regija 21 / 165 (Kiklade, Athens / Saronic Gulf). Breadcrumb
(`regionHubAbove`) gleda samo `place.dids[0]`.

```ts
// landingNav.ts, liesInside, grana REGION
return entry.kind === LocationType.MARINA && entry.dids.some(d => regionMarinas.has(d));

// catalogueHubs.ts, regionHubAbove (nakon guarda): regije svih redova mjesta, ne samo prvog
const countryCode = place.countryCode;
const names = [...new Set((await Promise.all(place.dids.map(did => regionsForMarina(countryCode, did)))).flat())];
```

(`itinerariesFor` u `landingNav.ts` isto čita samo `place.dids[0]` — ista izmjena po želji.) Olympic Marina i Marina Zeas time
ostaju u bloku Kiklada (MMK-ova regija), kao danas.

### 4. Tekst s imenom agencije — obrisati u ISTOM web commitu kao pinovi (ne prije)

`public/seo-content/{de,en,es,fr,hr,it,nl,pl,pt}/catamaran-charter-mare-fun-charter-marina.html` (9 datoteka) danas je tekst
indeksiranog Golfo Aranci CAT landinga i nosi ime agencije u svih 9 jezika. Javno je dostupan:
`https://www.boat4you.com/seo-content/en/catamaran-charter-mare-fun-charter-marina.html` → 200 (samo `X-Robots-Tag: noindex`).
Nakon pina „Marina di Golfo Aranci" nitko ga više ne čita (landing čita `golfo-aranci-catamaran-charter.html` preko aliasa), pa
se briše u istom commitu (`git rm`); prije pina bi indeksirani landing ostao bez teksta (noindex). Ime agencije nikad javno.

## Što se događa s indeksiranim URL-ovima

„Prije migracije" = pinovi live, migracija još ne. Svi brojevi su URL-ovi × 9 jezika iz današnjeg sitemapa.

| Mjesto | Danas indeksirano | Danas | Samo pinovi | Migracija bez pinova | Migracija + pinovi |
|---|---|---|---|---|---|
| #4 Marina Zadar | `marina zadar (ex. tankerkomerc)` 27 (CAT, MOTORBOAT, SAIL) | 200 `l-1941,l-27` | 308 → `marina zadar` | **404** | 308 → `marina zadar` (Mario) |
| #14 Marina Paleros | `palairos` 9 (SAIL) | 200 `l-135` | 308 → `marina paleros` | **404** | 308 → `marina paleros` (Mario) |
| | `marina paleros` 9 (SAIL) | 200 `l-828` | 200 `l-135,l-828` | 200 `l-135,l-828` | 200 `l-135,l-828` |
| #36 Golfo Aranci | agencijsko ime 9 (CAT) | 200 `l-1550` | 308 → `marina di golfo aranci` | **404** | 308 → `marina di golfo aranci` (Mario) |
| Olympic Marine | `olympic marina` 18 (CAT, SAIL) | 200 `l-710` | 200 `l-139,l-710` | **404** | 200 `l-139,l-710` (isti URL) |
| | `lavrion - olympic marine` 9 (SAIL) | 200 `l-139` | 308 → `olympic marina` | **404** | 308 → `olympic marina` |
| Marina di Olbia | `olbia` 18 (CAT, SAIL) | 200 `l-222` | 200 `l-222,l-225,l-609` | **404** | 200 `l-222,l-225,l-609` (isti URL) |
| | `marina di olbia yachting services` 9 (CAT) | 200 `l-225,l-609` | 308 → `olbia` | 200 `l-222,l-225,l-609` | 308 → `olbia` |
| #9 Lavrion | `lavrion main port` 18 (CAT, SAIL) | 200 `l-1069,l-150` (pin) | 200 + `l-2022` | 200 bez `l-2022` | 200 `l-1069,l-150,l-2022` |
| E ACI Marina Split | `aci marina split` 36 | 200 `l-69` | isto | 200 `l-1755,l-69` | 200 `l-1755,l-69` (bez pina) |
| ostalih 14 (MMK ime ostaje) | 189 | 200 `l-<MMK>` | isto | 200 + NauSys red | isto (bez pina) |

Ostalih 14 (isti kanonski URL, landing dobiva brodove NauSys reda): `d-marin marina mandalina` 36 (+49), `marina portorosa` 18
(+245), `marina benitses` 18 (+458), `netsel marmaris marina` 18 (+185), `paros port` 18 (+590), `bodrum marina` 9 (+202),
`marina kos` 9 (+157), `marina villa igiea` 9 (+227), `marina zeas` 9 (+116), `port of split / west harbour` 9 (+521),
`puerto de andratx` 9 (+573), `salamis yachting club` 9 (+462), `sami port` 9 (+450), `sant antoni de portmany port` 9 (+399).
`hyeres port saint pierre` 18 već danas ima oba reda (spojeni po imenu), ostaje isti.

Od 169 indeksiranih odredišta samo ova 24 mijenjaju razrješenje; nijedno ne pada ispod današnje flote ni ispod praga 10.

**Dvostruki oglasi (review 13.10.):** `yacht_listing_twin` (V9_69/V9_70) prepoznaje dva primjerka istog broda po imenu baze. Kad
oba reda mjesta dobiju isto ime, MMK i NauSys primjerci **131 broda** postaju blizanci i prvo osvježavanje (≤ 10 min) skriva drugi
primjerak iz nedatirane pretrage, brojki landinga, sitemapa i facts (B17, jedna kartica po brodu; 557 → 688). Prikazani primjerak
je uvijek u istom mjestu. Zato su brojke spajanja niže od zbroja oba reda. Fleet po tipu (`/public/yachts` 13.10. s oba did-a →
nakon osvježavanja blizanaca): Marina Zadar CAT 13 → 13 / MOTORBOAT 15 → 14 / SAIL 91 → 78; Marina Paleros SAIL 27 → 22; Golfo
Aranci CAT 10 → 10 (na pragu, kao danas); Olympic CAT 33 → 33 / SAIL 83 → 71; Olbia CAT 64 / SAIL 38 (bez promjene) — svi ≥ 10,
tekstovi postoje (uz alias gore). Landinzi koji danas broje samo MMK red ne mogu pasti ispod današnje brojke (svaki skriveni
primjerak ima prikazani par istog tipa u istom mjestu).

Sitemap (nakon migracije + osvježavanja blizanaca):
- **izlazi 63:** 45 odobrenih 308 (Zadar 27, Palairos 9, Golfo Aranci 9) + 18 spajanja duplikata (`lavrion - olympic marine` 9,
  `marina di olbia yachting services` 9);
- **ulazi ~108:** `marina zadar` 27 i `marina di golfo aranci` 9, plus nova mjesta koja tek sad imaju ime bez zareza ili prelaze
  prag (tekstovi postoje u 9 jezika): Marina Baotić CAT 136 / MOTORBOAT 20 / MOTOR_YACHT 16 (27), Marina Agana CAT 14 / SAIL 35
  (18), Marina Kos CAT 29 (9), Paros port SAIL 20 (9), Port of Split / West Harbour MOTOR_YACHT 10 (9, točno na pragu — može
  ispasti). Reial Club Nàutic Port de Pollença SAIL 12 → 7 nakon blizanaca → **ne** ulazi. Baotić POWER_CATAMARAN 7 i SAIL (bez
  teksta) ne ulaze. To je očekivano, nije greška pri provjeri nakon deploya.

## Stara pisanja koja nisu indeksirana (noindex danas) → 404 nakon migracije

Pin nije potreban za SEO (nisu u sitemapu, danas noindex ili 308 na noindex): `naples` (209), `palermo` (227), `vodice` (45),
`kotor`, `riposto`, `cannes`, `igy vieux-port de cannes`, `port of denia`, `real club nautico dènia`, `port olimpic`,
`corbieres port`, `gumbet limani`, `luka baška voda`, `luka kaštel stari`, `marina stobreč`, `marina molo luise`,
`mergelina e porto di sannazaro`, `port of kavala`, `trget (near pula)`, `nea peramos`, `yacht classic hotel`,
`bodrum/turgutreis/d-marin`, `lavrion marina` (pokriva pin Lavrion).

Opcionalno, jedini koji danas 308 vodi na indeksirani landing:

```ts
  { name: 'Hyeres Port Saint Pierre', dids: ['l-346', 'l-1975'], kind: LocationType.MARINA, countryCode: 'FR',
    aliases: ['Hyeres', 'Hyeres Port Saint Pierre | Hyeres', 'Hyeres Port Saint Pierre, Hyeres'] },
```

## Provjera (pinovi live, prije i poslije migracije isto)

```
/search?destinations=marina%20zadar%20(ex.%20tankerkomerc)&boatTypes=CATAMARAN -> 308 destinations=marina%20zadar&boatTypes=CATAMARAN
/search?destinations=marina%20zadar&boatTypes=SAILING_YACHT                       -> 200, canonical isti, index
/search?destinations=palairos&boatTypes=SAILING_YACHT                            -> 308 marina%20paleros
/search?destinations=marina%20di%20golfo%20aranci&boatTypes=CATAMARAN            -> 200, index (alias teksta!)
/search?destinations=olympic%20marina&boatTypes=SAILING_YACHT                    -> 200, canonical isti, index
/search?destinations=lavrion%20-%20olympic%20marine&boatTypes=SAILING_YACHT      -> 308 olympic%20marina
/search?destinations=olbia&boatTypes=CATAMARAN                                   -> 200, canonical isti, index
/search?destinations=marina%20di%20olbia%20yachting%20services&boatTypes=CATAMARAN -> 308 olbia
/search?destinations=lavrion%20main%20port&boatTypes=CATAMARAN                   -> 200, canonical isti
/search?destinations=aci%20marina%20split&boatTypes=CATAMARAN                    -> 200, canonical isti (nakon migracije)
```

## Zatečeno / za Marija

- **#25 Tourlos Marina NIJE u V9_79.** NauSys 158 „Mykonos" je danas po imenu dio indeksiranog landinga „Port of Mykonos"
  (`l-158,l-1912`, 18 URL-ova: CATAMARAN + odredišni). Preimenovan u „Tourlos Marina", Port of Mykonos ostaje s 9 brodova
  (3 katamarana, prag 10) → 18 URL-ova pada u noindex, a Tourlos Marina dobiva 11 brodova (CAT 8 → 19). Pitanje za Marija:
  spojiti ipak (Port of Mykonos gubi 18 indeksiranih URL-ova) ili ostaviti. Ako DA: jedno mjesto više u migraciji (158 →
  „Tourlos Marina", grad „Tourlos"; par 158/1561), bez web pina (`tourlos marina` ostaje isti URL).
- **Sestrinski sajtovi traže mjesto po imenu grada** (`resolveDestinationDid` → `/public/locations?name=`, ime + grad). Kad
  NauSys ime izgubi grad/otok, red ispada iz te pretrage: „Trogir" gubi Marinu Baotić (57 + 1749, ~306 brodova) i Agana (55),
  „Ibiza" 399, „Naples" Pozzuoli 257, „Corsica"/„Sicily"/„Mallorca"/„Athens"/„Corfu"/„Tivat"/„Santorini" po nekoliko brodova.
  Regije se razrješavaju prije marina, pa to vrijedi samo za imena bez regije — najviše za **Trogir** (CC/CY/EY itinerari iz
  Trogira). Prijedlog za sestre: `SEARCH_ALIASES` `'trogir': ['Trogir', 'Seget']`. Na boat4you itinerar-CTA „Trogir" prelazi s
  Baotića na ACI Marinu Trogir, „Ajaccio" na lanac (Korzika → Francuska).
- **NauSys 225 „Marina di Olbia Yachting Services"** (0 aktivnih brodova) je već danas po imenu jedan red s 609; najduže ime
  i dalje imenuje red liste i facts („Marina di Olbia Yachting Services"). Ako i 225 treba „Marina di Olbia", to je jedan
  dodatni red (Mariov DA).
- **MMK 1375 „Placencia"** (~5 km južno od Robert's Grove): tamo nema baziranog broda, ali je mjesto preuzimanja za **3 broda
  Robert's Grove** (MMK 1302): 346 budućih bookabilnih ponuda (prod 13.10. 17:23 UTC). Danas ga lista po imenu spaja s Robert's
  Grove (`l-265,l-1375,l-1302`); nakon #51 ta preuzimanja idu u zaseban red liste „Placencia", a red Robert's Grove je
  `l-265,l-1302`. Fizički točno (5 km), ali Mario odlučuje: ostaje zaseban ili par 1375 ↔ Robert's Grove. (Belize nije
  promovirana zemlja, pa facts nisu pogođeni.)
- **Charter facts, NauSys 233 „Milazzo"** (preuzimanje 1 broda iz Portorose, 13 tjednih ponuda, nije blizanac): danas je u facts
  mjestu Poseidon Marina samo zato što je „milazzo" unutar „Marina Poseidon, Milazzo"; nakon #34 je zasebno mjesto (1,3 km od
  Poseidona). Nema indeksiranog landinga. Mario: par 233 ↔ Poseidon ili ostaviti. Ako 212 „Marina Santa Maria Maggiore,
  Milazzo" (danas bez brodova i ponuda) jednom dobije podatke, 233 se po imenu spaja s njim.
- **boat4you autocomplete „trogir"** (`search_filed` = ime + grad, regija „Trogir" ne postoji): nakon migracije više ne nalazi
  Marinu Baotić (57 + 1749, ~306 brodova) ni Marinu Agana (55 + 993) — NauSys imena su nosila „Trogir", gradovi MMK blizanaca
  su „Seget Donji" / „Marina". Ostaju ACI Marina Trogir, SCT Marina Trogir i ostali. Opcije za Marija: prihvatiti, ili 57 (i
  55) dobiva grad „Trogir" (kartice bi tada imale dva različita grada za istu marinu). Manji gubici: `naples` (Pozzuoli 16),
  `tivat`, `santorini`, `kefalonia` po 1–2 broda.
- **Povučeni brodovi (308):** 22 retka `yacht_successor` pokazuju na primjerak koji postaje blizanac; idući izračun (07:55)
  ih prebacuje na prikazani primjerak istog broda (svih 22 i dalje prolazi pravilo nasljednika, nijedan ne gubi 308).
- Tekst s imenom agencije: vidi „OBAVEZNO" točka 4 (briše se u istom web commitu kao pinovi).
- `location_region` nema jedinstveni ključ; ACI Marina Split (69) ima regiju 5 dvaput (MMK + NauSys sink). Nije dirano.
- Rendering grupa u listi (Lavrion 3, Baška Voda 3, Mergellina 5 redova) treba backend kod iz istog paketa
  (`mergeDualSourceMarinas` spaja grupu); stari jar prikazuje grupu kao 2–3 reda istog imena (tražilica je i tada ispravna).
