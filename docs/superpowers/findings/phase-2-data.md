# Faza 2 — Data layer

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-08
**Scope:** persistencija (`*Repository.kt`), entiteti, Flyway migracije `db/migration/V*__*.sql`, `common/jpa/`, `common/cache/`, `@Transactional` granice, Hibernate Envers, HikariCP.

---

## Inventory

### Repositories (52)

Po domenama:

| Domena | # Repos | Pozicije od interesa |
|---|---|---|
| `catalouge/jpa/` | 30 | `YachtRepository`, `OfferRepository`, `InquiryRepository`, `LocationRepository`, `AgencyRepository`, `*ViewRepository` (FiltersView, YachtSearchView, AllLocationView, CustomYachtView, LocationView, YachtLocationsView), `Equipment`, `Extra`, `Manufacturer`, `Model`, `Region`, `Country`, `Category`, `Language`, `ExternalSystem`, `ExternalBase`, `ExternalEquipment`, `ExternalSeason`, `ExternalReservation`, `ExtraRepository`, `OfferExtraRepository`, `YachtExtraRepository`, `YachtEquipmentRepository`, `YachtImageRepository`, `YachtTranslationRepository`, `CustomYachtDetailRepository`, `ReservationOptionRepository`, `CustomOfferRepository`, `AgencySourceRepository` |
| `reservation/jpa/` | 9 | `ReservationRepository`, `ReservationFlowRepository`, `ReservationViewRepository`, `ReservationDocumentRepository`, `ReservationPaymentPhaseRepository`, `ReservationExtraRepository`, `ReservationYachtSwapAuditRepository`, `BookingSequenceRepository` |
| `users/jpa/` | 1 | `UserRepository` |
| `roles/jpa/` | 2 | `RoleRepository`, `RoleAssignmentRepository` |
| `security/jpa/` | 1 | `TokenRepository` (već dirano u F1-012, F1-014) |
| `invoice/jpa/` | 1 | `InvoiceRepository` |
| `external/sync/jpa/` | 3 | `ServiceCallRepository`, `ServiceCallCacheRepository`, `ExternalMappingRepository` |
| `exchange/jpa/` | 1 | `ExchangeRateRepository` |
| `gdpr/jpa/` | 1 | `GdprAuditLogRepository` |
| `settings/jpa/` | 1 | `SettingsRepository` |

### Entities (~60+)

Glavni "hot path" entiteti (više referenci, vjerojatno N+1 kandidati):

- `Yacht`, `Offer`, `OfferExtra`, `OfferPaymentPlan`, `Reservation`, `ReservationFlow`, `ReservationExtra`, `ReservationPaymentPhase`, `Inquiry`, `Agency`, `Location`, `Region`, `Country`, `User`, `RoleAssignment`, `Token`, `Invoice`

Audited / Envers entities (`store_data_at_delete: true` znači revisions tablica raste pri svakom DELETE-u): treba grep `@Audited`.

### Flyway migracije (V1_00 → V1_89 + V9_xx test data)

Ukupno **92 migracije**. Većina inkrementalnih ALTER-eva. Za review fokus:
- Pre-prod: koje migracije idu u prvi prod deploy? (Sve od V1_00 kreću na fresh DB ili se očekuje incrementalni rollout?)
- "DROP COLUMN" / "DROP TABLE" migracije: V1_24, V1_54, V1_55 → potencijalni dataloss ako sadrže žive kolone.
- "NOT NULL" backfill migracije: V1_64, V1_69, V1_70 → koliko su sigurne na velikim tablicama bez locking-a.
- Backfill migracije: V1_69, V1_70 — explicit bulk update pri startup-u.

### Common infrastructure

- `common/jpa/AbstractEntity.kt` — base class (createdAt, modifiedAt, status?)
- `common/jpa/CustomRevisionEntity.kt` + `CustomRevisionListener.kt` — Envers custom revision (vjerojatno drži userId / changeContext)
- `common/jpa/EntityStatusEnum.kt` — soft-delete pattern?
- `common/cache/CacheConfig.kt` — JCache + EhCache (potvrđeno iz application.yml)

### HikariCP config (snapshot iz `application.yml:43-50`)

```yaml
hikari:
  maximum-pool-size: ${DB_POOL_MAX:25}
  minimum-idle: ${DB_POOL_MIN_IDLE:5}
  connection-timeout: 20000   # 20s
  idle-timeout: 300000        # 5min
  max-lifetime: 1200000       # 20min
  leak-detection-threshold: 60000   # 60s
  pool-name: boat4you-hikari
```

VM2 + VM3 svaki uzima do 25 conn-a → ukupno do 50 + Flyway temp pool. PostgreSQL default `max_connections=100` — tijesno ako se conns ne otpuste; treba provjeriti da li je VM4 PostgreSQL tunirana ili ostaje default.

### Hibernate / Envers config (`application.yml:51-66`)

```yaml
spring.jpa:
  open-in-view: false           # ✓ dobro — sprječava lazy loading u view layeru
  properties:
    org.hibernate.envers:
      audit_table_suffix: _revisions
      store_data_at_delete: true   # ⚠ sve DELETE-eve kopira u _revisions; Envers na hot petlji = perf koncern
    hibernate:
      jdbc.batch_size: 50         # ✓ batch insert/update
      jdbc.fetch_size: 50
      order_inserts: true
      order_updates: true
      batch_versioned_data: true
  hibernate:
    ddl-auto: validate            # ✓ ne mijenja shemu u runtime
```

