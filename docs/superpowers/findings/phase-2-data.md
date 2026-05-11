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
