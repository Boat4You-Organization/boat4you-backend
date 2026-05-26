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

**Status:** FIXED `0d1242a` — V1_90 migracija (smallint → VARCHAR(31), CASE-mapped per enum.name() declaration order) + `@Enumerated(EnumType.STRING)` na 22 entity polja. 18 enuma cross-verificirano protiv Kotlin source order-a prije commit-a. Adresira F2-018 i F2-019 (native queryji preneseni na string literale) u istom commit-u.

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
**Status:** FIXED `0d1242a` — uz F2-018 STRING migraciju, native queryji u `YachtRepository` su preneseni na string literale (`o.status IN ('FREE','OPTION','OPTION_WAITING')`, `y.entry_type = 'EXTERNAL'`); parametri `vesselTypes: List<Int>` → `List<String>`; isto za `YachtDistributionService` i `YachtRelaxSuggestionService` (prosljeđuju `enum.name` umjesto `.ordinal`).

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

## Batch 3b — Offer flow: Offer / OfferExtra / OfferPaymentPlan / ReservationOption / Inquiry / CustomYachtDetail / CustomOffer (2026-05-11)

### [F2-022] HIGH bug — Daily scheduled cleanup koristi non-PostgreSQL date syntax → tihi failure svaki dan u 06:00
**Lokacija:** `domains/catalouge/jpa/OfferRepository.kt:108-117` (`deleteExpiredOffers`, `nativeQuery=true`) i `domains/catalouge/jpa/ExternalReservationRepository.kt:13-15` (`deleteExpiredReservations`, JPQL).
**Detekcija:** statička + trace caller (`DeleteExpiredReservationsAndOffersJob.kt:20` `@Scheduled(cron = "0 0 6 * * ?")` → `ReservationOfferService.deleteExpiredReservationsAndOffers()`).
**Opis:** Cron job poziva oba `delete` query-ja u istoj `@Transactional` metodi. **Ni jedan nije validni PostgreSQL:**
- `OfferRepository.deleteExpiredOffers`: native SQL koristi `DATE_ADD(CURRENT_DATE, '-30 day'::interval)`. **PostgreSQL nema `DATE_ADD` funkciju** (MySQL syntax). PostgreSQL ekvivalent: `CURRENT_DATE - INTERVAL '30 days'`.
- `ExternalReservationRepository.deleteExpiredReservations`: JPQL koristi `DATEADD(day, -30, CURRENT_DATE)`. **`DATEADD` nije standardni JPQL niti PostgreSQL function** (T-SQL/MSSQL syntax).

Migracije ne registriraju custom `date_add` ili `dateadd` Postgres funkcije (grep `db/migration/**/*.sql`). Hibernate ne transformira native query-je → first query baca `ERROR: function date_add(date, interval) does not exist`. Transakcija rolls back, drugi query se ne izvršava. **Cron logira exception ali ne kill-a JVM** — svaki sljedeći dan ista priča.

Posljedica: **expired offers + external reservations se nikad ne brišu**, tablice `offer` i `external_reservations` rastu bez retencije. Combined sa Envers `store_data_at_delete:true` (već F2-002 koncern) i sync flow-ovima koji daily refresh-aju offers — `offer_revisions` tablica može akumulirati ~10k+ row-eva tjedno i bez čišćenja. Disk + query plan bloat pre-prod.

Test suite: 29/103 fail-ovi (baseline). Vjerojatno nema integration testa koji ovo cilja — inače bi failao.
**Predloženi fix:** dvije male promjene:
- `OfferRepository.deleteExpiredOffers`: `WHERE o.date_to < CURRENT_DATE - INTERVAL '30 days'` (native).
- `ExternalReservationRepository.deleteExpiredReservations`: prebaciti na JPQL parameter — `WHERE r.dateTo < :cutoff`, callback prosljeđuje `LocalDate.now().minusDays(30)`. Eliminira dialect-specific funkcije iz koda.

Plus: dodati log statement koji broji *koliko* je redova obrisano (Spring Data vraća `int` count za @Modifying queries), tako da admin vidi je li cron stvarno radi. **Plus monitoring:** alert ako 0 redova obrisano 3+ dana u nizu (signal da query opet šuti).
**Riziko-procjena fixa:** trivijalan u smislu code-a (dvije linije). Ali to su scheduled jobovi koji diraju persistencu — testirati u staging-u prije prod-a (idealno: ručno trigger admin endpointa `/admin/deleteExpiredReservationsAndOffers` koji već postoji u `AdminJobController.kt:26`).
**Status:** FIXED `0dc514f` — `deleteExpiredOffers` native SQL koristi `CURRENT_DATE - INTERVAL '30 days'`; `deleteExpiredReservations` prebačeno na JPQL `:cutoff` parameter, `ReservationOfferService` prosljeđuje `LocalDate.now().minusDays(30)`. **Staging verification recommended** prije prod-a: ručni trigger preko `/admin/deleteExpiredReservationsAndOffers` (postoji u `AdminJobController.kt:26`) + provjera log-a da nema više `function date_add/dateadd does not exist`.

---

### [F2-023] MED perf — `InquiryRepository.findAllByParamsForAdmin` triple leading-wildcard LIKE = full table scan; multiplikator za F1-068 email-bombing
**Lokacija:** `domains/catalouge/jpa/InquiryRepository.kt:11-25`
**Detekcija:** statička
**Opis:** Admin inquiry search:
```jpql
LOWER(i.email) LIKE LOWER(CONCAT('%', STR(:search), '%'))
  OR LOWER(i.name) LIKE LOWER(CONCAT('%', STR(:search), '%'))
  OR LOWER(i.surname) LIKE LOWER(CONCAT('%', STR(:search), '%'))
```
Leading wildcard `%X%` znači da **nijedan B-tree index ne može poslužiti** — Postgres mora projekcionirati cijelu `inquiry` tablicu i izvršiti case-folding na 3 kolone po row-u. Plus svaki `LOWER(col)` u funkcionalnom kontekstu znači da i regular index na `email` ne bi bio koristan; trebao bi specifičan `LOWER(email)` funkcionalni index — koji ne postoji.

Kombinirano s F1-068 (`/public/inquiries/{id}/send-test` anon email-bombing): napadač kreira tisuće inquiry zapisa → admin search postaje sekunde po query-ju. DoS multiplikator: spori admin UX (kad netko ipak provjeri) + više CPU na DB-u.

`STR(:search)` je dodatna anomalija — Hibernate JPQL `STR` funkcija konvertira u string. Ako `:search` već je String parametar, ovo je no-op (Hibernate translates to `cast(? as varchar)`). Funkcijski nepotrebno; potencijalno smetnje za optimizer ali ne kritično.
**Posljedica:** admin search degradira non-linearno s rastom `inquiry` tablice. Combined s F1-068 ovo je vektor DoS-a.
**Predloženi fix:** dvije opcije:
- (a) **Trigram index** — pg_trgm extension, `CREATE INDEX inquiry_email_trgm_idx ON inquiry USING gin (LOWER(email) gin_trgm_ops)` (+ ista za name/surname). Omogućava `LIKE '%x%'` index-supported scan. Najbolja pragmatika.
- (b) **Full-text search** — `tsvector` kolona, ali overkill za admin search.
- (c) **Fail-fast minimum length** — odbiti search query < 3 chars (vraćati 400) tako da je rezultat brzo bounded. Combined s (a) za najbolji rezultat.

Plus: prebaciti `STR(:search)` na običan `:search` (redundantno).
**Riziko-procjena fixa:** dira shemu (Flyway migracija za pg_trgm + indexe). MED.
**Status:** OPEN — Faza 6 (perf indexes) ili paralelno s F1-068 fix-om

---

### [F2-024] MED perf — `countByEmailIgnoreCaseAndIdNot` poziva se na svaku novu inquiry; bez funkcionalnog indeksa = O(n) po insertu
**Lokacija:** `domains/catalouge/jpa/InquiryRepository.kt:29-39`
**Detekcija:** statička
**Opis:** `LOWER(i.email) = LOWER(:email) AND i.id <> :idNot` — pokreće se nakon spremanja svake inquiry da bi "NEW CLIENT" pill bio točan u sljedećem email-u. Bez `LOWER(email)` funkcionalnog indeksa, ovo je seq scan na cijelom `inquiry` tabli pri svakom insert-u.

Combined s F1-068 (anon email-bombing): napadač šalje 1000 inquiry POST-ova → svaki insert pokreće seq scan po 1000-rastućoj tabli. **Quadratic behavior** O(n²) na bursts: 1000 inquiries = 500k row reads ukupno. Plus svaka inquiry već pokreće mail-send pa je IO bottleneck dominantan, ali DB CPU is collateral.
**Posljedica:** pod load-om inquiry creation throughput pada. Combined s F1-069 (`/public/inquiries` POST no rate-limit) i F1-068 (email-bombing) ovo je treći faktor istog problema — burst attack ima O(n²) DB cost.
**Predloženi fix:** isto kao F2-023 — funkcionalni index `CREATE INDEX inquiry_email_lower_idx ON inquiry (LOWER(email))`. Tada `WHERE LOWER(i.email) = LOWER(:email)` koristi index. **Najbolji potez:** kombinirati u jednoj migraciji s F2-023 trigram indeksom (oba dira `email` kolonu).

Alternativno: cache-irati count za email u Redis/in-memory s short TTL — ali to dodaje state. Index je čistiji.
**Riziko-procjena fixa:** Flyway migracija + index na milijunskoj tabli ali inquiry je manja od yacht-a tako da nije skupa. MED.
**Status:** OPEN — Faza 6 (vezano za F2-023)

---

### [F2-025] LOW perf — `offersByYachtAndStatusCache` key tip je `Yacht` entity, ne `Long` (sibling F2-007)
**Lokacija:** `common/cache/CacheConfig.kt:130` (deklaracija) + `domains/catalouge/jpa/OfferRepository.kt:39-50` (`@Cacheable("offersByYachtAndStatusCache")`)
**Detekcija:** statička
**Opis:** Isti pattern kao F2-007 (yachtExtrasCache) — cache deklariran s `Yacht::class.java` kao key tip, EhCache koristi `equals()`/`hashCode()`. `Yacht` entity (provjereno u Batch 3a) nema custom equals/hashCode → default Object identity. Različite Yacht instance s istim id-jem su različiti ključevi. Plus key u @Cacheable je default `SimpleKey(yacht, statuses)` što ovisi o `yacht.hashCode()` koji = identityHashCode → cache nikad ne hit-uje između transakcija.