---

## Workflow Faze 2

Plan reading pass-a u 5 batch-eva:

1. **Batch 1 — Common infrastructure** (`common/jpa/*`, `common/cache/CacheConfig.kt`, `AbstractEntity`, Envers listener)
2. **Batch 2 — User / security / roles** (`UserRepository`, `TokenRepository`, `RoleAssignmentRepository`, role-related entities)
3. **Batch 3 — Catalogue core** (`YachtRepository`, `OfferRepository`, `InquiryRepository`, `LocationRepository`, `AgencyRepository`, view repos i pripadajući entiteti) — ovo je najvjerojatniji izvor N+1 i complex queryja
4. **Batch 4 — Reservation flow** (`ReservationRepository`, `ReservationFlowRepository`, payment phases, document, swap audit)
5. **Batch 5 — Migracije** — chronological pass, focus na riskantne ALTER, NOT NULL backfill, DROP COLUMN

Za svaki batch: triage live u ovaj file kao `[F2-NNN]` nalazi.

---

## Findings

---

## Batch 1 — Common JPA infrastructure + cache config (2026-05-08)

### [F2-001] MED data integrity — `creatorId` / `modifierId` audit kolone nikad ne popunjavaju
**Lokacija:** `common/jpa/AbstractEntity.kt:46-65`
**Detekcija:** statička
**Opis:** `@PrePersist` i `@PreUpdate` imaju zakomentirane block-ove s TODO "Fill when Security has been implemented". Security je implementirana (JWT filter, role guards), ali ovaj `@MappedSuperclass` se nije ažurirao. Posljedica: svaki entity ima `creator_id` i `modifier_id` kolone u shemi (nullable), ali su one **uvijek NULL** — bez obzira tko je kreirao ili modificirao zapis. GDPR / forensic / audit zahtjevi koji se oslanjaju na ove kolone (i postoje u modelu — `creator_id BIGINT, updatable=false`) ne mogu se zadovoljiti.
**Posljedica:** kompletni audit chain na razini entiteta neispravno radi. Ne može se utvrditi tko je kreirao yacht, rezervaciju, korisnika, ili promijenio bilo što. Combined s F2-004 (sve revizije pripadaju "user 1") audit log je u potpunosti slijep.
**Predloženi fix:** popuniti `creatorId`/`modifierId` iz `SecurityContextHolder.getContext().authentication.principal` (UserDetails vraća user id). Edge case: anonymous calls (publika) — ostaviti NULL, ili koristiti reserved sentinel ID (npr. 0).
**Riziko-procjena fixa:** dira persistencu **svake** save/update operacije; promjena je u `@MappedSuperclass` što utječe na cijeli model. Nije trivijalan — treba migracijsku odluku za postojeće NULL zapise (backfill ili "unknown" semantika).
**Status:** OPEN — eskalacija (architectural decision)

---

### [F2-002] LOW perf — `@Audited` na `AbstractEntity` + `store_data_at_delete: true` čini `_revisions` tablice rastuće za sve entitete
**Lokacija:** `common/jpa/AbstractEntity.kt:17`, `application.yml:55-58`
**Detekcija:** statička
**Opis:** `AbstractEntity` ima class-level `@Audited`, što znači **svaki entity** koji nasljeđuje pravi Envers revisions tablicu (`<table>_revisions`). Plus `store_data_at_delete: true` znači da DELETE kopira cijeli row u `_revisions`. Za high-churn tablice (Token, ServiceCallCache, ExternalMapping pri sync-u, ExchangeRate dnevni refresh) revisions tablica raste lineraro s prometom — disk grows over time, query plan bloat na izvornoj tablici (Postgres autovacuum mora obrađivati i revisions tablicu).
**Posljedica:** dugoročno DB rast bez retention policy. F1-014 (Token table grows) već flagged za Fazu 2; Envers ga umnožava ×2 (token + token_revisions).
**Predloženi fix:** isključiti `@Audited` na non-business entitetima (Token, ServiceCallCache, ExternalMapping, ExchangeRate, ServiceCall) ili napraviti `@NotAudited` override. Dodati partitioning ili retention job za revisions tablice koje moraju ostati audited (User, Reservation).
**Status:** OPEN — Faza 2 detailing kandidat za follow-up

---

### [F2-003] LOW data-model clarity — `entity_status` enum samo `ACTIVE/INACTIVE`, soft-delete pattern nije eksplicitan
**Lokacija:** `common/jpa/EntityStatusEnum.kt`, `common/jpa/AbstractEntity.kt:36-38`
**Detekcija:** statička
**Opis:** Sve entiteti imaju `entity_status` kolonu s enumom `ACTIVE` ili `INACTIVE`. Ovo izgleda kao soft-delete pattern, ali nije dokumentirano niti centralizirano (nema query default-a, nema `@Where(clause = "entity_status = 'ACTIVE'")` na entitetima). Ako je svrha soft-delete: postoje li mjesta gdje query slučajno vraća INACTIVE redove? Ako svrha NIJE soft-delete: što enum znači (deactivirati yacht ali ne obrisati)?
**Posljedica:** model nejasnoća → developer može slučajno promijeniti `INACTIVE` zapis ili oslanjajući se na "delete" zaboraviti filter, što vraća deaktivirane entitete u response.
**Predloženi fix:** dokumentirati kao class-level docstring, ili dodati `@Where(clause = "entity_status = 'ACTIVE'")` na sve entitete koji se ne smiju selektirati u INACTIVE-u (cijena: morati dodati `@FilterDef` ili native query za admin scenarije gdje se INACTIVE traži).
**Status:** OPEN — Faza 5 (cross-cutting modeling clarification)

