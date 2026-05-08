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

(Read pass starts here.)