Pozitivno: `OfferMutationService.kt:17` ima eviction `allEntries = true` pa stale data nije briga — samo cache se ne koristi efektivno (svaki request je miss).
**Posljedica:** annotation cijelo vrijeme zaposlena, cache je praktički prazan. Zero perf gain.
**Predloženi fix:** isto kao F2-007 (sibling) — promijeniti key tip u `java.lang.Long::class.java`, dodati `@Cacheable(key = "T(java.util.Arrays).hashCode(new Object[]{#yacht.id, #statuses})")` ili jednostavnije pivotati na metodu koja prima `yachtId: Long` umjesto Yacht entity. Ako ostane Yacht, dodati custom `equals/hashCode` na Yacht (rizik za druge dependencije — verify).
**Status:** OPEN — fix paralelno s F2-007 (oba dira CacheConfig)

---

### [F2-026] MED bug-u-čekanju — `OfferPaymentPlan.equals/hashCode` koristi mutable polja, čuva se u `Set<OfferPaymentPlan>` na Offer-u
**Lokacija:** `domains/catalouge/jpa/OfferPaymentPlan.kt:43-63` (equals/hashCode) + `domains/catalouge/jpa/Offer.kt:94-95` (`offerPaymentPlans: MutableSet<OfferPaymentPlan>`)
**Detekcija:** statička
**Opis:** `OfferPaymentPlan.equals/hashCode` rade na `offer`, `date`, `amount`, `percentage` — **svi su mutable (`open var`).** Klasični Kotlin/JPA pitfall: objekt se stavi u `MutableSet` s hashCode bazom na trenutnim vrijednostima → kasnije se vrijednost promijeni (npr. broker update-a datum/iznos) → hashCode više ne odgovara bucketu u kojem entity sjedi → `set.contains(plan) == false` iako je `plan` upravo u tom set-u. Posljedica:
- `offer.offerPaymentPlans.remove(plan)` može ne ukloniti plan (krivi bucket).
- Hibernate's "dirty checking" + cascade ALL + orphanRemoval može tiho stvoriti dupli plan ili pustiti orphan.
- Two PaymentPlans s istim datumom + amount u istom offer-u su `equals()=true` čak ako su različiti DB redovi — Set ih dedupes, **drugi se gubi pri save-u**.

Spring/Hibernate guidance: na entity-jima koji se drže u kolekciji uvijek equals/hashCode po identity (`id` jednom postavljen) ili klasa = data class s `id` only.
**Posljedica:** subtle bugs pri update-ovanju payment plana koji se ne reproduciraju lokalno. Pre-prod, plan update flow se vjerojatno još malo koristi pa ovo čeka da prvi broker ažurira plan datum nakon save-a.
**Predloženi fix:** promijeniti equals/hashCode na bazi `id` (vraćati `false` ako oba `id` nisu set-ana, ili koristiti `super.equals` kad oba unsaved). Idiom:
```kotlin
override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is OfferPaymentPlan) return false
    return id != null && id == other.id
}
override fun hashCode(): Int = id?.hashCode() ?: javaClass.hashCode()
```
Alternativno: prebaciti kolekciju na `MutableList<OfferPaymentPlan>` (lista ne ovisi o hashCode/equals za identitet — koristi pozicijski index).
**Riziko-procjena fixa:** dira entity contract — postojeći callers koji se oslanjaju na "structural equality" za detect-change moraju biti revisited. MED kandidat za grep.
**Status:** OPEN — eskalacija (entity contract change)

---

### [F2-027] LOW data integrity — JPA `orphanRemoval=true` vs DB `OnDelete SET_NULL` na istom FK = orphan rows nakon direct SQL delete-a
**Lokacija:** `domains/catalouge/jpa/Offer.kt:94, 110` (`@OneToMany cascade=ALL, orphanRemoval=true`) + `OfferExtra.kt:51-54` (`@OnDelete(action = OnDeleteAction.SET_NULL)` na `offer_id`) + `OfferPaymentPlan.kt:28-31` isto
**Detekcija:** statička
**Opis:** Hibridna semantika brisanja:
- JPA strana (kad app obriše Offer kroz `EntityManager.remove(offer)`): `orphanRemoval=true` + `cascade=ALL` znači da Hibernate emita DELETE na child redove (`offer_extras WHERE offer_id=?`, `offer_payment_plan WHERE offer_id=?`) prije DELETE FROM offer.
- DB strana (kad operator radi direktan `DELETE FROM offer WHERE id=N` u psql, ili kad Flyway migracija obriše red): `OnDelete SET_NULL` znači child redovi ostaju, samo se `offer_id` postavlja na NULL. To su **orphan rows** koje nikad nitko ne čita (sve queryji JOIN-aju na offer_id), ali postoje u DB-u.

Posljedica:
- Disk grow ako se Offer brisanje radi izvan app-a (npr. F2-022 kad se ikad popravi, F1-074 test fixtures koji direktno bake DB state).
- "Tiha" semantika: čišćenje preko `JpaRepository.delete(offer)` se ponaša drugačije od `repository.deleteById(id)` — prvi triggera orphanRemoval, drugi može (ovisno o Hibernate-u) izvršiti direct DELETE bez load-anja entity-ja, pa OnDelete preuzme. **Inconsistent behavior** po endpointu.
**Predloženi fix:** uskladiti smjer. Dvije opcije:
- (a) `OnDelete(action = OnDeleteAction.CASCADE)` — DB-side cleanup, omogućuje direct SQL delete bez orphan-ova. Najpragmatičnije za pre-prod (ne dira app logic).
- (b) `OnDelete(action = OnDeleteAction.RESTRICT)` — DB ne dopušta delete dok child redovi postoje. Forsira app-side cleanup. Sigurnije ali zahtjeva izmjene u code (mora explicit deleteAll children prije offer-a).

Preporučujem (a) — CASCADE — jer već imamo orphanRemoval=true na JPA strani, tako da je app-side ponašanje **istovremeno** s onim što DB sad već radi automatski. Migracija jednog FK constraint-a.
**Riziko-procjena fixa:** Flyway migracija (DROP CONSTRAINT + ADD CONSTRAINT s CASCADE). Bezopasno ako nema postojećih orphan-ova (verify prije: `SELECT count(*) FROM offer_extras WHERE offer_id IS NULL`).
**Status:** OPEN — Faza 6 (data integrity sweep)

---

### [F2-028] LOW model consistency — Offer, OfferExtra, OfferPaymentPlan, ReservationOption, Inquiry, CustomYachtDetail, CustomOffer ne extendaju `AbstractEntity` (proširenje F2-017)
**Lokacija:** `domains/catalouge/jpa/Offer.kt:33`, `OfferExtra.kt:24`, `OfferPaymentPlan.kt:21`, `ReservationOption.kt:24`, `Inquiry.kt:26`, `CustomYachtDetail.kt:20`, `CustomOffer.kt:28`
**Detekcija:** statička
**Opis:** Isti pattern kao F2-017 (Yacht/YachtImage/YachtTranslation). Sve gore navedene klase imaju vlastiti `@Id` umjesto da nasljeđuju `AbstractEntity<Long>`. Posljedice:
- **Nema `created`/`modified` kolone** (osim Inquiry i CustomOffer koji imaju manual `createdAt` polja → bar djelomično). Nemoguće je sortirati "recent offers" osim preko id-a.
- **Nema `creator_id`/`modifier_id`** — combined s F2-001/F2-004 (audit trail dead) — Offer cijena se može promijeniti, ne zna se tko.
- **Nema `entity_status` soft-delete pattern-a** — direct DELETE (vidi F2-022/F2-027) jedini način "remove-a", što ide preko hard delete (Envers _revisions ima dead bodies).
- **Nema centralnog `@Audited`** — F2-002 perf koncern se zaobilazi, ali audit trail nije ujednačen.

Najvažnija praktična posljedica: **brokeri mogu mijenjati cijenu/datume offer-a bez audit traga.** Combined s F2-026 (mutable PaymentPlan equals) i F2-022 (broken cleanup) — Offer lifecycle je nepouzdan.
**Predloženi fix:** isto kao F2-017 — extend AbstractEntity, migracija dodaje 4 kolone + backfill. Velik refactor. Mora ići u jednu pre-prod migraciju za sve "core business" entitete (Yacht + Offer + Inquiry + Reservation iz Batch 3a).
**Riziko-procjena fixa:** velik. Sync flow-ovi koji insertaju offer-e moraju postaviti created/modified iz partner data-e ili NOW(). Migracija dira velike tablice.
**Status:** OPEN — eskalacija (architectural decision, paralelno s F2-017)

---

### [F2-029] LOW code smell — `STR(:search)` JPQL funkcija u `findAllByParamsForAdmin` redundantna
**Lokacija:** `domains/catalouge/jpa/InquiryRepository.kt:15-17`
**Detekcija:** statička
**Opis:** Spomenuto pod F2-023 ali samostalno bilježim. JPQL `STR(x)` Hibernate funkcija konvertira argument u string (generira `CAST(? AS varchar)`). Za `:search` parametar koji je već `String?`, ovo je no-op. Vjerojatno copy-paste iz prijašnjeg query-ja gdje je parametar bio `Long` ili sl. Vizualni noise + zbunjuje budućeg developera ("zašto je ovdje STR?").
**Predloženi fix:** ukloniti `STR(...)` wrapper — koristiti `:search` direktno.
**Riziko-procjena fixa:** trivijalno.
**Status:** WAITING-DECISION (trivial)

---

### Sažetak Batch 3b

- **HIGH (1):** F2-022 (scheduled cleanup tiho ne radi — non-PG date syntax)
- **MED (3):** F2-023 (admin search seq scan, F1-068 multiplikator), F2-024 (per-insert email count seq scan, F1-068 multiplikator), F2-026 (mutable equals/hashCode u Set)
- **LOW (4):** F2-025 (cache key Yacht entity — sibling F2-007), F2-027 (cascade vs OnDelete mismatch), F2-028 (Offer flow entiteti ne extendaju AbstractEntity — proširenje F2-017), F2-029 (STR redundantan)
- **WAITING-DECISION:** F2-029 (trivijalan)

**Najkritičniji nalaz batch-a: F2-022.** Daily scheduled cleanup baca exception već **sad** u staging-u/dev-u (svaki dan u 06:00). U Spring `@Scheduled` log-u je tiho. Tablice rastu jer cleanup ne uspjeva. Mora se popraviti prije prod deploy-a — fix je <10 linija u 2 query-ja.

**Drugi važan trend:** F2-023 + F2-024 + F1-068 + F1-069 zajedno čine **inquiry endpoint DoS surface**. Index migracija (trigram + LOWER) značajno reducira napad-cost. Faza 6 kandidat za batch index migracije.

**Batch 3b završen. Batch 3c next:** Equipment, Extra, Location, Agency, Manufacturer, Model + view repos (FiltersView, AllLocationView, LocationView, YachtLocationsView, CustomYachtView).

---

## Batch 3c — Catalogue supporting: Equipment / Extra / Location / Agency / Manufacturer / Model + view repos (2026-05-11)