---

### [F2-004] MED audit integrity — `CustomRevisionListener` hardkodira `setModifierUserId(1L)` za svaku reviziju
**Lokacija:** `common/jpa/CustomRevisionListener.kt:5-18`
**Detekcija:** statička
**Opis:** Listener koji bilježi tko je napravio Envers reviziju. Implementacija ima zakomentiran block "Fill when Security has been implemented" (isti uzorak kao F2-001) i fallback **`revisionEntity.setModifierUserId(1L)`**. To znači: **svi audit log redovi** u svim `_revisions` tablicama imaju `modifier_user_id = 1`. User 1 je vjerojatno first SYSTEM_ADMIN — kako god, audit log laže o tome tko je napravio promjenu.
**Posljedica:** forensics nemoguć — ne može se utvrditi tko je promijenio cijenu rezervacije, ažurirao yacht, izmijenio agency podatke. Compliance za ne-GDPR cleartext audit ne radi. Combined s F2-001 (creator/modifier nikad postavljeni na entitetu): audit chain je u potpunosti slijep.
**Predloženi fix:** isto kao F2-001 — dohvat usera iz `SecurityContextHolder`, fallback na NULL ili sentinel za anonymous. Slijed: prvo F2-001, jer Listener može koristiti istu helper metodu.
**Status:** OPEN — eskalacija (audit trail je sigurnosno relevantan)

---

### [F2-005] LOW code style — `dataSyncCacheManagerCustomizer` bean nije profile-gated iako komentar sugerira da bi trebao biti
**Lokacija:** `common/cache/CacheConfig.kt:362-363`
**Detekcija:** statička
**Opis:** `// @Profile("data-sync")` zakomentirano iznad `@Bean`. Cache-evi `externalMappingCache`, `externalMappingExtendedCache` koriste se samo u sync flow-ovima (NauSys/MMK), koji idu samo na VM3. VM2 (API) instancira ove cache-eve neupotrebljive. Učinak: par MB heap-a + bean-graph bloat. Trivial.
**Posljedica:** marginalno više memorije na VM2.
**Predloženi fix:** odkomentirati `@Profile("data-sync")` na bean-u.
**Riziko-procjena fixa:** trivijalna; ako je bean stvarno nepoznato koristen na VM2, profile-gating ga uklanja. Verify-grep `externalMappingCache` korištenja prije promjene.
**Status:** WAITING-DECISION (trivial)

---

### [F2-006] MED perf — `regionsCache` i `marinasByCountryCache` su single-entry pool ali keyed po `countryCode` → thrashing potvrđen
**Lokacija:** `common/cache/CacheConfig.kt` (cache config) + `domains/catalouge/services/LocationQueryingService.kt:121-128` (callers)
**Detekcija:** statička, verified
**Opis:** Wait — re-read CacheConfig. `regionsCache` (l.253-260) i `marinasByCountryCache` (l.265-272) su DEKLARIRANI s `hundredEntryResourcePool`, NE single-entry. Ovo je polu-greška: caches koji STVARNO imaju single-entry pool i mogu thrashati su:

- `countriesCache` (l.45-52) — `SimpleKey → List`. Caller ne otkriven u ovom batchu; ako ima parametre, thrash. Ako je `getCountries()` no-arg, OK.
- `extrasCache` (l.194-201) — isto, treba verifyirati.
- `usedVesselTypesCache`, `vesselTypeYachtCountCache`, `equipmentCache`, `languageCache`, `equipmentFilter`, `extrasFilter` — svi single-entry s `SimpleKey` ključem.

Single-entry s `SimpleKey` rado je OK ako je metoda **no-arg** (Spring kreira `SimpleKey.EMPTY` koji je singleton). Multi-arg metode generiraju različite SimpleKey objekte → single-entry thrash. **TREBA per-cache verification.** F2-006 ostaje OPEN dok ne provjerimo svaki @Cacheable za broj parametara.

`regionsCache` i `marinasByCountryCache` su zapravo **OK** (hundred-entry pool + countryCode key), my earlier read was wrong.
**Posljedica:** dok ne verificiramo, neki single-entry cache-evi mogu biti silently brokeni. Najvjerojatniji kandidati: `usedVesselTypesCache` ako uzima filter parametre.
**Predloženi fix:** najprije grep svaki `@Cacheable("X")` po projektu i prebrojiti parametre na ciljanoj metodi.
**Riziko-procjena fixa:** trivijalno (cache config edit) ako se potvrdi thrash.
**Status:** OPEN — postpone verification do Batch 3 (catalogue services) gdje su @Cacheable callers

---

