# Duplikati lokacija (marine) — prod, 13.10.2026

Izvještaj za Marija, **samo čitanje** (cusma4 `boat4you_db`, `BEGIN READ ONLY`, 13.10.2026). Ništa nije spojeno ni
preimenovano: svaki par ispod čeka Mariov DA. Iznimka je Marina Kaštela (63 / 1935) — za nju je pripremljen
parkirani data fix `docs/pending-migrations/LOCATION_KASTELA_fix.sql` (još nije migracija ni pušten).

## Kako je mjereno

- **Aktivna lokacija** = ima barem jedan aktivan brod (`yacht.sys_active`, matična baza broda `yacht.location_id`).
- **Duplikat po imenu** (ista država): ime isto do na dijakritike / velika slova / interpunkciju (`Lavrion, main port`
  = `Lavrion Main Port`), i/ili se razlikuje samo za grad kao sufiks ili prefiks (`Marina Lav - Podstrana` = `Marina
  Lav` + grad Podstrana; NauSys `Zadar, D-Marin Borik` = MMK `D-Marin Borik` + grad Zadar).
- **Udaljenost** = zračna linija između koordinata dvaju redova. **Izvor** = partner čiji `external_mapping`
  (type `Location`) pokazuje na red; MMK piše grad u `city`, NauSys grad nikad ne šalje (ime često počinje gradom).
- **Tražilica** = rješava li `did=l-<id>` oba reda zajedno (`LocationRepository.findSamePlaceMarinaIds`: isto ime do
  na hrvatske dijakritike `šžčćđ`, < 50 km, ili kurirani par `location_same_place`). Ako ne, odabir jednog reda u
  tražilici **ne pokazuje brodove drugog reda**.
- **Lista** = `/public/locations` (autocomplete): jedan red (`containment` = jedno ime unutar drugog, ≤ 20 km;
  `curated` = `location_same_place`) ili dva reda. Procjena izvan aplikacije, na svim lokacijama s brodovima.

## Sažetak

- **16 grupa** s barem dva aktivna reda: **15 su isto mjesto** (redovi ≤ 1,5 km, zajedno
  702 aktivnih brodova), 1 je imenjak na drugom mjestu (ne dirati).
- Tražilica ih danas spaja **4**; **11 su razdvojene** (odabir jednog reda
  skriva flotu drugog) — najveće: Lavrion Main Port (133), Preveza Main Port (113), Adaköy Marina (40), Marina Lav - Podstrana (21).
- U listi (autocomplete) 10 od 15 parova se vidi kao **dva reda**.
- Kartice brodova pokazuju `display_name` svog reda, pa istu marinu zovu dvama imenima (Kaštela, Skiathos, Siracusa…).
- Sinkronizacija (MMK / NauSys) **ne vraća** ime nakon preimenovanja (ime se piše samo kod prvog kreiranja); MMK
  svaki put prepiše `city` svojim gradom — ali samo na redovima koje MMK mapira. Zato je recept za svaki par:
  **NauSys red preuzme MMK ime + grad** (NauSys ne piše ni ime ni grad) **+ kurirani par** u `location_same_place`
  (lista = jedan red, tražilica = jedno mjesto i kad se imena razlikuju u interpunkciji). Bez brisanja redova i bez
  prebacivanja ponuda — `l-<id>` linkovi ostaju živi. Isti obrazac kao Kaštela.

## Isto mjesto, dva reda