### [F2-030] MED perf — `AgencyRepository`: 3 query-ja s `JOIN FETCH` na kolekciju bez `DISTINCT` (cartesian product preko žice)
**Lokacija:** `domains/catalouge/jpa/AgencyRepository.kt:10-21` (`findAllActiveByPrimarySyncProvider`), `:39-53` (`findAllActiveByPrimarySyncProviderAndActiveYachts`), `:55-69` (`findAllActiveByPrimarySyncProviderAndHasYacht`)
**Detekcija:** statička
**Opis:** Sva tri query-ja imaju `JOIN FETCH a.agencySources ar JOIN FETCH ar.externalSystem es`. `agencySources` je `MutableSet<AgencySource>` na Agency-ju (1:N relacija). JPQL bez `SELECT DISTINCT a` znači da DB sloj vrati 1 row po (agency × source). Za agency s 5 sourcea (NauSys + MMK + …), 5 row-eva istog agency-ja iz DB-a → 5× više bytes preko žice. Hibernate de-dupes u memoriji (Set vraćeni tip dodatno maskira problem — caller misli da je sve OK), ali DB je već radio Cartesian.

Plus 4. query `findAllByParamsForAdmin` (`:88-111`) ima sibling-pattern F2-023/F2-029: `LIKE LOWER(CONCAT('%', STR(:name), '%'))` — admin search; isto seq scan + `STR()` redundantan.
**Posljedica:** marginalan network overhead per call (~5-10×). Sync jobs (`findAllActiveByPrimarySyncProvider*`) pozivaju ovo periodički iz NauSys/MMK scheduled task-ova; gomila se s vremenom.
**Predloženi fix:** dodati `SELECT DISTINCT a` u sva 3 query-ja. Trivijalno. Za 4. (`findAllByParamsForAdmin`) maknuti `STR()` wrapper (F2-029 pattern) i dodati trigram index u Faza 6 batch (F2-023 family).
**Riziko-procjena fixa:** dira sync hot path query-je — verify da Set dedupes pravilno (trebao bi, jer Hibernate uvijek mapira same-id Agency u isti entity instance unutar PersistenceContext-a).
**Status:** WAITING-DECISION (3× DISTINCT trivijalno; STR()/LIKE u Faza 6 grupi)

---

### [F2-031] MED perf — `Agency.agencySources` EAGER OneToMany + admin paginated query = N+1 per page hit
**Lokacija:** `domains/catalouge/jpa/Agency.kt:73-79` + `AgencyRepository.kt:88-111` (`findAllByParamsForAdmin`)
**Detekcija:** statička
**Opis:** `@OneToMany(mappedBy = "agency", fetch = FetchType.EAGER)` na `agencySources`. Komentar uz polje navodi razlog: "Page.map serialisation, detached entity reads silently get an empty proxy". Razuman workaround, ali ima cijena.

`findAllByParamsForAdmin` vraća `Page<Agency>` bez `JOIN FETCH a.agencySources`. Hibernate behaviour:
1. Run paginated SELECT s LIMIT (pravi pagination — OK).
2. Za **svaki** vraćeni Agency, fire **dodatni** SELECT za `agencySources` (EAGER). N+1.

Za pageSize=20: 1 select + 20 select-a za sources = 21 round trip-ova. Admin liste agencija ovaj problem ne osjete jer agencije nisu hot path, ali svaki page hit fire-a 20 dodatnih queryja koji se mogu izbjeći.

Plus: EAGER se primjenjuje i u **drugim** kontekstima koji ne čitaju `sources` (npr. `findByVatCode`, `findAllActiveWithoutYachts` — koji vraća `Set<Long>` ali Agency entity je ipak load-an EAGER tijekom hydratacije? — verify). Sve hydratacije Agency entity-ja kreiraju eager queries.

EAGER fetch je naprosto "antipattern by default" — pomaknuti detached-entity problem na lokalizirani fix umjesto global EAGER.
**Posljedica:** dodatni DB round trips. Marginalan na malom agency setu (~tens), znatniji ako lista raste.
**Predloženi fix:** dvije opcije:
- (a) **`@EntityGraph(attributePaths = ["agencySources", "agencySources.externalSystem"])` na findAllByParamsForAdmin** — Hibernate generira single JOIN FETCH inside paginirani query (preko outer-join workaround za pagination + collection); preserve pagination correctness via window-function. Vrati Agency entity LAZY, ali admin endpoint dobiva sources unutar TX-a.
- (b) **Skinuti EAGER**, naprasti LAZY default, i u svakom hot pathu koji treba sources dodati JOIN FETCH ili `@Transactional`-wrap caller-a. Veći refactor.

Opcija (a) je manje invazivna. Zatim `Agency.agencySources` može ostati LAZY.
**Riziko-procjena fixa:** dira admin queryje i mogući detached-entity callere — treba runtime verifikacija (admin page render se ne smije srušiti s LazyInitializationException-om).
**Status:** OPEN — Faza 5 (perf + runtime verifikacija) ili Faza 7

---

### [F2-032] LOW bug — `LocationViewRepository` declares `JpaRepository<LocationView, Long>` ali `LocationView.id` je `String`
**Lokacija:** `domains/catalouge/jpa/LocationViewRepository.kt:10` (`interface LocationViewRepository : JpaRepository<LocationView, Long>`) + `LocationView.kt:21-23` (`open var id: String?`)
**Detekcija:** statička
**Opis:** ID kolona je tipa string ("c-123" za country, "r-456" za region, "m-789" za marina — composite namespace). `LocationView.kt:21` deklarira `var id: String?`. Ali repository tipira ID kao `Long`. Ovo radi runtime jer:
- `findByIds(ids: List<String>)` (`:11-12`) prima String list — radi.
- Default `findById(Long)` metoda iz `JpaRepository<_, Long>` interface-a bi se srušila pri pozivu (cast String→Long), ali izgleda da je niko ne poziva.

Posljedica je code-smell: developer koji vidi `JpaRepository<LocationView, Long>` može pretpostaviti da LocationView ima numerički ID i pisati novi kod s `findById(123L)` — pri runtimeu dobije `ClassCastException` ili `EntityNotFoundException` ovisno o JPA verziji.
**Predloženi fix:** promijeniti deklaraciju na `JpaRepository<LocationView, String>`. Trivijalno; compile će potvrditi da nigdje već nije implicit `findById(Long)` u upotrebi.
**Riziko-procjena fixa:** trivijalan; compile-time check.
**Status:** WAITING-DECISION (trivial)

---

### [F2-033] MED perf — `LocationViewRepository.findByNameAndIdsNotIn` (public location autocomplete) = leading-wildcard LIKE → seq scan na svaki public search
**Lokacija:** `domains/catalouge/jpa/LocationViewRepository.kt:14-32`
**Detekcija:** statička
**Opis:** Public location autocomplete (search bar dropdown koji se zove pri svakom keystroke-u na search formi). Query:
```jpql
WHERE LOWER(lv.searchFiled) LIKE LOWER(CONCAT('%', :name, '%'))
```
Sibling F2-023 (admin Inquiry search), F2-024 (admin email count) i F2-034 (manufacturer/model search) — isti pattern. **Razlika:** ovo je **public, no-auth, high-traffic** endpoint (location autocomplete koristi se na home page). Svaki search request triggera seq scan na `location_view` (ne baš mala — Country + Region + Marina aggregated).

Combined s F1-070 (image resize bez validacije = OOM kandidat) i F1-064 (public yacht search trigger-a synkroni external sync) — public search putovi su sumarni DoS surface. Ovo dodaje DB CPU dimenziju.
**Posljedica:** spori autocomplete pod load-om. Pre-prod nije problem (DB je underutilizirana), ali jednom kad search promet skoči (marketing), ovo postaje vidljivo.
**Predloženi fix:** trigram (pg_trgm) GIN index — `CREATE INDEX location_view_search_filed_trgm_idx ON ... USING gin (LOWER(search_filed) gin_trgm_ops)`. **NAPOMENA:** `location_view` je **DB view**, ne tabela. Postgres ne dozvoljava index na view direktno; treba ili (a) materijalizirati u materialized view (s refresh trigger-om), ili (b) dodati index na **underlying source tablicama** (`location`, `region`, `country`) tako da view scan koristi te indekse. Najpragmatičnije: index na `LOWER(name)` u source tablicama + sigurati da view predicates push-down (verify EXPLAIN ANALYZE prije i poslije).

Plus: dodati minimum-length validation u controller-u (`name.length >= 2`) tako da `LIKE '%a%'` ne vraća pola tablice.
**Riziko-procjena fixa:** Flyway migracija (pg_trgm extension je već widely available; verify enabled na VM4 Postgres 18 instalaciji) + 1-3 funkcionalna indeksa. MED, neinvazivno za code.
**Status:** OPEN — Faza 6 (index migration batch s F2-023/F2-024)

---

### [F2-034] LOW perf — Niz interne admin/sync `LOWER + LIKE` queryja: Manufacturer, Model, Agency admin (LIKE-pattern porodica)
**Lokacija:**
- `domains/catalouge/jpa/ManufacturerRepository.kt:10` (`findManufacturersByNameIgnoreCase`)
- `domains/catalouge/jpa/ModelRepository.kt:14` (`findAllByManufacturerIdAndNameIgnoreCase`)
- `domains/catalouge/jpa/AgencyRepository.kt:93` (`findAllByParamsForAdmin`)
- `domains/catalouge/jpa/LocationRepository.kt:43` (`findByNameIgnoreCase` — case-insensitive equality)
- `domains/catalouge/jpa/AgencyRepository.kt:77` (`findByNameAndNotExistsInOtherSystem` — equality LOWER)

**Detekcija:** statička
**Opis:** Ista `LOWER(col) LIKE/=` familija kao F2-023/F2-024/F2-033 ali nižeg prometa (admin only, ili sync once-per-yacht). Sve dolaze pod isti pre-prod čeklist: ili (a) funkcionalni `LOWER(...)` indexes + (gdje treba) trigram, ili (b) prebaci na partial-equality s tačnim case-om kad je god moguće. Manufacturer i Model tablice su male (stotine-tisuće), pa scan nije akutan. Agency admin search je nisko-frekventan.
**Predloženi fix:** uključiti u istu Faza 6 index migration batch koju F2-023/F2-024/F2-033 zahtjevaju. Jedna migracija dodaje:
- `CREATE INDEX manufacturer_name_lower_idx ON manufacturer (LOWER(name))`
- `CREATE INDEX model_name_lower_idx ON model (LOWER(name))`
- `CREATE INDEX location_name_lower_idx ON location (LOWER(name))`
- `CREATE INDEX agency_name_lower_idx ON agency (LOWER(name))`
- + trigram indexes za leading-wildcard slučajeve (F2-023, F2-033).