### [F2-007] LOW code smell — `yachtExtrasCache` deklariran s key tipom `Yacht::class.java` umjesto `Long` (yacht id)
**Lokacija:** `common/cache/CacheConfig.kt:210-217`
**Detekcija:** statička
**Opis:** Cache je deklariran kao `Yacht → List`. EhCache koristi `equals()`/`hashCode()` na ključu — Yacht entity ima default object identity equality (osim ako overrida); znači različite Yacht instance s istim id-om su različiti ključevi. Ako `@Cacheable` korisnik prosljeđuje cijeli `Yacht` objekt kao ključ (`@Cacheable(key = "#yacht")`) — obično je hibernate proxy ili managed entity, što može imati hashCode-bug-ove. Standardna praksa: `Long → List` keyed po `yacht.id`.
**Posljedica:** moguć cache-miss na "isti" yacht ako entity reload-an iz različitih JPA sessiona. Plus, ne mogu cache-irati izvan transakcije (detached entity može imati drukčiji hashCode).
**Predloženi fix:** promijeniti deklaraciju u `java.lang.Long::class.java → List::class.java`, plus uskladiti `@Cacheable` callers da prosljeđuju `yacht.id`.
**Status:** OPEN — verify @Cacheable callers prvo; ako svi prosljeđuju `id`, samo je deklaracija out-of-sync

---

### [F2-008] LOW biz visibility — Većina config cache-eva ima 10h TTL bez explicit invalidacije pri admin promjenama
**Lokacija:** `common/cache/CacheConfig.kt` — `countriesCache`, `manufacturersCache`, `equipmentCache`, `regionsCache`, `seasonsCache`, `marinasByCountryCache`, `languageCache`, `extrasCache` (svi `Duration.ofHours(10)`)
**Detekcija:** statička
**Opis:** 10h TTL znači: admin doda novi country / manufacturer / equipment / region preko admin UI-ja, **frontend mu vidi izmjene tek za do 10 sati** (ili nakon restart-a app-a). Ako admin tableovi ne pozivaju `@CacheEvict`, ovo je tihi UX bug. Treba grep `@CacheEvict` po admin* mutation servisima.
**Posljedica:** admin UX confusion — "dodao sam, gdje je?".
**Predloženi fix:** dodati `@CacheEvict(allEntries = true)` na admin mutation pathove, ili skratiti TTL na 1h (kompromis između cache-hit i invalidacijskog lag-a). Najbolji pristup: explicit eviction po stvarnoj operaciji.
**Status:** OPEN — verify postoji li `@CacheEvict` na admin mutation servisima; ako ne, MED kandidat

---

### Sažetak Batch 1

- **MED (2):** F2-001 (creator/modifier ne pune se), F2-004 (modifier_user_id=1 hardcoded)
- **LOW (5):** F2-002, F2-003, F2-005, F2-007, F2-008
- **OPEN za verifikaciju:** F2-006 (cache thrashing), F2-008 (admin invalidacija)
- **WAITING-DECISION:** F2-005 (profile-gate dataSync cache bean)

F2-001 i F2-004 su povezani (oba "TODO Fill when Security implemented") i trebaju zajedničku odluku Marija prije fix-a — **audit trail je trenutno funkcionalno mrtav.** Eskalacija prije nego nastavim u Batch 2.

---

## Batch 2 — User / security / roles (2026-05-08)

### [F2-009] LOW perf — `UserEntity.@Formula("concat(name, ' ', surname)")` se uvijek učitava
**Lokacija:** `domains/users/jpa/UserEntity.kt:131-133`
**Detekcija:** statička
**Opis:** `@Formula` deklaracija dodaje computed kolonu **u svaki SELECT** za UserEntity (Hibernate ugradi `concat(name, ' ', surname) AS fullNameByFormula` u svaki query). Polje je `@Deprecated("Use getFullName() method")`, ali je još uvijek `lateinit var` → znači da Hibernate fetcha vrijednost svaki put. Plus: `getFullName()` metoda radi isto u Kotlin-u bez DB računa.
**Posljedica:** marginalno više CPU-a na DB-u + više bytes po row-u u svakom SELECT-u. User tablica nije najveća, ali svaki request koji loada user-a (autentificirani request) ovo pokupi.
**Predloženi fix:** obriši `@Formula` polje (deprecated je već). Migracija nije potrebna — Hibernate samo neće više dodavati u SELECT. Verify-grep da nigdje u kodu netko ne pristupa `fullNameByFormula` direktno (ako pristupa, prebaci na `getFullName()`).
**Riziko-procjena fixa:** trivijalno, dira UserEntity. LOW.
**Status:** WAITING-DECISION (trivial)

---

### [F2-010] LOW perf — `UserRepository.findByEmail` JPQL `LEFT JOIN FETCH u.roleAssignments` bez `DISTINCT`
**Lokacija:** `domains/users/jpa/UserRepository.kt:12-19`
**Detekcija:** statička
**Opis:** `JOIN FETCH` na kolekciju (`roleAssignments`) bez `DISTINCT` keyword-a generira Cartesian product u rezultatu — ako user ima 3 role assignment-a, DB vraća 3 row-a istog user-a. Hibernate ih u memoriji deduplicira (po default object identity-ju jer UserEntity nema custom equals), ali DB sloj još uvijek prolazi 3× više byteova preko žice.
**Posljedica:** marginalno spori login + admin lookup za usere s više rola. SYSTEM_ADMIN-i obično imaju samo 1 rola, normalni useri 1, tako da je impact malen u praksi.
**Predloženi fix:** dodati `SELECT DISTINCT u FROM UserEntity u ...`. Tim DB radi de-dup, mreža prenosi manje, Hibernate svejedno radi memory de-dup ali tek na manjem skupu.
**Riziko-procjena fixa:** trivijalna, jedan DISTINCT. LOW.
**Status:** WAITING-DECISION (trivial)