| # | Država | Red (id · ime · grad) | `display_name` | Koordinate | Akt. brodova | Izvor | Udaljenost | Tražilica | Lista |
|---|---|---|---|---|---|---|---|---|---|
| 1 | HR | 63 · Marina Kastela · — | Marina Kastela | 43.54548, 16.40480 | 159 (NauSys=159) | NauSys `NauSys:54` | 281 m | jedno mjesto | **2 reda** |
|  |  | 1935 · Marina Kaštela · Kaštel Gomilica | Marina Kaštela \| Kaštel Gomilica | 43.54602, 16.40140 | 135 (MMK=135) | MMK `MMK:119` |  |  |  |
| 2 | GR | 1069 · Lavrion Main Port · Lavrion | Lavrion Main Port \| Lavrion | 37.71099, 24.05771 | 70 (MMK=70) | MMK `MMK:18220780000100000` | 209 m | **razdvojeno** | **2 reda** |
|  |  | 150 · Lavrion, main port · — | Lavrion, main port | 37.71230, 24.05600 | 63 (NauSys=63) | NauSys `NauSys:125450` |  |  |  |
| 3 | GR | 835 · Preveza Main Port · Preveza | Preveza Main Port \| Preveza | 38.95765, 20.75521 | 58 (MMK=58) | MMK `MMK:1935994390000100000` | 400 m | **razdvojeno** | **2 reda** |
|  |  | 134 · Preveza, Main Port · — | Preveza, Main Port | 38.95405, 20.75526 | 55 (NauSys=55) | NauSys `NauSys:828255` |  |  |  |
| 4 | GR | 2017 · Skiathos · Skiathos | Skiathos \| Skiathos | 39.16469, 23.49209 | 39 (MMK=39) | MMK `MMK:21` | 363 m | jedno mjesto | **2 reda** |
|  |  | 160 · Skiathos · — | Skiathos | 39.16188, 23.48995 | 13 (NauSys=13) | NauSys `NauSys:476604` |  |  |  |
| 5 | TR | 904 · Adaköy Marina · Marmaris | Adaköy Marina \| Marmaris | 36.81613, 28.28812 | 35 (MMK=35) | MMK `MMK:1103044210000100000` | 523 m | **razdvojeno** | **2 reda** |
|  |  | 204 · Marmaris Adakoy Marina · — | Marmaris Adakoy Marina | 36.81851, 28.29319 | 5 (NauSys=5) | NauSys `NauSys:2458139` |  |  |  |
| 6 | HR | 72 · Marina Lav - Podstrana · — | Marina Lav - Podstrana | 43.49169, 16.53831 | 17 (NauSys=17) | NauSys `NauSys:934568` | 70 m | **razdvojeno** | 1 red (containment) |
|  |  | 1423 · Marina Lav · Podstrana | Marina Lav \| Podstrana | 43.49148, 16.53750 | 4 (MMK=4) | MMK `MMK:1237751930000100000` |  |  |  |
| 7 | HR | 1413 · D-Marin Borik · Zadar | D-Marin Borik \| Zadar | 44.12995, 15.20998 | 14 (MMK=14) | MMK `MMK:667400400000100000` | 127 m | **razdvojeno** | 1 red (containment) |
|  |  | 26 · Zadar, D-Marin Borik · — | Zadar, D-Marin Borik | 44.13107, 15.20965 | 1 (NauSys=1) | NauSys `NauSys:4963592` |  |  |  |
| 8 | HR | 1571 · Marina Baška Voda · Baška Voda | Marina Baška Voda \| Baška Voda | 43.35769, 16.94777 | 7 (MMK=7) | MMK `MMK:30782240000100000` | 142 m | jedno mjesto | **2 reda** |
|  |  | 76 · Marina Baska Voda · — | Marina Baska Voda | 43.35781, 16.94603 | 1 (NauSys=1) | NauSys `NauSys:477801` |  |  |  |
| 9 | ES | 548 · Denia, Marina de Dénia · — | Denia, Marina de Dénia | 38.84163, 0.12081 | 5 (NauSys=5) | NauSys `NauSys:55192998` | 38 m | **razdvojeno** | **2 reda** |
|  |  | 848 · Marina de Dènia · Dènia | Marina de Dènia \| Dènia | 38.84179, 0.12120 | 2 (MMK=2) | MMK `MMK:6241768840000100000` |  |  |  |
| 10 | NO | 396 · Tromso · — | Tromso | 69.64906, 18.96042 | 3 (NauSys=3) | NauSys `NauSys:482455` | 1.5 km | **razdvojeno** | **2 reda** |
|  |  | 624 · Tromsø · Tromsø | Tromsø \| Tromsø | 69.65282, 18.99748 | 2 (MMK=2) | MMK `MMK:183988270000100000` |  |  |  |
| 11 | HR | 1950 · Marina Admiral · Opatija | Marina Admiral \| Opatija | 45.32721, 14.30052 | 3 (MMK=3) | MMK `MMK:101` | 157 m | **razdvojeno** | 1 red (containment) |
|  |  | 13 · Marina Admiral Opatija · — | Marina Admiral Opatija | 45.32862, 14.30057 | 1 (NauSys=1) | NauSys `NauSys:842727` |  |  |  |
| 12 | IT | 229 · Siracusa · — | Siracusa | 37.06724, 15.28948 | 2 (NauSys=2) | NauSys `NauSys:482349` | 364 m | jedno mjesto | **2 reda** |
|  |  | 691 · Siracusa · Siracusa | Siracusa \| Siracusa | 37.06884, 15.29306 | 2 (MMK=2) | MMK `MMK:150` |  |  |  |
| 13 | HR | 65 · Marina Spinut, Split · — | Marina Spinut, Split | 43.51488, 16.41787 | 1 (NauSys=1) | NauSys `NauSys:100510` | 17 m | **razdvojeno** | **2 reda** |
|  |  | 1421 · Marina Špinut · Split | Marina Špinut \| Split | 43.51500, 16.41800 | 1 (MMK=1) | MMK `MMK:485998870000100000` |  |  |  |
| 14 | IT | 2007 · Porto di Mergellina · Naples | Porto di Mergellina \| Naples | 40.82704, 14.22257 | 1 (MMK=1) | MMK `MMK:31` | 181 m | **razdvojeno** | 1 red (containment) |
|  |  | 2099 · Naples, Porto di Mergellina · — | Naples, Porto di Mergellina | 40.82629, 14.22448 | 1 (NauSys=1) | NauSys `NauSys:75354092` |  |  |  |
| 15 | TR | 502 · Göcek/Skopea Marina · — | Göcek/Skopea Marina | 36.75456, 28.93791 | 1 (NauSys=1) | NauSys `NauSys:24457720` | 72 m | **razdvojeno** | 1 red (containment) |
|  |  | 780 · Skopea Marina · Göcek | Skopea Marina \| Göcek | 36.75439, 28.93869 | 1 (MMK=1) | MMK `MMK:3593440780000100000` |  |  |  |