Plus: maknuti `STR(:name)` iz `AgencyRepository.findAllByParamsForAdmin` (F2-029 sibling).
**Status:** OPEN — Faza 6 (vezano za F2-023/F2-024/F2-033, sve u jednoj migraciji)

---

### [F2-035] INFO positive — Database view entities pravilno `@Immutable` + composite ID klase Serializable s ispravnim equals/hashCode
**Lokacija:**
- `@Immutable` view entities: `FiltersView.kt`, `AllLocationView.kt`, `LocationView.kt`, `YachtLocationsView.kt`, `CustomYachtView.kt`, `YachtSearchView.kt` — svi imaju `protected set` na poljima, `@Immutable` aspekt sprječava write paths preko JPA-a.
- Composite ID klase: `LocationRegionId`, `AgencySourceId`, `YachtLocationsViewId` — sve `@Embeddable`, Serializable, equals/hashCode na temelju `Objects.hash()` + `Hibernate.getClass()` za proxy unwrap. Standardna best practice.

**Opis:** Pozitivna note. View entities ne mogu se accidentalno modificirati. Composite IDs ne mogu se mutirati post-construction (preko `protected set` ili final). Ne vidim niti jedan slučaj gdje je view entity flush-an natrag u DB (što bi failao runtime jer `@Immutable`).
**Status:** INFO

---

### Sažetak Batch 3c

- **MED (3):** F2-030 (Agency JOIN FETCH × 3 bez DISTINCT), F2-031 (Agency.agencySources EAGER → Page N+1), F2-033 (public location autocomplete seq scan)
- **LOW (2):** F2-032 (LocationViewRepository ID type mismatch), F2-034 (LOWER+LIKE familija — Manufacturer/Model/Agency admin/Location)
- **INFO (1):** F2-035 (view entities `@Immutable` + composite IDs solidni)
- **WAITING-DECISION:** F2-030 (3× DISTINCT trivijalno), F2-032 (Long→String repo deklaracija)

**Najvažniji nalaz batch-a:** F2-033 — public location autocomplete je high-traffic seq scan, vidljivo pod marketing burst-om. Combined s F2-023/F2-024/F2-034 ovo je **batch za Faza 6 index migration** — jedna Flyway migracija s pg_trgm extension + 4-5 funkcionalnih indexa pokriva cijelu LOWER/LIKE familiju.

F2-031 (Agency EAGER) zahtjeva runtime verifikaciju jer LAZY default može pucati u detached-entity context-u. Faza 5 ili Faza 7 (deploy-window testiranje).

**Batch 3 (catalogue core) završen kroz 3a/3b/3c.** Batch 4 (reservation flow) sljedeći: ReservationRepository, ReservationFlowRepository, ReservationDocumentRepository, ReservationPaymentPhaseRepository, ReservationViewRepository, ReservationYachtSwapAuditRepository, BookingSequenceRepository + pripadajući entiteti.

---

## Batch 4 — Reservation flow: Reservation / ReservationFlow / PaymentPhase / Document / SwapAudit / BookingSequence (2026-05-11)

### [F2-036] MED bug — `ReservationPaymentPhase.equals` vraća false kad su oba `paidOn` null; plus mutable Set anti-pattern (F2-026 sibling)
**Lokacija:** `domains/reservation/jpa/ReservationPaymentPhase.kt:39-63` (`equals`/`hashCode`) + `ReservationFlow.kt:106` (`paymentPhases: MutableSet<ReservationPaymentPhase>`)
**Detekcija:** statička
**Opis:** Dva bug-a u jednoj klasi:

**Bug 1 — null-handling u `equals`:**
```kotlin
if (paidOn?.equals(other.paidOn) != true) return false
```
Ako su `this.paidOn` i `other.paidOn` oba null:
- `null?.equals(other.paidOn)` short-circuit → `null`
- `null != true` → `true`
- → `return false` (entity-ji se smatraju **različitima** iako su strukturno identični)

Dva pre-payment phase-a (paidOn=null) s istom deadline+amount su `equals=false`. Hibernate dedupes po identity-ju za managed entity-je, ali kad se entity flush-a i ponovo loada iz različitih sessiona, behavior je inconsistent.

**Bug 2 — F2-026 family:** equals/hashCode čitaju mutable polja (`deadline`, `amount`, `paidOn`, `stripeSessionId`, `stripePaymentIntentId`) → entity se stavlja u `MutableSet<ReservationPaymentPhase>` na ReservationFlow → kad se `paidOn` postavi pri capture-u plaćanja (Stripe webhook), hashCode mijenja bucket → `paymentPhases.contains(phase)` može vratiti false čak ako je phase u set-u. Cascade ALL + orphanRemoval=false (default) — duplikati ili "lost" updates mogući pod load-om.

Kombinirano: bug 1 maskira bug 2 jer dva null-paid-on phase-a izgledaju različita pa naoko nema duplikata. Onda se prvo plaćanje captura, paidOn promijeni, hashCode pomakne, drugi capture proba update na "isti" phase ali Set kaže "ne postoji" → kreira dupli. **Tihi double-payment kandidat** u edge case-u.

Plus: equals/hashCode kontrakt subtilno povrijeđen — kad su oba paidOn null, hashCode oba returnuje konstantu (deterministic), ali equals returnuje false. Contract: `a.equals(b)==false` ⇒ `a.hashCode()` može biti razno, ali konverzno se mora držati. Tehnički ne-bug, ali nepouzdano.
**Posljedica:** mogući subtle duplikati u payment phase logici pri capture vremenu. Pre-prod testovi vjerojatno ne hvataju (zahtjeva specifičan timing). Combined s F1-019 (Stripe webhook non-idempotency CRIT), risk multiplies.
**Predloženi fix:** id-based equals/hashCode (idiom iz F2-026):
```kotlin
override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ReservationPaymentPhase) return false
    return id != null && id == other.id
}
override fun hashCode(): Int = id?.hashCode() ?: javaClass.hashCode()
```
Alternativno: pristup F2-026 (MutableList umjesto MutableSet) ako je redoslijed važan (po deadline-u).
**Riziko-procjena fixa:** dira entity contract — verify-grep `paymentPhases.contains(...)`, `paymentPhases.remove(...)` callere. MED. Treba ići zajedno s F2-026 (OfferPaymentPlan istog roda).
**Status:** OPEN — eskalacija (entity contract change, F2-026 family)

---

### [F2-037] MED bug — `calculateTotalPaid` JPQL `SUM` može vratiti null; deklarirano non-null `BigDecimal` → NPE risk
**Lokacija:** `domains/reservation/jpa/ReservationPaymentPhaseRepository.kt:24-31`
**Detekcija:** statička
**Opis:**
```kotlin
@Query("SELECT SUM(pp.amount) FROM ReservationPaymentPhase pp WHERE pp.reservationFlow.id IN (:reservationFlowIds) AND pp.paidOn IS NOT NULL")
fun calculateTotalPaid(reservationFlowIds: Set<Long>): BigDecimal
```
JPQL `SUM` na praznom result set-u vraća **null**. Kotlin return tip je `BigDecimal` (non-nullable) — compiler emit-a `@NotNull BigDecimal calculateTotalPaid(...)`. Spring Data dinamički prosljedjuje null vrijednost natrag, što:
- Java strana: dodaje null u `BigDecimal` variable — OK na JVM-u
- Kotlin caller-a: `kotlin.jvm.internal.Intrinsics.checkNotNullExpressionValue(...)` (ili sličan check) — u releasebuilds compiler inserts non-null assertion na method exit → `IllegalStateException` ili `NullPointerException` *kod* poziva metode.

Praktično: kad ReservationFlow set-a se proslijedi u `calculateTotalPaid` i nijedna phase nije plaćena (paidOn IS NULL na svim), SUM vraća null → NPE u caller-u. Vjerojatnost: niska u happy path (svaka rezervacija ima bar 1 plaćeno phase) ali svako "calculate paid for cancelled or pre-confirmed reservations" pada.

Plus: `sumCommissionByCreatedAtBetween` na `ReservationViewRepository.kt:79-86` koristi `COALESCE(SUM(rv.reservationCommission), 0)` — **isti repo, isti autor zna pattern.** Nedosljednost.
**Posljedica:** runtime NPE u edge case-u (no paid phases). Pre-prod testovi vjerojatno ne pogađaju. Failure mod: 500 response pri admin listing-u rezervacija s no-payment phases.
**Predloženi fix:** dva razumna pristupa:
- (a) `COALESCE(SUM(pp.amount), 0)` u query-ju (matchira existing `sumCommissionByCreatedAtBetween` pattern). Kotlin signature ostaje `BigDecimal`. Trivijalno.
- (b) Vratiti nullable `BigDecimal?` u Kotlin signature, callers eksplicitno handle-aju null. Više bolerplate-a ali eksplicitnije.

Preporučujem (a) — match-uje existing convention.
**Riziko-procjena fixa:** trivijalan. MED.
**Status:** WAITING-DECISION (trivial COALESCE add)

---

### [F2-038] MED audit gap — `ReservationDocument` ne extendira `AbstractEntity`; uploadi signed contracts / internal admin docs nemaju tamper-evidence trail
**Lokacija:** `domains/reservation/jpa/ReservationDocument.kt:28`
**Detekcija:** statička
**Opis:** ReservationDocument je klasični "compliance/legal" artifact — signed reservation contracts, deposit receipts, admin internal correspondence. Klasa ne extendira AbstractEntity, ne `@Audited`. Što imamo:
- `uploadedAt`, `uploadedBy` — present (createrInfo na samom row-u).
- `modified` / `modifier` / `entity_status` — **nedostaju**.

Što se ne tracka:
- Document modification (admin re-uploads with same id — ne može direktno, ali može `repository.save(modified_doc)`)
- Document deletion (hard delete preko `repository.delete(doc)`)
- Document visibility toggle (`isInternal` flip — admin može unhide internal handover note → customer dobije pristup, no trace)

GDPR + business risk: tipičan dispute scenario ("ja sam vam poslao taj signed contract, gdje je?", "obrisali ste moj receipt") — bez audit log-a, ne može se dokazati niti opovrgnuti. Plus: ako admin ažurira `isInternal=false` na sensitive internal note, no trail.