---

### [F2-011] MED data integrity / GDPR — `findAllAdminEmailAddresses` ne filtrira `deleted_at IS NULL`
**Lokacija:** `domains/users/jpa/UserRepository.kt:30-39`
**Detekcija:** statička
**Opis:** Query vraća email-ove svih SYSTEM_ADMIN korisnika za system notifications (npr. "broker je dodao novu agenciju" → notify all admins). Filter `u.entityStatus = 'ACTIVE'` postoji, ALI `u.deletedAt IS NULL` filter NEDOSTAJE. UserEntity ima poseban `deleted_at` GDPR tombstone — when set, user je "anonimiziran" (PII wiped, password rotated, tokens revoked, **ali roles drop-an?**). Treba verifikacija — ako role assignment nije obrisan kad se user GDPR-deletea, onda ovaj query vraća email anoniminiziranog user-a (`deleted-7421@boat4you.invalid` ili sl.) i sistem pokušava poslati notifikaciju koja ili (a) bounce-uje, ili (b) gore — ako anonimizacija ne mijenja email, šalje pravom user-u koji je tražio brisanje. **GDPR right-to-erasure breach.**
**Posljedica:** ovisi o tome čisti li GDPR delete flow rola; treba verifikacija. Bilo: bounced admin notifikacije, bilo: GDPR breach.
**Predloženi fix:** dodati `AND u.deletedAt IS NULL` u WHERE klauzulu. Plus: razjasniti GDPR delete flow — drop-aju li se roles assignment-i (ili samo PII fieldovi)?
**Riziko-procjena fixa:** trivijalno za query. Verify GDPR delete flow je zaseban issue.
**Status:** OPEN — verify GDPR delete flow prvo, onda fix

---

### [F2-012] LOW perf — `findAllByBirthdayMonthDay` native query bez funkcionalnog indeksa
**Lokacija:** `domains/users/jpa/UserRepository.kt:57-68`
**Detekcija:** statička
**Opis:** `EXTRACT(MONTH FROM birthday) = :month AND EXTRACT(DAY FROM birthday) = :day` — funkcionalni filter koji DB ne može zadovoljiti index-om na `birthday` kolonu. Cron BirthdayEmailJob trigger-a daily → full table scan na `users` tablicu.
**Posljedica:** za male user-baze (low thousands), neopazi se. Za 100k+ usera, sekunde scan-a svaki dan. Ne urgent.
**Predloženi fix:** dodati funkcionalni index — `CREATE INDEX users_birthday_md_idx ON users ((EXTRACT(MONTH FROM birthday)), (EXTRACT(DAY FROM birthday))) WHERE birthday IS NOT NULL`. Postgres podržava parcijalne + funkcionalne indekse.
**Riziko-procjena fixa:** dira shemu (Flyway migracija). LOW priority za sad.
**Status:** OPEN — defer to Phase 6 (repo hygiene + perf indexes)

---

### [F2-013] MED perf — `TokenEntity.@ManyToOne user` je EAGER by default — fetched on every auth request
**Lokacija:** `security/jpa/TokenEntity.kt:27-29`
**Detekcija:** statička
**Opis:** `@ManyToOne` bez `fetch = FetchType.LAZY` koristi JPA default = EAGER. `JwtAuthenticationFilter` poziva `tokenRepository.findByValue(jwtFromHeader)` na **svaki autentificirani HTTP request**, što triggera SELECT TokenEntity + automatski JOIN/sub-select za UserEntity. Kombinirano s F1-014 (token table grows): svaki request = 2 query-ja minimum (token + user) + Envers _revisions overhead pri save-u.
**Posljedica:** baseline auth latency 2× nego potrebno. Visibly impacts response time pod load-om. Kod 100 req/s normalan promet i 25 conn pool, conn turnover gušći nego treba.
**Predloženi fix:** `@ManyToOne(fetch = FetchType.LAZY)` na user FK-u. Verify call sites koji čitaju `token.user` ne padaju izvan transakcije — ako padaju, treba @Transactional ili explicit `JOIN FETCH` u relevantnom query-ju.
**Riziko-procjena fixa:** dira hot path (auth filter). Treba runtime verifikacija u Fazi 7 — može se desiti LazyInitializationException u nekoj rute koji se na sluti samo kad pukne.
**Status:** OPEN — fix kandidat za Fazu 5 (cross-cutting perf), s runtime verifikacijom u Fazi 7

---

### [F2-014] MED logic bug — `findAllValidTokenByUserId` ima `OR` umjesto `AND` u WHERE klauzuli, "valid" semantika netočna
**Lokacija:** `security/jpa/TokenRepository.kt:9-15`
**Detekcija:** statička
**Opis:** Query: `WHERE t.user.id = :id AND (t.isExpired = false OR t.isRevoked = false)`. Logika: vraća tokene gdje JE BAR JEDNO false. Token koji je `isExpired=true, isRevoked=false` (npr. natural expiry, ali nije ručno revoked) → vraća se. Token koji je `isExpired=false, isRevoked=true` (forced logout) → također vraća se. **Pravi "valid" filter** bi trebao biti AND: `isExpired=false AND isRevoked=false`. Trenutni query bi se trebao zvati `findAllNotFullyRevokedByUserId` ili sl. — **ime je obmanjujuće.**