## Isto ime, različito mjesto (ne spajati)

| Država | Red (id · ime · grad) | Koordinate | Akt. brodova | Izvor | Udaljenost |
|---|---|---|---|---|---|
| HR | 2029 · Marina Frapa · Rogoznica | 43.53169, 15.96228 | 144 | MMK+NauSys `MMK:7 NauSys:59 NauSys:10388882` | 197.7 km |
|  | 775 · Marina Frapa Dubrovnik · Dubrovnik | 42.65747, 18.08210 | 10 | MMK `MMK:2687769540000100000` |  |

Marina Frapa (Rogoznica) i Marina Frapa Dubrovnik su različite marine; tražilica i lista ih već drže odvojeno
(audit B14, 26.9.2026).

## Ime koje dijele dva reda (rizik za sinkronizaciju)

Točno isto ime (bez obzira na velika slova) na dva reda: `marigot bay` (837, 983), `port calanova` (1516, 1781),
`siracusa` (229, 691), `skiathos` (160, 2017), `sorrento` (242, 1809). Kad bi partner poslao NOVU bazu takvog imena,
stari `findByNameIgnoreCase` bacio bi grešku i cijela sinkronizacija lokacija tog partnera bi se vratila. Popravljeno u
kodu (isti commit kao lat/lon): uzima se red s najmanjim id-em. Nakon Kaštela fixa dodaje se i `marina kaštela` (63, 1935).

## Dodatak: različita imena, iste koordinate (kandidati za ručni pregled)