Posebno problematično jer **Reservation flow ima dedicated audit trail (`ReservationYachtSwapAudit`) za yacht swap**, ali document mutations nemaju ekvivalent.
**Posljedica:** legal/compliance gap. Vjerojatno nije immediate blocker (no PII brisanje obavezno traces danas), ali svaki dispute resolution je "they-said/we-said".
**Predloženi fix:** dvije razine:
- (a) **Minimalan:** ReservationDocument extend AbstractEntity → automatski dobiva created/modified/creator/modifier + Envers `_revisions` tablica. Migracija dodaje 4 kolone + backfill (`creator_id` = uploaded_by za postojeće redove, `created` = uploaded_at, `modified` = uploaded_at, `entity_status` = 'ACTIVE').
- (b) **Sveobuhvatan (preporučljivije za legal docs):** poseban audit table `reservation_document_audit` koji bilježi svaku verziju (append-only): `id, doc_id, action (UPLOAD|MODIFY|DELETE|VISIBILITY_FLIP|DOWNLOAD), actor_id, timestamp, details_json`. Ovo daje full trail uključujući download access (forenzika tko je preuzimao kada).

(a) je minimum za pre-prod. (b) je ako legal compliance to traži.
**Riziko-procjena fixa:** (a) zahtijeva F2-001/F2-004 prvo (creator/modifier popunjavanje); (b) je zaseban feature. Pre-prod prioritet: (a) zajedno sa F2-028 entity batch-em.
**Status:** OPEN — eskalacija (F2-001/F2-004 dependency; legal compliance check)

---

### [F2-039] LOW data corruption resilience — `ReservationFlowRepository.findIdsInReservationFlowChain` recursive CTE nema cycle detection
**Lokacija:** `domains/reservation/jpa/ReservationFlowRepository.kt:7-39`
**Detekcija:** statička
**Opis:** Native PostgreSQL `WITH RECURSIVE` CTE prati `previous_flow_id` chain ili gore (do head-a) ili dolje (svi descendant flows). Standardni recursive pattern, bez `CYCLE` klauzule (Postgres 14+ feature). Ako `previous_flow_id` chain ikad formira ciklus (A → B → A), CTE diverges → infinite query → connection timeout (HikariCP 20s) → exception, transaction rollback.

Cycle ne smije se desiti per business logic (yacht swap kreira *new* row, never back-reference). Ali:
- Manual SQL u ops mode (admin u psql) može slučajno postaviti previous_flow_id na ancestor → cycle.
- F1-074 (test suite divergence) — testovi koji manipuliraju DB direktno mogu kreirati corrupted state.
- Buggy sync u budućnosti.

Postgres 14+ ima `WITH RECURSIVE ... CYCLE id SET is_cycle USING path` syntax. Hibernate prosljedjuje native query as-is → može se koristiti.
**Posljedica:** ako cycle ikad postoji, sve query-je koje koriste flow chain padaju s timeout-om. Customer ne može vidjeti svoju rezervaciju, admin paneli za tu rezervaciju ne rade.
**Predloženi fix:** dodati explicit recursion depth limit. Najjednostavnije: izmijeniti CTE da broji `steps` i prekine na `WHERE steps < 50` (yacht swap chain nikad više od 50). Alternativno: Postgres 14+ `CYCLE` klauzula:
```sql
WITH RECURSIVE to_head AS (
  SELECT ... -- as before
) CYCLE id SET is_cycle USING path
```

Trivijalan defensive fix, ne dira pravi case (depth <50 uvijek).
**Riziko-procjena fixa:** dira native CTE — verify EXPLAIN ANALYZE da step-counter pristup performira jednako.
**Status:** OPEN — Faza 6 (defensive coding sweep) ili tracking-only

---

### [F2-040] LOW perf — `ReservationViewRepository.findAllReservationsByParams` 6-column LOWER+LIKE admin search (F2-023 family)
**Lokacija:** `domains/reservation/jpa/ReservationViewRepository.kt:21-50`
**Detekcija:** statička
**Opis:** Admin reservation search filtrira po `reservationNumber`, `reservationFlowName`, `reservationFlowSurname`, `reservationFlowEmail`, `agencyName` plus konkatenirani `name + ' ' + surname` — **6 LOWER+LIKE** klauza s leading wildcard. Isti pattern kao F2-023 (Inquiry admin), F2-033 (public location autocomplete), F2-034 (Manufacturer/Model). Admin-only, niža frekvencija od F2-033, ali query je 6×, ne 3× — najteži među admin search-evima.

Plus: search radi protiv `reservation_view` (DB view), ne tablice direktno → indexi moraju biti na underlying tablicama (`reservation`, `reservation_flow`, `agency`).
**Posljedica:** seq scan na bookings table-u. Bookings table je core business — biti će više row-eva nego inquiry-ja. Spori admin search uz rast.
**Predloženi fix:** uključiti u istu Faza 6 index migration batch koju F2-023/F2-024/F2-033/F2-034 zahtjevaju:
- `CREATE INDEX reservation_flow_email_trgm_idx ON reservation_flow USING gin (LOWER(email) gin_trgm_ops)`
- `CREATE INDEX reservation_flow_name_trgm_idx ON reservation_flow USING gin (LOWER(name) gin_trgm_ops)`
- `CREATE INDEX reservation_flow_surname_trgm_idx ON reservation_flow USING gin (LOWER(surname) gin_trgm_ops)`
- `CREATE INDEX reservation_reservation_number_trgm_idx ON reservation USING gin (LOWER(reservation_number) gin_trgm_ops)`
- agency.name već pokriven F2-034 batch-em.

Plus: minimum-length validation u kontroleru — `search.length >= 2` preduvjet.
**Status:** OPEN — Faza 6 (vezano s F2-023/F2-024/F2-033/F2-034 — jedna migracija pokriva sve)

---

### [F2-041] LOW model consistency — `ReservationFlow.status` ima `// TODO should we remove this?` + ReservationFlow/ReservationDocument/ReservationYachtSwapAudit/BookingSequence/ExternalReservationPaymentPlan ne extendaju AbstractEntity (F2-028 family)
**Lokacija:** `domains/reservation/jpa/ReservationFlow.kt:62-65` + entity izvori (gore navedeni)
**Detekcija:** statička
**Opis:** Dvije male povezane stavke:

**1. Dead code smell:** `ReservationFlow.status: ReservationFlowStatus?` ima inline komentar `// TODO should we remove this?`. Status enum je `UNKNOWN/IN_PROGRESS/DONE/ABANDONED` (V1_90 mapping). Verify-grep tko poziva `flow.status`:
- Ako neki kod čita → ostavi i ukloni TODO.
- Ako nitko → drop kolone, Flyway migracija + entity polje delete.

**2. F2-028 proširenje:** ReservationFlow, ReservationDocument, ReservationYachtSwapAudit, BookingSequence, ExternalReservationPaymentPlan ne extendaju AbstractEntity. Posljedice (kao F2-017/F2-028):
- ReservationFlow audit trail: nema modifier/created/modified columns (osim `createdAt`); F2-038 audit gap za documents.
- ReservationYachtSwapAudit: namjerno append-only, ne treba AbstractEntity (intentional design).
- BookingSequence: counter, ne treba audit (intentional).
- ExternalReservationPaymentPlan: business data, treba audit.

Iznimka: **ReservationPaymentPhase DOES extend AbstractEntity** — prva u batch-u, dobra praksa.
**Posljedica:** ako se ovi entiteti modificiraju (cancel rejection na ReservationFlow, payment plan recalc na ExternalReservationPaymentPlan), no audit trace.
**Predloženi fix:** u sklopu F2-001/F2-004/F2-017/F2-028 architectural batch:
- ReservationFlow, ExternalReservationPaymentPlan: extend AbstractEntity. ✓
- ReservationDocument: F2-038 odgovara — alternativan audit table ili AbstractEntity.
- ReservationYachtSwapAudit, BookingSequence: ostaviti kako su (intentional — append-only / counter).

Plus: maknuti TODO komentar uz `ReservationFlow.status` nakon odluke.
**Status:** OPEN — eskalacija (F2-028 family architectural decision)

---

### [F2-042] INFO positive — Reservation flow design solidan: pessimistic lock za sequence, DTO projekcija za BYTEA, denormalized FK-ovi u swap audit (intentional)
**Lokacija:**
- `BookingSequenceRepository.kt:13-15` — `@Lock(LockModeType.PESSIMISTIC_WRITE)` na `findByCharterYearForUpdate` (concurrent-safe sequence generation).
- `ReservationDocumentRepository.kt:11-22` — `findMetadataByReservationId` projeicira u `ReservationDocumentDto` bez `data` BYTEA polja (avoid OOM kad customer otvara reservation detail s 5 documents).
- `ReservationYachtSwapAudit.kt:32-46` — plain `Long` polja za `reservationId`/`reservationFlowId`/`previousYachtId`/`newYachtId` bez `@ManyToOne` (intentional snapshot — audit row ne smije pucati ako referenced yacht obrisana).
- `ReservationViewRepository.kt:79-86` — `sumCommissionByCreatedAtBetween` koristi `COALESCE(SUM, 0)` (model za F2-037 fix).

**Opis:** Sva 4 patterna su industry best practices. Vrijedi ih dokumentirati za održavanje (npr. team wiki / CLAUDE.md doc).
**Status:** INFO

---

### Sažetak Batch 4

- **MED (3):** F2-036 (PaymentPhase equals null-bug + mutable Set; F2-026 family), F2-037 (calculateTotalPaid SUM null NPE risk), F2-038 (ReservationDocument audit gap)
- **LOW (3):** F2-039 (recursive CTE bez cycle detection), F2-040 (ReservationView admin search 6×LIKE — F2-023 family), F2-041 (status TODO + AbstractEntity extension family)
- **INFO (1):** F2-042 (4 pozitivna patterna u reservation flow)
- **WAITING-DECISION:** F2-037 (trivial COALESCE)

**Najkritičniji nalaz batch-a: F2-036.** Combined s F1-019 (Stripe webhook non-idempotency CRIT) — payment phase double-capture u edge case-u je realan scenarij. Fix treba prije prod-a. Ide u isti batch s F2-026 (OfferPaymentPlan — isti rod problema).

**Drugi važan trend:** F2-037 je trivial COALESCE ali ide u F2-029/F2-032 trivial cleanup batch. Plus F2-040 dodaje 4 indexa u F2-023/F2-024/F2-033/F2-034 Faza 6 migration batch — sad je to **5 finding-a u jednoj migraciji.**

**Batch 4 završen. Batch 5 next:** Flyway migracije — chronological pass, focus na riskantne ALTER, NOT NULL backfill, DROP COLUMN. Specifične mete iz inventory-ja: V1_24/V1_54/V1_55 (DROP), V1_64/V1_69/V1_70 (NOT NULL backfill), plus V1_90 (own STRING migracija) i V1_57 (payment_type backfill).

---

## Batch 5 — Flyway migracije (2026-05-11)