Caller `TokenService.revokeAllUserTokens` ne brine za ovu razliku jer postavlja oba flag-a na true → idempotentno. Ali **ako neki budući caller** zatraži "all valid tokens for user" očekujući stvarno valid tokene → dobije i revoked i expired ones. Bug-om-čekanju.
**Posljedica:** dependent code može ne raditi ono što naziv funkcije sugerira. Code review test bug.
**Predloženi fix:** dvije opcije:
  - (a) preimenovati metodu u `findAllNotFullyRevokedByUserId` (zadrži OR semantiku); ili
  - (b) promijeniti u AND i preimenovati `findAllActuallyValidByUserId`. Update caller-a u TokenService da koristi novu semantiku (ili dodati novi method, ostaviti stari za revoke flow).

Najčistije: dodati novi method `findAllActiveByUserId` s AND semantikom, ostaviti postojeći za revoke flow.
**Riziko-procjena fixa:** trivijalno, ne dira hot path.
**Status:** WAITING-DECISION (rename + new method, ili nada)

---

### [F2-015] LOW perf — `revokeAllUserTokens` radi N+1 update umjesto bulk UPDATE statement
**Lokacija:** `security/jpa/TokenService.kt:9-18`
**Detekcija:** statička
**Opis:** Service load-a sve valid tokene → set-a oba flag-a na true → `saveAll`. Hibernate generira N pojedinačnih UPDATE-eva (jedan po tokenu) + N revisions inserts. Za user-a s 10 tokena (browser + mobile + različite sessions tijekom mjeseca) — 20 round-trip-ova.
**Posljedica:** marginalno spori logout-svuda. Postaje problem ako F1-014 (token table grow) ne riješiti — useri akumuliraju desetke tokena tijekom godine.
**Predloženi fix:** `@Modifying @Query("UPDATE TokenEntity t SET t.isExpired=true, t.isRevoked=true WHERE t.user.id=:userId AND (t.isExpired=false OR t.isRevoked=false)")` — single statement. **NAPOMENA:** bulk UPDATE bypassa Hibernate event listeners → Envers `_revisions` neće dobiti redove za update. To može biti acceptable (Envers već ima manualni revoke-all event jer bi i ovako u jednom request-u bilo).
**Riziko-procjena fixa:** dira audit trail. Treba odluku ide li bulk + manual Envers revision insert, ili stati na N+1 pattern-u.
**Status:** OPEN — Fazi 5 perf + audit decision

---

### [F2-016] MED perf — `RoleAssignmentEntity` ima EAGER user i role @ManyToOne (oba)
**Lokacija:** `domains/roles/jpa/RoleAssignmentEntity.kt:21-30`
**Detekcija:** statička
**Opis:** `@ManyToOne` bez explicit fetch = EAGER. RoleAssignmentEntity ima dva @ManyToOne (user + role) — svaki load triggera 2 додатна SELECT-a. Kad UserEntity loada svoje `roleAssignments` (LAZY collection, učita ih po potrebi), Hibernate fetcha roleAssignment redove + za svaki par 2 додатна SELECT-a (user + role). Klasični N+1 problem na auth path-u.

User → roleAssignments (LAZY, 1 query) → assignment → user (EAGER, već loaded) + role (EAGER, +1 query po assignmentu). Za usera s 2 role: 1 + 2 = 3 query-ja umjesto 1 sa proper JOIN FETCH.

UserRepository.findByEmail koristi explicit `JOIN FETCH u.roleAssignments` — **ali ne i nested role**. Tako da na auth-flow-u imamo: 1 (findByEmail s JOIN FETCH) + N (jedan SELECT po assignment-u za role) = N+1.
**Posljedica:** auth flow N+1; replicira se na svaki @Authentication / @PreAuthorize check kad se rola provjerava iz tokena (ako se rola ne re-fetcha iz DB-a, OK; ali F1-005 traži da se re-fetcha).
**Predloženi fix:** `@ManyToOne(fetch = FetchType.LAZY)` na oba; UserRepository.findByEmail proširi na `LEFT JOIN FETCH ra.role` (nested join).
**Riziko-procjena fixa:** dira auth path (UserAuthService callers). Visi srednje rizično — ne mijenjam dok ne odobriš.
**Status:** OPEN — Faza 5

---

### Sažetak Batch 2

- **MED (4):** F2-011 (GDPR/admin email), F2-013 (TokenEntity.user EAGER), F2-014 (findAllValid OR/AND bug), F2-016 (RoleAssignment EAGER N+1)
- **LOW (4):** F2-009 (Formula deprecated), F2-010 (DISTINCT missing), F2-012 (birthday index), F2-015 (revoke bulk)
- **WAITING-DECISION:** F2-009, F2-010, F2-014 (trivijalna polish/rename)

Najznačajniji nalazi:
- **F2-013 + F2-016** zajedno: auth path ima 3-4 nepotrebna query-ja po request-u. Pod load-om vidljivo.
- **F2-011** GDPR koncern — verify GDPR delete flow čisti li role_assignments.
- **F2-014** je tipičan "bug-čekanju" — ime obmanjuje, čeka novog dev-a da pogriješi.

Batch 3 (catalogue core: Yacht/Offer/Inquiry/Location/Agency + view repos) sljedeći — najvjerojatnije najdublji N+1 i complex-query izvor.

---

## Batch 3a — Catalogue core: Yacht / YachtImage / YachtTranslation (2026-05-08)

