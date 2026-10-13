# Web pinovi za V9_78 (duple marine), 13.10.2026

Backend migracija `V9_78__location_dual_source_marinas_one_name.sql` preimenuje NauSys red svakog para u ime i grad
MMK reda i upisuje par u `location_same_place` (Mario 13.10.2026: „da, spoji sve duple marine"). boat4you-web landing
nosi ime reda s najvećom flotom (`destinationDid.ts`), URL je to ime malim slovima, a sva druga pisanja dobivaju 308
(`landingRedirectPath`). Ovaj dokument kaže koji landing pin (`src/config/landing-identity.config.ts`) mora biti LIVE
**prije** migracije, da nijedan indeksirani kanonski URL ne promijeni adresu.

## Kako je provjereno (13.10.2026, samo čitanje)

- Live `sitemap-categories.xml` (2.916 URL-ova) i `sitemap-locations.xml` (531): grep po imenima svih parova.
- Live `/search?destinations=…` za 31 pisanje (browser UA, ≥ 1,2 s razmaka): status, 308 cilj, `canonical`, `robots`.
- Simulacija nakon migracije: prod redovi `location` + `external_mapping` (cusma4, `BEGIN READ ONLY`), backend spajanje
  liste (`LocationQueryingService.mergeDualSourceMarinas` + `MarinaPlaces.containmentPairs`) i web resolver
  (`requireDestinationIndex` + `resolveDestinationName`) prepisani 1:1. Simulacija danas daje točno živu listu
  (784/784 marina reda, ista spojena imena) i istih 31 rezultata kao live probe.

## OBAVEZNO prije V9_78

**Marina Lav - Podstrana** (HR, l-72 NauSys 17 aktivnih brodova + l-1423 MMK 4, 70 m). Danas je 9 URL-ova indeksirano
(`MOTOR_YACHT` × 9 jezika), kanonski `/search?destinations=marina%20lav%20-%20podstrana&boatTypes=MOTOR_YACHT`, a
`marina lav` 308 na njega. Nakon preimenovanja nijedan red više ne nosi ime „Marina Lav - Podstrana": bez pina ti URL-ovi
daju **404** (nepoznata destinacija), a landing se seli na `marina%20lav` (i čita drugi tekst iz korpusa,
`motor-yacht-charter-marina-lav.html`). S pinom je sve kao danas (isti dids `l-1423,l-72`, isti tekst).

```ts
  {
    // l-72 (NauSYS "Marina Lav - Podstrana") and l-1423 (MMK "Marina Lav |
    // Podstrana") are one marina, 70 m apart (owner 13.10.2026: "spoji sve
    // duple marine"). The landings are indexed under the NauSYS name; the
    // backend rename of l-72 to "Marina Lav" (V9_78) must not move that URL.
    name: 'Marina Lav - Podstrana',
    dids: ['l-72', 'l-1423'],
    kind: LocationType.MARINA,
    countryCode: 'HR',
    aliases: ['Marina Lav', 'Marina Lav | Podstrana', 'Marina Lav, Podstrana'],
  },
```

## PREPORUČENO (nema promjene URL-a ni bez pina)

Ova četiri landinga su indeksirana i nakon migracije zadržavaju isti kanonski URL i bez pina: spojeni red nosi današnje
ime. Pin samo veže URL na id-eve, isto kao Kaštela. Kod Adaköya pin već prije migracije dodaje l-204 (5 aktivnih
brodova) u landing i daje 308 starom (noindex) `marmaris adakoy marina`, koji bi inače bio 404.

```ts
  {
    // l-150 (NauSYS "Lavrion, main port") and l-1069 (MMK "Lavrion Main Port |
    // Lavrion"), 209 m apart; one name since V9_78.
    name: 'Lavrion Main Port',
    dids: ['l-150', 'l-1069'],
    kind: LocationType.MARINA,
    countryCode: 'GR',
    aliases: ['Lavrion, main port', 'Lavrion Main Port | Lavrion', 'Lavrion Main Port, Lavrion'],
  },
  {
    // l-134 (NauSYS "Preveza, Main Port") and l-835 (MMK "Preveza Main Port |
    // Preveza"), 400 m apart; one name since V9_78.
    name: 'Preveza Main Port',
    dids: ['l-134', 'l-835'],
    kind: LocationType.MARINA,
    countryCode: 'GR',
    aliases: ['Preveza, Main Port', 'Preveza Main Port | Preveza', 'Preveza Main Port, Preveza'],
  },
  {
    // l-160 (NauSYS "Skiathos") and l-2017 (MMK "Skiathos | Skiathos"), 363 m
    // apart; one place since V9_78.
    name: 'Skiathos',
    dids: ['l-160', 'l-2017'],
    kind: LocationType.MARINA,
    countryCode: 'GR',
    aliases: ['Skiathos | Skiathos', 'Skiathos, Skiathos'],
  },
  {
    // l-204 (NauSYS "Marmaris Adakoy Marina") and l-904 (MMK "Adaköy Marina |
    // Marmaris"), 523 m apart; one name since V9_78.
    name: 'Adaköy Marina',
    dids: ['l-204', 'l-904'],
    kind: LocationType.MARINA,
    countryCode: 'TR',
    aliases: ['Marmaris Adakoy Marina', 'Adaköy Marina | Marmaris', 'Adaköy Marina, Marmaris'],
  },
```

## Po paru

„Indeksirano" = URL je u sitemapu i ima `index, follow`. Odredišni landing bez tipa broda je za sva ova mjesta noindex.

| # | Mjesto (NauSys / MMK red) | Landing danas | Kanonski danas | Nakon V9_78 bez pina | Pin |
|---|---|---|---|---|---|
| 2 | Lavrion Main Port (150 / 1069) | 18 indeksiranih (CATAMARAN, SAILING_YACHT) | `lavrion%20main%20port` | isti URL, dids `l-1069,l-150` | preporučeno |
| 3 | Preveza Main Port (134 / 835) | 18 indeksiranih (CATAMARAN, SAILING_YACHT) | `preveza%20main%20port` | isti URL, dids `l-134,l-835`; nestaje krhkost: danas bi NauSys red s većom flotom („Preveza, Main Port", zarez) srušio landing | preporučeno |
| 4 | Skiathos (160 / 2017) | 18 indeksiranih (CATAMARAN, SAILING_YACHT) | `skiathos` | isti URL | preporučeno |
| 5 | Adaköy Marina (204 / 904) | 9 indeksiranih (SAILING_YACHT) | `adak%C3%B6y%20marina` (samo l-904) | isti URL, + l-204; `marmaris adakoy marina` (noindex) 200 → 404 | preporučeno |
| 6 | Marina Lav (72 / 1423) | 9 indeksiranih (MOTOR_YACHT) | `marina%20lav%20-%20podstrana` | **404**, landing prelazi na `marina%20lav` | **OBAVEZNO** |
| 7 | D-Marin Borik (26 / 1413) | nema (ime „Zadar, D-Marin Borik" ima zarez, ne može biti landing) | `d-marin borik` 200 noindex | `d-marin%20borik` postaje pravi landing; novi tipizirani URL-ovi mogu ući u sitemap ako prođu gate (korpus ima `sailing-yacht-charter-d-marin-borik`, `motorboat-charter-d-marin-borik`) | ne |
| 8 | Marina Baška Voda (76 / 1571) | noindex | `marina%20ba%C5%A1ka%20voda` | isto | ne |
| 9 | Marina de Dénia (548 / 848) | noindex | `marina%20de%20d%C3%A8nia` (samo l-848) | `marina%20de%20d%C3%A9nia`, staro pisanje 308 (isti ključ) | ne |
| 10 | Tromsø (396 / 624) | noindex | `tromso` (l-396); `tromsø` 308 → `tromsø eidkjosen` | `tromso` → 404; `tromsø` i dalje 308 → `tromsø eidkjosen` (sad s l-396) | ne |
| 11 | Marina Admiral (13 / 1950) | noindex | `marina%20admiral%20opatija` | `marina%20admiral`; staro → 404 | ne (opcionalno) |
| 12 | Siracusa (229 / 691) | noindex | `siracusa` | isto | ne |
| 13 | Marina Špinut (65 / 1421) | noindex | `marina%20%C5%A1pinut` (samo l-1421) | isto, + l-65 | ne |
| 14 | Porto di Mergellina (2099 / 2007) | noindex (ime sa zarezom) | `porto di mergellina` → „Naples, Porto di Mergellina" | isto (imena se NE mijenjaju, samo par) | ne |
| 15 | Skopea Marina (502 / 780) | noindex | `g%C3%B6cek%2Fskopea%20marina` | `skopea%20marina`; staro → 404 | ne (opcionalno) |
| + | Sorrento (242 / 1809) | noindex | `sorrento` (l-1809) | isto | ne |
| + | Port Calanova (1781 / 1516) | 404 (nema brodova) | n/a | isto | ne |

Opcionalno, samo da stari noindex URL-ovi daju 308 umjesto 404 (nije potrebno za SEO; pin odmah prebacuje noindex
kanonski na novo ime):

```ts
  { name: 'Marina Admiral', dids: ['l-13', 'l-1950'], kind: LocationType.MARINA, countryCode: 'HR',
    aliases: ['Marina Admiral Opatija', 'Marina Admiral | Opatija', 'Marina Admiral, Opatija'] },
  { name: 'Skopea Marina', dids: ['l-502', 'l-780'], kind: LocationType.MARINA, countryCode: 'TR',
    aliases: ['Göcek/Skopea Marina', 'Skopea Marina | Göcek', 'Skopea Marina, Göcek'] },
```

## Provjera (prije i poslije migracije isto)

```
/search?destinations=marina%20lav%20-%20podstrana&boatTypes=MOTOR_YACHT  -> 200, canonical isti, index
/search?destinations=marina%20lav&boatTypes=MOTOR_YACHT                  -> 308 na gornji
/search?destinations=lavrion%20main%20port&boatTypes=CATAMARAN           -> 200, canonical isti
/search?destinations=preveza%20main%20port&boatTypes=SAILING_YACHT       -> 200, canonical isti
/search?destinations=skiathos&boatTypes=CATAMARAN                        -> 200, canonical isti
/search?destinations=adak%C3%B6y%20marina&boatTypes=SAILING_YACHT        -> 200, canonical isti
sitemap-categories.xml: isti 72 URL-a ovih pet mjesta (18 + 18 + 18 + 9 + 9)
```

## Zatečeno, nije dirano

- **Tromsø Eidkjosen (1218)** je oko 10 km od Tromsøa, a lista ga već danas spaja s 624 „Tromsø" (jedno ime je unutar
  drugog, ≤ 20 km). Nakon V9_78 u isti red ulazi i 396 (jer je 396 = 624), pa „Tromso" nestaje kao zaseban red liste
  i ulazi u „Tromsø Eidkjosen" (l-1218,l-396,l-624). Tražilica za `l-396` / `l-624` ne uključuje Eidkjosen.
- **„Naples" (NauSys 209, 3 broda)** je 156 m od Porto di Mergellina. Zato Mergellina ostaje bez preimenovanja (samo
  par): s imenom „Porto di Mergellina" lista bi „Naples" spojila s „Naples, Pozzuoli" (8,8 km). Je li 209 ista marina
  kao Mergellina, odlučuje Mario; tek onda preimenovanje 2099.
- boat4you-web `main` 13.10. ima necommitane izmjene druge sesije: pin commitati s pathspecom.