### [F2-043] CRIT security — V9_xx test data migracije nisu gated; prod Flyway će ih izvršiti i unijeti Workspace.hr team usere s shared bcrypt hashem
**Lokacija:**
- `src/main/resources/application.yml:87-93` — `spring.flyway.target: ${FLYWAY_TARGET_VERSION:latest}`
- `application-prod.yml:67-71` — Flyway prod config ne overrida `target` ni `locations`
- `db/migration/V9_00__insert_test_data.sql` — 15+ user redova s `password = $2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu` (identičan za sve), emails `*@workspace.hr` (pravi team members)
- `db/migration/V9_02__nausys_test_agency.sql`, `V9_03__mmk_test_agency.sql` — agency test data

**Detekcija:** statička + grep across yml-a
**Opis:** Flyway sortira migracije po version number-u. V1_00..V1_90 → V9_00 → V9_02 → V9_03 → V9_04 → V9_05. `target: latest` (default) povlači **sve V-migracije** redom. Nema:
- `spring.flyway.locations` override (npr. `classpath:db/migration/prod`) u `application-prod.yml`
- `FLYWAY_TARGET_VERSION` env var hardcoded na `1.90` u deploy artefaktu
- `FLYWAY_IGNORE_MIGRATION_PATTERNS=V9_*` (Flyway 9+ feature) postavka
- Spring profile-grouping unutar V9_xx samog (npr. `@Profile("dev")` zaštita — Flyway ignorira Spring profile semantiku)

Što to znači u praksi:
1. Pri prvom prod deploy-u Flyway će rastrti SVE V1_xx + V9_xx migracije
2. V9_00 ubacuje **15+ pravih Workspace.hr team userа** (Pero Škvorčević, Asan Štekl, Ena Olčar, Lovre Barunđek, ... ti i ostali) s emails koji odgovaraju stvarnim ljudima
3. **Svi imaju isti bcrypt hash** — `$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu` (cost 10, plaintext koji TI / team znate iz dev-a)
4. Hash je u public-no-poznatom layout-u repa → bcrypt offline crack je izvediv s focused attacker-om i common dev password rječnikom
5. Ako V9_xx također insertira role_assignments za te usere (likely — test data simulira admin/broker workflow), te accounte mogu biti SYSTEM_ADMIN-i

**Combined posljedice:**
- Anyone na Workspace.hr team-u koji zna dev password može se logirati u prod-u kao svoj testni `@workspace.hr` user
- Bilo tko s repo accessom (npr. ex-employee) može pokušati offline brute-force na bcrypt hash i pristupiti istim accounts u prod-u
- Ako test users imaju admin role, prvi sat prod uptime-a je full compromise

**Predloženi fix:** **tri sloja obrane — primijeniti sve:**
1. **Env var u prod deploy-u (must-have):** `FLYWAY_TARGET_VERSION=1.90` (ili koji god V1.xx je posljednja prod migracija). Flyway će preskočiti V9_xx jer su izvan target window-a.
2. **Yml-side belt-and-braces:** dodati u `application-prod.yml` `spring.flyway.ignore-migration-patterns: ["*:pending"]` + `target: 1.90` kao default; ili još bolje, premjestiti `V9_*` u zaseban folder `db/migration/test-only/` koji se ne uključuje u prod `locations`:
```yaml
# application.yml (default — uključuje sve)
spring.flyway.locations: classpath:db/migration,classpath:db/migration/test-only
# application-prod.yml
spring.flyway.locations: classpath:db/migration   # bez test-only
```
3. **Sanitize V9_00:** ukloniti pravi `@workspace.hr` emails — koristiti `test-{n}@boat4you.invalid` format. Plus randomizirati bcrypt hash po useru (ili koristiti standardni "Password123!" sa unique salt-om koji ne odgovara nijednom realnom korisničkom paswordu).

**Pretpostavka koju treba verificirati prije prod cutover-a:**
- Trenutno prod env var-i (VM2/VM3 deploy yaml/systemd unit) DA LI ima `FLYWAY_TARGET_VERSION` postavljen?
- Ako da → manje urgentno, ali (2) + (3) treba i dalje uraditi
- Ako ne → **prod-blocker, MUST fix u sljedećem deploy-u**

**Riziko-procjena fixa:** (1) je trivijalan — dodati env var. (2) zahtjeva direktorij restructuring. (3) je code change u V9_00 (ali Flyway već applied → checksum mismatch ako se mijenja in-place; treba `repair`).
**Status:** OPEN — **prod-blocker dok se ne potvrdi (1)**. Eskalacija.

---

### [F2-044] HIGH risk — `V1_24__drop_columns.sql` destructive DROP COLUMN bez rationale komentara, bez verify-grep-a
**Lokacija:** `db/migration/V1_24__drop_columns.sql:1-2`
**Detekcija:** statička
**Opis:** Cijeli sadržaj migracije:
```sql
ALTER TABLE external_reservations DROP COLUMN external_id;
ALTER TABLE offer DROP COLUMN payment_plans;
```
Dva DROP COLUMN-a bez ijednog komentara. Po Mariovoj konvenciji (svaka druga migracija ima 5-50 linija obrazloženja), **ovo je migracioni orphan.** Što ne znamo bez detaljnog grep-a:
- **`external_reservations.external_id`** — bio je vjerojatno zamijenjen s `externalId: Long` na ExternalReservation entity-ju (vidio sam u Batch 3a). Ali bez komentara/PR linka, ne može se utvrditi je li ovo bilo:
  - drop dead orphan column (safe)
  - drop column s podacima koji su migrirani drugdje (potencijalan data loss ako migracija nije bila izvršena prvo)
  - drop column koji je još uvijek bio koristen → app pao
- **`offer.payment_plans`** — eventualno zamijenjen s `OfferPaymentPlan` tablicom (1:N). Isto, bez context-a ne znamo je li bilo backfill-a iz column-a u novu tablicu.

Posljedice za pre-prod review:
1. Code review bez context-a (ti, autor) — ne može se utvrditi je li to bio safe ili loss-y drop.
2. Pre-prod prvi-put-deploy radi clean install → V1_24 obriše prazne kolone → safe (jer još nije bilo podataka).
3. **Ali ako se ikad ponovno trebao migrirati neki staging/QA DB koji je preživio V1_03 era** — bilo bi izgubljeno.

**Predloženi fix:** dodati post-hoc komentar u V1_24 (Flyway prihvaća izmjene komentara bez checksum break-a samo ako `validateMigrationNaming` ne uključuje SQL diff; provjeri jer Flyway hashira sadržaj). Vjerojatno najbolje: **zaseban PR-only doc** u repu koji opisuje "Migracija audit log: V1_24 dropped external_reservations.external_id (replaced by external_id Long field on entity, no backfill needed because table was empty at the time), offer.payment_plans (replaced by OfferPaymentPlan separate table, V1_03 backfill confirmed in commit XYZ)".

Plus: dodati to-do u Phase 7 checklist: verify-grep za reference na ove kolone u stargim git commit-ima i confirm da nije izgubljeno.
**Riziko-procjena fixa:** dokumentacijski, ne dira shemu. LOW. Ali finding sam je HIGH jer code reviewer ne može potvrditi safety bez sutina.
**Status:** OPEN — **HIGH za pre-prod** dok se ne dokumentira (može se rezolvirati za 30min Mario commentary)

---

### [F2-045] MED risk — `V1_64__inquiry_phone_required.sql` SET NOT NULL bez safety net-a; prod deploy fail-a ako postoji ijedan NULL
**Lokacija:** `db/migration/V1_64__inquiry_phone_required.sql:12-13`
**Detekcija:** statička
**Opis:**
```sql
ALTER TABLE inquiry ALTER COLUMN phone SET NOT NULL;
```
Komentar kaže "verified before pushing" (dev) i "if a future scrape uncovers a legacy NULL row, set it to a sentinel like '' before re-running". **Verify je human-driven, ne migration-driven.**

Risk-scenario:
1. Prod kopija inquiry tablice ima 1+ NULL phone-ova (jer prod podaci ≠ dev podaci, naročito ako inquiry endpoint je već bio prod-public i F1-068 email-bombing CRIT je već iskorišten od nekog — netko poslao curl request bez phone polja).
2. V1_64 izvršava se → `column "phone" contains null values` SQL Error → cijela Flyway migracija fail-a → app ne startira.
3. Recovery: ručno SQL `UPDATE inquiry SET phone='' WHERE phone IS NULL; flyway repair; flyway migrate`. Otkud admin dobije pristup prod DB-u u tom trenu? VM4 PostgreSQL direktan psql access od support-a.

Industry pattern za NOT NULL backfill u jednoj migraciji:
```sql
UPDATE inquiry SET phone = '' WHERE phone IS NULL;
ALTER TABLE inquiry ALTER COLUMN phone SET DEFAULT '';
ALTER TABLE inquiry ALTER COLUMN phone SET NOT NULL;
```
Idempotentno, bez surprise-a. Prvi UPDATE = no-op ako nema NULL-ova, ali sigurnost-net.
**Posljedica:** prod deploy može pasti pri prvoj-run-i ako ima NULL-ova. Recovery: SLA degradation, ručna intervencija.
**Predloženi fix:** dodati explicit `UPDATE ... SET phone='' WHERE phone IS NULL` ispred ALTER-a. Trivijalno. **Ali Flyway već applied** ovu migraciju u dev/staging → checksum mismatch ako se mijenja. Alternative: dodati **novu** migraciju V1_91 koja prvo UPDATE-a NULL-ove ako postoje, prije nego V1_64 efekt vrijedi. Reverse impossible — V1_64 already ran in dev.

**Najpragmatičnije:** prije prod deploy-a, izvršiti manualni `SELECT COUNT(*) FROM inquiry WHERE phone IS NULL` u staging-mirror-of-prod copy → ako ≥1, `UPDATE inquiry SET phone='' WHERE phone IS NULL` prije Flyway run-a.
**Riziko-procjena fixa:** human-driven preflight check umjesto in-migration safety. MED jer recovery je možda 1h SLA degradacije.
**Status:** OPEN — pre-prod operational checklist (verify before deploy, ne code change)

---

### [F2-046] MED maintenance — `V1_57__add_extra_payment_type.sql` hardkodira ordinal vrijednosti (0-3); zavisi o V1_90 kasnijem mapping-u
**Lokacija:** `db/migration/V1_57__add_extra_payment_type.sql:36-55`
**Detekcija:** statička
**Opis:** V1_57 backfill:
```sql
UPDATE offer_extras SET payment_type =
    CASE
        WHEN price IS NULL OR price = 0 THEN 0  -- INCLUDED
        WHEN payable_in_base = FALSE THEN 1     -- WITH_BOOKING
        WHEN LOWER(name) ~ 'tourist tax|...' THEN 3  -- ON_SITE
        ELSE 2  -- ADVANCE_TO_OPERATOR
    END
WHERE payment_type IS NULL;
```
Stavi integers 0-3 u smallint kolonu. V1_90 kasnije konvertira u VARCHAR(31) preko CASE:
```sql
ALTER TABLE offer_extras ALTER COLUMN payment_type TYPE VARCHAR(31) USING (
    CASE payment_type
        WHEN 0 THEN 'INCLUDED'
        WHEN 1 THEN 'WITH_BOOKING'
        WHEN 2 THEN 'ADVANCE_TO_OPERATOR'
        WHEN 3 THEN 'ON_SITE'
        ELSE NULL
    END);
```