### [F2-017] LOW model consistency — `Yacht`, `YachtImage`, `YachtTranslation` ne extendaju `AbstractEntity`
**Lokacija:** `domains/catalouge/jpa/Yacht.kt:32`, `YachtImage.kt:22`, `YachtTranslation.kt:22`
**Detekcija:** statička
**Opis:** Većina entiteta nasljeđuje `AbstractEntity<Long>` koji daje: `id` (BIGSERIAL), `created`, `modified`, `creator_id`, `modifier_id`, `entity_status` + `@Audited` aspekt. **Yacht klasa ima vlastiti `@Id` i nedostaje sve ostalo** — bez audita, bez timestamps, bez entity_status soft-delete pattern-a. **YachtImage**, **YachtTranslation** isto. Komentar uz `Yacht.sysActive` kaže "If yacht is deactivated (deleted) by external system" — to je **ad-hoc soft-delete pattern** umjesto centralnog `entity_status`.
**Posljedica:** **Yacht promjene nisu auditable.** Cijena, deposit, model, agency — sve može tiho biti promijenjeno bez tracker-a. Combined s F2-001/F2-004 (audit trail dead in general): **historija promjena yachte-a i pripadajućih slika/prijevoda je nepostojeća.** Ako broker podigne cijenu yachti-a noćuska tako da povisi prihod kupca koji je već rezervirao, ne može se utvrditi promjena.

Plus: `Yacht` nema `created` / `modified` kolonu → ne može se sortirati "novi yachti su gore" ili "promijenjeni nedavno". Trenutni queryji koji to trebaju moraju izlučiti iz Envers `_revisions` tablice... ali Yacht nije ni Envers-audited.
**Predloženi fix:** Yacht extend AbstractEntity. Migracija dira yacht tablicu (dodati 4 kolone, backfill `created/modified` iz prve sync revizije ili NOW(), `creator_id` ostane NULL za postojeće, `entity_status='ACTIVE'`). Velik refactor + Envers `@Audited`.
**Riziko-procjena fixa:** velik. Migracija na milijunske tablice + sync code update (sync postavlja Yacht.created etc. dolazi iz vanjskih sustava ili NOW()).
**Status:** OPEN — eskalacija (architectural decision)

---

### [F2-018] MED data integrity risk — `@Enumerated` bez `EnumType.STRING` na većini entiteta = ORDINAL storage
**Lokacija:** `Yacht.kt:132 (mainsailType)`, `Yacht.kt:142 (genoaType)`, `Yacht.kt:230 (vesselType)`, `Yacht.kt:238 (entryType)`, `YachtTranslation.kt:45 (type)`. Vjerojatno isti pattern u drugim entitetima — treba grep `@Enumerated$` (bez argumenata).
**Detekcija:** statička
**Opis:** `@Enumerated` bez argumenta default-a na `EnumType.ORDINAL` — enum vrijednost se sprema kao **integer index** u DB-u (`vesselType=0` za prvi, `=1` za drugi itd.). Posljedica: **ako developer doda novi enum value bilo gdje OSIM na kraj listе, ili promijeni redoslijed, ili obriše vrijednost — svi postojeći redovi tiho čitaju krive vrijednosti**. Klasičan "production-killer".

Primjer: trenutno `EntryType.CUSTOM` možda ima ordinal 1 (na osnovi `entry_type = 1` u native queryju za replacement search). Ako netko sutra preimenuje ili reorderira EntryType enum, svi yachti s `entry_type=1` u DB-u i dalje vraćaju "CUSTOM" iz kod-a — ali ako se enum promijenio i sad `EntryType.EXTERNAL` ima ordinal 1, čitamo CUSTOM kao EXTERNAL. Tipična bug klase koja se manifestira tek post-deploy.
**Posljedica:** kritičan rizik tihih korumpiranih enum vrijednosti. Combined s F2-019 (native queryji hardkodiraju ordinale) — već svjesno gradimo na tom riziku.
**Predloženi fix:** **dvostupanjski**: (1) audit svih @Enumerated bez STRING, dokumentirati trenutne ordinale. (2) Migracija u dvije faze:
  - migracija A: dodati string kolonu `vessel_type_str VARCHAR`, backfill iz int-a, dual-write.
  - migracija B: drop int kolonu, rename str → original.

Ako fresh DB se još ne deploya — promijeniti `@Enumerated` u `@Enumerated(EnumType.STRING)` PRE prvog deploya je trivial.

**Status:** OPEN — **kritična odluka prije prod deploy-a**: ide li ovaj review-branch live s ORDINAL pattern-om? Ako da, mora biti svjesno acceptirano i strogo zabraniti reorder enuma. Ako ne, migracijska strategija prije live-a.

---

### [F2-019] MED data integrity — Native queryji hardkodiraju enum ordinale (`status IN (1, 2, 3)`, `entry_type = 1`)
**Lokacija:** `domains/catalouge/jpa/YachtRepository.kt:178, 182, 248` (najmanje)
**Detekcija:** statička
**Opis:** `findForReplacementSearch` i `countForReplacementSearch` native queryji koriste raw integer vrijednosti za enum filtriranje:
- `o.status IN (1, 2, 3)` — pretpostavka da su prve 3 vrijednosti enum-a "active offer states" (CREATED, CONFIRMED, BOOKED ili sl.). Ali nije eksplicitno dokumentirano i ne provjerava se compile-time.
- `y.entry_type = 1` — vjerojatno `EntryType.CUSTOM`. Komentar uz @Enumerated u Yacht-u ne pomaže.

