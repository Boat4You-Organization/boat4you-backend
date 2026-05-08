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