Funkcionalno chain radi: V1_57 stavi `2`, V1_90 mapira u `'ADVANCE_TO_OPERATOR'`. Ali **dve migracije moraju biti u sync-u na enum ordinal-u.** Ako se ikad doda novi ExtraPaymentType vrijednost ili reorderira enum (npr. dodati `OWNER_REIMBURSEMENT` između WITH_BOOKING i ADVANCE_TO_OPERATOR):
1. V1_90 mora dobiti novu `WHEN 2 THEN 'OWNER_REIMBURSEMENT'` linija + sve sljedeće shift up
2. V1_57 ostaje hardkodiran s 0-3 → ali V1_90 ga već je već primijenio, novi shape se primjenjuje samo na **nove** rows.

Konfuzno za održavanje. Plus: V1_57 implicit dependency na implementaciju `ExtraPaymentType.classify()` Kotlin function — komentar pravilno kaže "keep both regexes in sync".

**Posljedica:** dokumentacijska. Ne pucа prod, ali povećava cognitive overhead i drift risk.
**Predloženi fix:** post-fact ništa (V1_57 + V1_90 već applied — checksum mismatch ako se mijenja). **Za buduće slične backfill-e:** koristiti string vrijednosti odmah (V1_57 bi mogao biti `SET payment_type = 'INCLUDED'` da je kolona bila VARCHAR od početka). Tracking-only finding.
**Status:** OPEN — tracking-only (nema concrete fix-a za applied migracije, naučili-smo-lekciju)

---

### [F2-047] MED fragility — `V1_69`/`V1_70` backfill custom_yacht location pretpostavlja country.id == location.id u numeričkom domenu
**Lokacija:** `db/migration/V1_69__backfill_custom_yacht_location.sql:17-23` + `V1_70__backfill_custom_yacht_location_via_country_key.sql:14-21`
**Detekcija:** statička
**Opis:** V1_69 set-a:
```sql
UPDATE public.yacht y SET location_id = cyd.country_id ...
```
Komentar kaže: *"Country and Location share numeric IDs (Greece = 86 in both tables), so a direct copy works."* V1_70 fallback parsea `country_key` text → `c-86` → 86 → set-a kao location_id.

**Pretpostavka:** numerički ID 86 postoji i kao `country.id = 86` i kao `location.id = 86` istovremeno, **i predstavlja isti entitet** (Greece). To je **akcidentalno-aligned** pattern koji nije enforced ničim — ni FK-om, ni constraint-om, ni dokumentom. Risk-scenariji:
1. Admin doda novu country (npr. Bahamas) preko admin UI-ja → country dobije sljedeći auto-increment ID (npr. 167). Location tablica ima već 167-ti red (npr. neki marina u Hrvatskoj). Sad `country.id = 167` ≠ `location.id = 167` semantically. Future V_xx koji ponovo radi V1_69 logic → set-a custom yacht location na krivu lokaciju.
2. Reset sequence-eva ili partial restore iz backup-a → numerički kolaps. Custom yachts ode na pogrešno location.

V1_69 + V1_70 su jednokratni backfill-evi tako da su safe **sad**. Ali pattern može biti ponavljan u budućim migracijama koji rade isto (npr. ako se doda nova "custom yacht country" feature).

**Posljedica:** pre-prod nije rušilac. Faktor budućeg maintenance bug-a.
**Predloženi fix:** trace gdje god se računa `location_id` iz `country_id` (grep aliases) i dodati **comment explicit assertion** + (idealnije) helper SQL function `country_id_to_location_id(country_id INT)` koja eksplicitno mapira (sad: identity, ali centralizirano). Alternatively: refactor da custom yachts imaju zaseban `location_for_custom_yacht_id` koji je optional.

Plus: dodati constraint check (post-deploy diagnostic): `SELECT y.id FROM yacht y JOIN custom_yacht_details cyd ON y.id=cyd.yacht_id WHERE y.entry_type=2 AND y.location_id IS NOT NULL AND y.location_id <> cyd.country_id` — verify konzistencija mjesečno.
**Riziko-procjena fixa:** dokumentacijski / dodatni helper. LOW invasiveness. MED concern.
**Status:** OPEN — Faza 6 (data model documentation sweep)

---

### [F2-048] LOW maintenance — `V1_54` recreates yacht_search_view s hardkodiranim `o.status <> 4` (ordinal); kasnije superseded by V1_90+R__1_03
**Lokacija:** `db/migration/V1_54__drop_yacht_locations_relict.sql:92` (kao i ostali V1_xx koji recreate-aju view: V1_60, V1_67)
**Detekcija:** statička
**Opis:** V1_54 recreate-a `yacht_search_view` s native ordinal komparacijom: `o.status <> 4` (= NOT UNAVAILABLE po OfferStatus enum-u). Ova logika je nasljedovana iz V1_03 pre-F2-018 ere. **Funkcionalno OK** u Flyway run order-u jer:
1. V1_54 → view s ordinal 4
2. V1_90 → DROP VIEW (jer convert payment_type/status kolonu)
3. R__1_03_yacht_search_view.sql → recreate view s `o.status <> 'UNAVAILABLE'` (string ordinal)

Konačno stanje u prod-u nakon ALL migracija: string-typed view. **Ali developer koji čita V1_54 vidi ordinal 4, mora skrolati do R__-a da nađe trenutni shape.** Confusing.

Isti pattern: V1_60 (`o.status <> 4`), V1_67 (`o.status <> 4`), V1_63 (reservation_view recreate).
**Posljedica:** maintainability — kognitivno trošeno za chase trenutnog view shape-a kroz 5 migracija + R__.
**Predloženi fix:** nothing actionable (Flyway already applied — checksum mismatch ako se mijenja). Future: nikada ne recreate viewa u V_xx, koristiti dedicated R__ uvijek (Flyway dokumentacija to već preporučuje). Tracking-only.
**Status:** OPEN — tracking-only / convention note

---

### [F2-049] LOW drift risk — `V1_88__manufacturer_model_dedup.sql` regex normalization rules zavise od sync s `ManufacturerAliasResolver.kt`
**Lokacija:** `db/migration/V1_88__manufacturer_model_dedup.sql:99-103` (REGEXP_REPLACE drop-suffix list) + `domains/external/.../ManufacturerAliasResolver.kt` (Kotlin equivalent)
**Detekcija:** statička
**Opis:** V1_88 koristi REGEXP_REPLACE da skida noise sufixe (`yachts`, `yachtbau`, `catamarans`, `group`, ...) plus TRANSLATE za accent fold. Komentar (l.17-20) kaže "see ManufacturerAliasResolver.kt for the full alias list mirrored in code". Kotlin resolver ima ekvivalent koji se zove iz sync flow-a.

**Drift risk:** ako developer doda novi alias u `ManufacturerAliasResolver.kt` (npr. "Boats Inc." se treba dedup-irati kao "Boats"), V1_88 ostaje hardkodiran na trenutnu listu. Sync detected duplicate (jer Kotlin resolver tako kaže), ali DB nema migrirani dedup → sync flow vjerojatno radi `OR INSERT IGNORE` ili sl. Inkonzistencija između application-level i DB-level dedup.
**Posljedica:** maintainability bug-čekanju. Pre-prod nije akutni problem.
**Predloženi fix:** dvije opcije:
- (a) **Tracking comment:** dodati u `ManufacturerAliasResolver.kt` komentar koji eksplicitno kaže "kad mijenjaš listu, dodaj novu Flyway V_xx migraciju koja primjenjuje istu dedup logic na DB" + checklist u CONTRIBUTING.md.
- (b) **Single source of truth:** prebaciti regex listu u DB tablicu `manufacturer_alias_pattern` koju Kotlin čita i Flyway updates. Veće preuređenje.

(a) je za pre-prod (5min commit). (b) za Faza 6 ako bude potrebe.
**Status:** OPEN — Faza 6 (drift-prevention pattern)

---

### [F2-050] INFO positive — Reservation flow + data hygiene migracije pokazuju dobar standard
**Lokacija:** više migracija
**Opis:** Pozitivni patternsi za zabilježit:
- **V1_45 booking_number** — `DROP VIEW IF EXISTS` (idempotent), comment objašnjava R__ recreate; jasan rationale.
- **V1_50 reservation_yacht_swap_audit** + **V1_53 yacht_dedup_audit** + **V1_58 payment_refund_audit** — append-only audit tables, dedicated forenzika. Pattern koji bi F2-038 ReservationDocument trebao slijediti.
- **V1_56 unique_stripe_session_id** — unique constraint na Stripe session ID je dobra defense protiv F1-019 (Stripe webhook idempotency).
- **V1_78 user_soft_delete_gdpr** — partial unique index `WHERE deleted_at IS NULL`, anonymization strategy detailed in comment, FK constraint considerations addressed. Top-tier GDPR migracija.
- **V1_79 gdpr_audit_log** — audit log per Article 5(2) accountability; columns za IP + user agent + completion timestamp. Pattern za drugi domain audit (vidi F2-038).
- **V1_82 password_reset_code_issued_at** — TTL pattern eksplicitno po OWASP cheatsheet-u. Eksplicitan reference.
- **V1_88 manufacturer_model_dedup** — idempotent (HAVING > 1 + EXISTS guards), TEMP tables ON COMMIT DROP, RAISE NOTICE za visibility. Veliki SQL pravilno strukturiran.
- **V9_04, V9_05** — FK index additions; dobra perf hygiene (ali profile-confusing — vidi F2-043, ovi su zapravo prod-relevantni indexi pomiješani sa test data).
- **V1_57 + V1_88** — koriste `RAISE NOTICE` / log statements za visibility tijekom Flyway run-a.

Vrijedno standardize-irati: future migracije slijede V1_78/V1_88 strukturu (rationale comment, idempotency, log statements, FK considerations).
**Status:** INFO

---

### Sažetak Batch 5

- **CRIT (1):** F2-043 (V9_xx test data run-a u prod-u → 15+ Workspace.hr team accounts s shared bcrypt hash → potential full prod compromise)
- **HIGH (1):** F2-044 (V1_24 destructive DROP COLUMN bez rationale — code reviewer ne može potvrditi safety)
- **MED (3):** F2-045 (V1_64 NOT NULL bez safety net — prod deploy može pasti na NULL-ovima), F2-046 (V1_57 hardkodira ordinal koji V1_90 mapira — drift risk), F2-047 (V1_69/V1_70 country.id == location.id assumption fragile)
- **LOW (2):** F2-048 (V1_54+V1_60+V1_67 hardkodiraju ordinal `o.status<>4` u view recreate-u), F2-049 (V1_88 regex normalization drift s ManufacturerAliasResolver.kt)
- **INFO (1):** F2-050 (10+ pozitivnih patterna — GDPR, audit, idempotency, OWASP TTL, FK indexes)