Combined s F2-018: ako se EntryType enum reorderira, ovi native queryji silently lažu o tome što filtriraju. Native queryji ne mogu se compile-time provjeriti.
**Posljedica:** dodatna bug-klasa povezana s F2-018. Replacement search (ops-side feature za broker swap rezervacija) može vraćati krive yachte ako se enum promijeni.
**Predloženi fix:** ili (a) prebaciti na JPQL gdje Hibernate generira ispravne vrijednosti iz enum-a, ili (b) ako native query mora ostati zbog LATERAL join-a, dokumentirati uz query "WARNING: status=1,2,3 mapira na OfferStatus.{X,Y,Z}; ako se enum promijeni, OVO TREBA AŽURIRATI." Plus: koristiti `:status1, :status2, :status3` parametre + sastaviti listu iz Kotlin koda (`OfferStatus.ACTIVE.ordinal + 1` ili sl.) — ne idealno ali eksplicitno.

Najbolji fix: pomiknuti se na `EnumType.STRING` (F2-018) i koristiti string vrijednosti u native queryju (`status IN ('CREATED', 'CONFIRMED', 'BOOKED')`).
**Status:** BLOCKED-BY F2-018

---

### [F2-020] LOW perf — `findWithReservationOptionsByAgency` JOIN FETCH bez DISTINCT
**Lokacija:** `domains/catalouge/jpa/YachtRepository.kt:42-52`
**Detekcija:** statička
**Opis:** Isti pattern kao F2-010 (UserRepository.findByEmail). `JOIN FETCH y.reservationOptions ro JOIN FETCH y.agency a` bez `DISTINCT` keyword-a. ReservationOptions je Set u Yacht entity, ali dotok preko žice nije dedupliciran u DB sloju.
**Posljedica:** za yacht s 10 reservation options × 1 agency, vraća se 10 row-eva istog yachti-a iz DB-a. Hibernate dedupes, ali podaci su preneseni 10×.
**Predloženi fix:** dodati `SELECT DISTINCT y FROM Yacht y ...`.
**Status:** WAITING-DECISION (trivial)

---

### [F2-021] MED maintainability — `findForReplacementSearch` vs `countForReplacementSearch` divergentna struktura WHERE klauzule
**Lokacija:** `domains/catalouge/jpa/YachtRepository.kt:138-220` (find) vs `:226-271` (count)
**Detekcija:** statička
**Opis:** Komentar iznad count-a kaže "Kept in sync with the main query's WHERE branches", ali implementacije su **strukturno različite**:
- find query: `... AND (avg_price.avg_per_day IS NOT NULL OR EXISTS (SELECT 1 FROM external_reservations ...))` — koristi LATERAL join rezultat `avg_price.avg_per_day` iz subqueryja gore.
- count query: `... AND (EXISTS (SELECT 1 FROM offer ...) OR EXISTS (SELECT 1 FROM external_reservations ...))` — re-izvodi EXISTS jer nema LATERAL join.

Funkcionalno **vjerojatno** vraćaju isti broj redova (oba kažu "yacht ima active offer u lokaciji **ILI** preklapajuću external reservation"). Ali ako se kasnije doda još jedan filter status (npr. status=4), dev mora držati u glavi da treba ažurirati **OBA** mjesta sa malo različitim sintaksama. Bug-čekanju: pagination break ako count i find ne reflektiraju isti skup.
**Posljedica:** mogući subtle pagination bugs ("page 1 ima 12 yachte-a, count kaže 25, page 3 prazan") — vrlo teško otkriti.
**Predloženi fix:** dvije opcije:
  - (a) refactor count na isti LATERAL pattern (sporiji, ali strukturno ekvivalentan).
  - (b) izdvojiti zajedničke filter izraze u baseline view ili stored function. Pre-kompleksno.
  - (c) ostaviti, ali napraviti **integration test** koji garantira da count = `find.size` za svaki edge filter combination.

Najpragmatičnije: dodati test koji pokriva edge case-ove (locationIds prazno, agencyIds prazno, samo external reservation match) i potvrdi count=find.size.
**Riziko-procjena fixa:** dira ad-hoc native query — ne pukne nigdje silently.
**Status:** OPEN — Faza 5 (cross-cutting test coverage) ili tracking-only

---

### Sažetak Batch 3a

- **MED (3):** F2-018 (enum ORDINAL storage — kritičan prije prod deploya), F2-019 (hardkodirani ordinali u native query — ovisi o F2-018), F2-021 (find/count divergence)
- **LOW (2):** F2-017 (Yacht ne extendira AbstractEntity), F2-020 (DISTINCT missing)
- **WAITING-DECISION:** F2-020 (trivial DISTINCT add)

**Najkritičniji nalaz batch-a: F2-018.** Nije pitanje "hoćeš li popraviti", nego "koliko to košta sad vs kasnije". Pre-prod deploy s ORDINAL je **prihvaćanje rizika** da se jednom u budućnosti prouzroči production data corruption. Migracija STRING-a kasnije je značajno teža nego sad.

**Batch 3a je incomplete** — još Offer, OfferExtra, OfferPaymentPlan, ReservationOption, Inquiry, CustomYachtDetail, Equipment, Extra, Location, Agency, Manufacturer, Model, View repos čekaju. Splitano u Batch 3b/3c sljedeća sjednica.

---
