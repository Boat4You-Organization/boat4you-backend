# Web pinovi za V9_79 (bliske duple marine), 13.10.2026

Backend migracija `V9_79__location_near_duplicate_marinas_one_name.sql` daje svakom mjestu koje je Mario odobrio
(`docs/location-near-duplicates-review-2026-10-13.md`: „A sve da", „C može", plus Olympic Marine, Marina di Olbia i ACI
Marina Split) jedno ime i upisuje parove u `location_same_place`. boat4you-web landing nosi ime reda s najvećom flotom
(`destinationDid.ts`), URL je to ime malim slovima, a druga pisanja dobivaju 308 samo dok ih nešto još razrješava
(`landingRedirectPath`). Ovaj dokument kaže koji pinovi (`src/config/landing-identity.config.ts`) i koji alias teksta
(`src/utils/static/curatedSeoSlug.ts`) moraju biti LIVE **prije** migracije.

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

Bez ovoga **90 indeksiranih URL-ova daje 404**, a `marina di golfo aranci` nema tekst (noindex).

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

Od 169 indeksiranih odredišta samo ova 24 mijenjaju razrješenje; nijedno ne gubi brodove. Fleet po tipu nakon spajanja
(`/public/yachts`): Marina Zadar CAT 13 / MOTORBOAT 15 / SAIL 91; Marina Paleros SAIL 27; Golfo Aranci CAT 10; Olympic CAT 33 /
SAIL 83; Olbia CAT 64 / SAIL 38 — svi iznad praga 10, tekstovi postoje (uz alias gore).

Sitemap: 45 URL-ova odobrenih 308 (Zadar 27, Palairos 9, Golfo Aranci 9) i 18 spajanja duplikata (Olympic 9, Olbia 9) izlaze iz
sitemapa; ulaze `marina zadar` 27 i `marina di golfo aranci` 9. Moguće nove stranice kad prođu prag i imaju vlastiti tekst:
„Marina Baotić" (306 brodova, tekstovi CAT / MOTOR_YACHT / MOTORBOAT / POWER_CATAMARAN — danas je ime sa zarezom i nema landing),
veće flote ostalih spojenih marina.

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
- **MMK 1375 „Placencia"** (0 aktivnih brodova, ~5 km od Robert's Grove) bio je po imenu spojen s Robert's Grove; nakon
  preimenovanja #51 je zaseban red liste „Placencia".
- Tekst `catamaran-charter-mare-fun-charter-marina.html` (9 jezika) nosi ime agencije; nakon 308 se više ne prikazuje — može
  se obrisati.
- `location_region` nema jedinstveni ključ; ACI Marina Split (69) ima regiju 5 dvaput (MMK + NauSys sink). Nije dirano.
- Rendering grupa u listi (Lavrion 3, Baška Voda 3, Mergellina 5 redova) treba backend kod iz istog paketa
  (`mergeDualSourceMarinas` spaja grupu); stari jar prikazuje grupu kao 2–3 reda istog imena (tražilica je i tada ispravna).