**Najkritičniji nalaz batch-a: F2-043 CRIT.** Mora se verificirati prije prvog prod deploy-a: postavljen li je `FLYWAY_TARGET_VERSION=1.90` u prod env-u? Ako ne, prvi-put-up insertira test team accounts u prod. Trivial fix (env var dodati), ali kritična verifikacija.

**F2-044 HIGH** je dokumentacijski. Tvojih 30min razgovora razrješi — V1_24 nije code bug, samo nedostaje historical context komentar.

**Batch 5 završen. Phase 2 read-pass GOTOV.** Sljedeća akcija: **closure summary + phase gate decision** (analogno Phase 1 zatvaranju u `7369434` + `9d97675` commit-ima).

---

## Phase 2 closure (2026-05-11)

**Status:** CLOSED — read-pass complete, fix-batch decisions deferred to user.

### Cumulative numbers

| Bucket | Count | Note |
|---|---|---|
| Findings filed | 50 | F2-001..050 |
| FIXED | 3 | F2-018 / F2-019 (`0d1242a` enum STRING migration), F2-022 (`0dc514f` scheduled cleanup) |
| OPEN | 44 | 1 CRIT + 1 HIGH + 19 MED + 23 LOW |
| INFO | 3 | F2-035 / F2-042 / F2-050 — positive patterns |
| Read-pass batches | 7 | 1, 2, 3a, 3b, 3c, 4, 5 ✓ |

### Verifications atempted during read-pass

- **F2-018 enum migration:** 18 enum mappings cross-verified against Kotlin source declaration order pre-commit. 22 entity fields swept via `@Enumerated` grep — zero leftover ordinal annotations after `0d1242a`. Native query ordinal literals in YachtRepository + view R__-ovi switched to string equivalents.
- **F2-022 cleanup queries:** verified callers via grep (`DeleteExpiredReservationsAndOffersJob` `@Scheduled(cron = "0 0 6 * * ?")` triggers from prod); confirmed no tests reference `deleteExpiredOffers` (justifies severity — no test fail had been catching this).
- **F2-043 V9_xx test data execution risk:** verified application.yml + application-prod.yml + ad-hoc grep — no `FLYWAY_TARGET_VERSION` or `spring.flyway.locations` override exists in tracked code. **Out-of-repo verification needed:** prod env var setting (VM2/VM3 deploy artefact).
- **`@Enumerated` sweep:** zero raw `@Enumerated` annotations remain in `src/main/kotlin` per `Grep @Enumerated$` post-F2-018.
- **`.ordinal` usage:** 3 hits, all in `ReservationOptionsCombinationProvider.kt` for `DayOfWeek` calendar math (not DB persistence). Safe.

### Trends — fix-batch groupings prepared for execution phases

Phase 2 yielded clean cluster-shaped fix batches that share migration / refactor work; tackling them as groups will minimize touch footprint:

1. **Faza 6 — index migration batch (5 findings):** F2-023 (Inquiry admin search), F2-024 (per-insert email count), F2-033 (public location autocomplete), F2-034 (Manufacturer/Model/Agency admin LIKE family), F2-040 (ReservationView 6-column admin search). One Flyway migration: `CREATE EXTENSION IF NOT EXISTS pg_trgm` + `CREATE INDEX ... USING gin (LOWER(col) gin_trgm_ops)` per table + functional `LOWER(name)` indexes on equality lookups. Plus controller-side `name.length >= 2` validations.

2. **Audit trail eskalacija batch (6 findings + 1 dependency chain):** F2-001 (creator/modifier never populated), F2-004 (CustomRevisionListener hardcodes user 1), F2-017 (Yacht/YachtImage/YachtTranslation ne extendaju AbstractEntity), F2-028 (Offer-flow entitети ne extendaju), F2-038 (ReservationDocument audit gap), F2-041 (ReservationFlow + others ne extendaju + status TODO). **Single architectural decision** unlocks all of them: hook `SecurityContextHolder` into AbstractEntity `@PrePersist`/`@PreUpdate` + decide which currently-non-AbstractEntity classes extend it. Then one Flyway migration adds 4 columns × N tables with backfill.

3. **Payment phase entity contract batch (2 findings):** F2-026 (OfferPaymentPlan equals on mutable fields, `MutableSet`), F2-036 (ReservationPaymentPhase same + null-handling bug). **Critical multiplier with F1-019 CRIT (Stripe webhook non-idempotency).** Fix pattern from F2-026 proposal: id-based equals/hashCode, ili switch to MutableList. Two entity classes, ~30 lines total.

4. **Migration-related cleanups (Faza 6 / tracking):** F2-047 (V1_69/V1_70 country.id == location.id assumption), F2-048 (V1_54+V1_60+V1_67 view recreate ordinal hardcode — tracking-only, applied), F2-049 (V1_88 dedup vs ManufacturerAliasResolver drift — comment + checklist).

5. **Trivial cleanup batch (4 findings, ~10 lines total):** F2-029 (STR removal in InquiryRepository), F2-030 (3× DISTINCT in AgencyRepository), F2-032 (LocationViewRepository declared `<_, Long>` → `<_, String>`), F2-037 (calculateTotalPaid `COALESCE(SUM, 0)`). Single low-risk commit.

6. **Pre-prod operational checklist (not code fixes):**
   - **F2-043 CRIT:** verify prod `FLYWAY_TARGET_VERSION=1.90` (or whichever last V1.xx) is set in VM2/VM3 deploy artefact. If not, **fix before first prod deploy** (one env var + V9 sanitize).
   - **F2-044 HIGH:** Mario commentary on V1_24 (5-min documentation pass clearing reviewer doubt).
   - **F2-045 MED:** staging preflight `SELECT COUNT(*) FROM inquiry WHERE phone IS NULL` before prod deploy — if ≥ 1, `UPDATE ... SET phone='' WHERE phone IS NULL` first.

### Recurring deferrals (cross-phase)

| ID | Razlog defera |
|---|---|
| F2-002 | Envers `_revisions` retention policy — Faza 6 (perf + data hygiene) |
| F2-003 | `entity_status` soft-delete pattern formalization — Faza 5 (cross-cutting modeling) |
| F2-006/F2-007/F2-008 | Cache config verification — Faza 6 ili paralelno s @Cacheable callers audit |
| F2-012 | Birthday functional index — Faza 6 |
| F2-013 | TokenEntity.user EAGER — Faza 5 (cross-cutting perf) |
| F2-015 | revokeAllUserTokens N+1 bulk update — Faza 5 |
| F2-021 | find/count divergence test coverage — Faza 5 (test sweep) |
| F2-039 | Recursive CTE cycle detection — Faza 6 (defensive coding) ili tracking-only |

### Phase gate (`./gradlew compileKotlin detekt test --continue`)

- **compileKotlin ✓** — bez warning-a iz Phase 2 izmjena (V1_90 + 22 entity polja `@Enumerated(EnumType.STRING)` + service callere + scheduled cleanup queries).
- **detekt — 291 weighted issues** — exact match to Phase 1 baseline (`project_boat4you_baselines.md`). Nijedan novi issue iz Phase 2 fix commit-a `0d1242a` / `0dc514f`. Zero regression. Sve postojeće detekt warnings su pre-existing (TooGenericException, ReturnCount, UseRequire, UnusedPrivate*, ForbiddenComment TODO-i). Pre-existing typo `class Addres11sInfoJsonb` u `AddressInfoJsonb.kt:5` postoji od initial commita; nije touched ovim review-em.
- **test — 29/103 failed** — exact match to Phase 1 baseline. Svih 29 failures su u `ReservationPaymentPhasesServiceTest`, koji je već zabilježen kao **F1-074** (test divergence from service rule A change, commit `6f11eef` prije review-a). Zero regression — Phase 2 izmjene nisu pokvarile niti jedan dodatni test.

Phase gate: **PASS at baseline (zero regression)**. Phase 2 changes su safe-to-merge tehnički; business gate je `F2-043` verification.

### Pending user actions before Phase 3 (Service / business logic)

1. **F2-043 verify** — confirm `FLYWAY_TARGET_VERSION` env var setting on VM2/VM3 (highest priority; CRIT downgrade depends on this).
2. **F2-044 commentary** — V1_24 historical context (Mario doc note rejoluje finding).
3. **Architectural batch decision** — F2-001/F2-004/F2-017/F2-028/F2-038/F2-041: yes/no on AbstractEntity hierarchy expansion + SecurityContext audit injection. Unblocks 6 findings.
4. **Payment phase fix decision** — F2-026/F2-036: id-based equals (preferred) vs MutableList migration. Trebao bi biti prije prod-a zbog F1-019 multiplier.
5. **Faza 6 index migration scoping** — bundle 5 findings (F2-023/F2-024/F2-033/F2-034/F2-040) into one pg_trgm + functional LOWER migration. Out-of-repo verify: pg_trgm extension permission on VM4 PostgreSQL 18 user.

### Phase 3 outlook (Vanjske integracije — per spec section)

Phase 3 per spec (`2026-05-07-boat4you-prod-review-design.md:96`) pokriva vanjske integracije: NauSys / MMK / Stripe / mail / HTTP klijenti.

Phase 2 nalazi koji direktno informiraju Phase 3:
- **F1-019 CRIT (Stripe webhook idempotency)** + **F1-031 LOW (Stripe signature error mapping)** + **F2-022 fix-precedent** (PostgreSQL syntax fix u scheduled cleanup) → Phase 3 produbljuje Stripe webhook flow + retry/idempotency policy across NauSys/MMK clients.
- **F1-037 HIGH (`NAUSYS_URL` default `http://`)** → Phase 3 TLS / URL validation.
- **F1-064 HIGH (public yacht search trigger-a synchroni external sync)** → Phase 3 sync orchestration + back-pressure.
- **F2-002 (Envers `_revisions` rast)** + **F2-022 (cleanup queries)** → Phase 4 scheduled jobs će ponovno doticati ovo, ne Phase 3.

Internal business-logic findings (auth/role-refetch, cancellation orchestration, admin search business rules) **NE pripadaju Phase 3** — to pokriva **Phase 5 (cross-cutting)** za error handling/logging i pojedinačni service review koji se odvija unutar svake integracijske faze.

Phase 4 (jobs + heavy native) i Phase 5 (cross-cutting) slijede nakon Phase 3.

---