Nije po pravilu imena — parovi aktivnih redova iste države ≤ 150 m, jedan samo NauSys, drugi samo MMK (tipičan
dvostruki uvoz iste marine). Neki su stvarno različite marine u istoj luci (provjeriti prije bilo kakve odluke).
Top 20 po broju brodova:

| Udaljenost | NauSys red | Brodova | MMK red | Brodova | Lista danas |
|---|---|---|---|---|---|
| 79 m | 57 · Trogir, Yachtclub Seget (Marina Baotić) | 252 | 1749 · Marina Baotić \| Seget Donji | 54 | 1 red (containment) |
| 126 m | 49 · Marina Mandalina, Sibenik | 87 | 1813 · D-Marin Marina Mandalina \| Šibenik | 149 | 2 reda |
| 11 m | 245 · Sicily, Portorosa | 47 | 1831 · Marina Portorosa \| Furnari | 134 | 2 reda |
| 21 m | 27 · Marina Zadar (ex. Tankerkomerc) | 55 | 1941 · Marina Zadar \| Zadar | 76 | 1 red (containment) |
| 136 m | 157 · Kos, Kos Marina | 86 | 2023 · Marina Kos \| Kos | 35 | 2 reda |
| 111 m | 444 · Fethiye, Ece Saray Marina Resort | 77 | 1252 · Yacht Classic Hotel \| Fethiye | 32 | 2 reda |
| 131 m | 465 · Athens, Attica, Nea Peramos Marina | 8 | 1937 · Nea Peramos \| Nea Peramos - Attica | 85 | 1 red (containment) |
| 68 m | 202 · Bodrum, Milta Marina | 17 | 2013 · Bodrum Marina \| Bodrum | 62 | 2 reda |
| 95 m | 150 · Lavrion, main port | 63 | 2022 · Lavrion Marina \| Lavrion | 7 | 2 reda |
| 110 m | 399 · Ibiza, Club Nàutic Sant Antoni de Portmany | 17 | 1434 · Sant Antoni de Portmany Port \| Sant Antoni de Portmany | 44 | 2 reda |
| 140 m | 590 · Paros, Paroikia Marina | 31 | 1845 · Paros port \| Paros | 30 | 2 reda |
| 52 m | 521 · Split, West Coast (Zapadna obala) | 11 | 1693 · Port of Split / West Harbour \| Split | 43 | 2 reda |
| 72 m | 185 · Marmaris, Netsel Marina | 8 | 2012 · Netsel Marmaris Marina \| Marmaris | 46 | 2 reda |
| 25 m | 135 · Palairos | 26 | 828 · Marina Paleros \| Palairos | 26 | 2 reda |
| 142 m | 55 · Marina, Marina Agana (near Trogir) | 2 | 993 · Marina Agana \| Marina | 48 | 1 red (containment) |
| 147 m | 359 · Polynesia, Raiatea | 41 | 1979 · Marina de Uturoa \| Raiatea | 6 | 2 reda |
| 103 m | 222 · Olbia | 42 | 1208 · Molo Brin \| Olbia | 3 | 2 reda |
| 33 m | 346 · Hyeres Port Saint Pierre | 31 | 1975 · Hyeres \| Hyeres | 12 | 1 red (containment) |
| 30 m | 205 · Fethiye, Yacht Classic Hotel Marina | 10 | 1252 · Yacht Classic Hotel \| Fethiye | 32 | 1 red (containment) |
| 116 m | 546 · Palermo, Marina di La Cala | 18 | 738 · La Cala (Porto Palermo) \| Palermo | 15 | 2 reda |

Ukupno takvih parova: 58 (≤ 150 m, NauSys + MMK). Popis je sirov; kurirani par (`location_same_place`)
je alat za one koje Mario potvrdi.

## Kako ponoviti

Izvoz `location` (+ broj aktivnih brodova, `external_mapping`, regije, kurirani parovi) iz cusma4 u `BEGIN READ ONLY`,
pa usporedba izvan baze (Python: NFD fold, haversine). Skripta nije u repou (jednokratna analiza).
