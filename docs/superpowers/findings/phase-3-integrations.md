# Faza 3 — Vanjske integracije

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:96-109`):**
- `domains/external/nausys/*`
- `domains/external/mmk/*`
- Stripe integracija (`StripeConfig`, `StripePaymentService`, `StripeWebhookController`, `StripePaymentController`, `PublicStripePaymentController`)
- Viva integracija (uklonjen u V1_55 — **out of scope**, finding F2 spomenuto)
- Mail service (`EmailService`, `ReservationEmailService`, `InquiryEmailService`, `PaymentPendingNotificationService`, `BirthdayEmailJob`, ...)
- HTTP client usage (`RestTemplate` / `RestClient` / `WebClient` / `HttpClient`)
- Generated klijenti iz OpenAPI (`build/generated/swagger/**`)

---

## Inventory

### NauSys (16 files)

| Path | Role |
|---|---|
| `domains/external/nausys/client/NauSysClient.kt` | Base RestClient client |
| `domains/external/nausys/client/NauSysAuditedClient.kt` | Wraps with ServiceCall audit |
| `domains/external/nausys/client/NauSysRetryableClient.kt` | Retry policy wrapper |
| `domains/external/nausys/config/NauSysRestClientConfig.kt` | Spring RestClient bean config |
| `domains/external/nausys/config/NauSysAuthProvider.kt` | Auth headers / credentials |
| `domains/external/nausys/model/NauSysDate*Wrapper.kt` | Date/time wrappers (3 files) |
| `domains/external/nausys/service/NauSysCatalogueIntegrationService.kt` | Catalogue mapping |
| `domains/external/nausys/service/NauSysCatalogueSyncService.kt` | Catalogue scheduled-call orchestration |
| `domains/external/nausys/service/NauSysYachtIntegrationService.kt` | Yacht-level integration |
| `domains/external/nausys/service/NauSysYachtSyncService.kt` | Yacht sync orchestration |
| `domains/external/nausys/service/NauSysYachtOfferIntegrationService.kt` | Offer integration |
| `domains/external/nausys/service/NauSysYachtOfferIntegrationServiceAsync.kt` | Async offer integration |
| `domains/external/nausys/service/NauSysYachtOfferSyncService.kt` | Offer sync orchestration |
| `domains/external/nausys/service/NauSysAvailabilityIntegrationService.kt` | Availability integration |
| `domains/external/nausys/service/NauSysAvailabilitySyncService.kt` | Availability sync orchestration |
| `domains/external/nausys/service/NausysReservationIntegrationService.kt` | Reservation create/update/cancel on NauSys side |
| `domains/external/nausys/controller/NausysSyncController.kt` | Admin sync trigger endpoints |
| `domains/external/nausys/job/NausysSyncJob.kt` | `@Scheduled` triggers (Phase 4 deep-dive) |

### MMK (12 files)

| Path | Role |
|---|---|
| `domains/external/mmk/client/MmkClient.kt` | Base RestClient |
| `domains/external/mmk/client/MmkAuditedClient.kt` | Audit wrapper |
| `domains/external/mmk/client/MmkRetryableClient.kt` | Retry wrapper |
| `domains/external/mmk/config/MmkRestClientConfig.kt` | Spring RestClient bean config |
| `domains/external/mmk/model/MmkDateTimeWrapper.kt` | Date/time wrappers |
| `domains/external/mmk/service/MmkCatalogueIntegrationService.kt` | Catalogue mapping |
| `domains/external/mmk/service/MmkCatalogueSyncService.kt` | Catalogue scheduled-call orchestration |
| `domains/external/mmk/service/MmkYachtIntegrationService.kt` | Yacht-level integration |
| `domains/external/mmk/service/MmkYachtSyncService.kt` | Yacht sync orchestration |
| `domains/external/mmk/service/MmkYachtOfferIntegrationService.kt` | Offer integration |
| `domains/external/mmk/service/MmkYachtOfferIntegrationServiceAsync.kt` | Async offer integration |
| `domains/external/mmk/service/MmkYachtOfferSyncService.kt` | Offer sync orchestration |
| `domains/external/mmk/service/MmkAvailabilityIntegrationService.kt` | Availability integration |
| `domains/external/mmk/service/MmkAvailabilitySyncService.kt` | Availability sync orchestration |
| `domains/external/mmk/service/MmkOfferIntegrationUtils.kt` | Helper utils |
| `domains/external/mmk/service/MmkReservationIntegrationService.kt` | Reservation NauSys-side |
| `domains/external/mmk/controller/MmkSyncController.kt` | Admin sync triggers |
| `domains/external/mmk/job/MmkSyncJob.kt` | `@Scheduled` (Phase 4 deep-dive) |

### Stripe (5 files)

| Path | Role |
|---|---|
| `common/config/StripeConfig.kt` | Stripe SDK config |
| `domains/reservation/service/StripePaymentService.kt` | Checkout sessions + refunds + webhook handler |
| `domains/reservation/controllers/StripeWebhookController.kt` | Webhook endpoint |
| `domains/reservation/controllers/StripePaymentController.kt` | Secured payment endpoints |
| `domains/reservation/controllers/PublicStripePaymentController.kt` | Public payment endpoints |

### Mail / async email (suspect set — to triage in Batch)

- `domains/catalouge/services/EmailService.kt` — base mail sender
- `domains/reservation/service/ReservationEmailService.kt` — reservation flow emails
- `domains/catalouge/services/InquiryEmailService.kt` — inquiry replies
- `domains/reservation/job/PaymentPendingNotificationService.kt` — pending payment reminders
- `domains/reservation/job/PreCharterReminderJob.kt` (if exists; verify)
- `domains/users/job/BirthdayEmailJob.kt`
- Others — to discover during read pass

### Sync orchestration cross-provider

- `domains/external/service/ExternalSyncService.kt` — wraps NauSys/MMK calls
- `domains/external/service/ServiceCallCacheService.kt` — caches partner responses
- `domains/external/service/ServiceCallAuditService.kt` — audit ServiceCall row writes
- `domains/external/service/ExternalMappingService.kt` — entity ID mapping
- `domains/external/service/IntervalProvider.kt` — date range slicing for sync
- `domains/external/service/ReservationOptionsCombinationProvider.kt` — checkin/checkout day matrix
- `domains/external/service/YachtGroupingProvider.kt` — yacht batch logic
- `domains/external/service/YachtImageIntegrationService.kt` + Async + Sync — yacht image downloads (Phase 4 NFS path-traversal too)

### Out-of-scope (covered in later phases)

- All `*Job.kt` → Phase 4 (scheduled jobs deep-dive). Phase 3 will note integration-layer concerns but **not** locking/idempotency of the cron itself.
- `application.yml` config → Phase 5 (cross-cutting).
- `ImageDownloadJob`/NFS path → Phase 4.

---

## HikariCP / executor pools impact (HTTP context)

NauSys + MMK sync share VM3 with cron jobs. Tasks may use `@Async` executors or coroutines. Need to map:
- Connect/read/write timeouts on RestClient — any explicit?
- Retry backoff (max attempts, jitter)
- Circuit breaker / bulkhead (Resilience4j) — present?
- Webhook handler idempotency (F1-019 CRIT still open)
- TLS configuration (F1-037 HIGH: `NAUSYS_URL` defaults `http://`)

---

## Workflow Faze 3

Plan reading pass-a u 6 batch-eva (analogno Phase 2 patternu):

1. **Batch 1 — HTTP client foundation** (`NauSysRestClientConfig`, `MmkRestClientConfig`, `NauSysAuthProvider`, base `NauSysClient` / `MmkClient`, `*RetryableClient`, `*AuditedClient`) — timeouts, retry, secret handling, TLS, audit-injection points
2. **Batch 2 — NauSys integration services** (`NauSysCatalogueIntegrationService`, `NauSysYachtIntegrationService`, `NauSysYachtOfferIntegrationService`, `NauSysAvailabilityIntegrationService`, `NausysReservationIntegrationService`)
3. **Batch 3 — MMK integration services** (analogni MMK fajlovi)
4. **Batch 4 — Stripe** (`StripeConfig`, `StripePaymentService`, `StripeWebhookController`, `StripePayment*Controller`) — webhook idempotency (F1-019 CRIT), refund flow, public endpoint surface
5. **Batch 5 — Mail** (`EmailService`, `ReservationEmailService`, `InquiryEmailService`, `PaymentPendingNotificationService`, `BirthdayEmailJob` integration boundary) — PII u email-u, SMTP errors, async retry, bounce handling
6. **Batch 6 — Sync orchestration + admin controllers** (`ExternalSyncService`, `ServiceCallCacheService`, sync triggers in `MmkSyncController` / `NausysSyncController`) — backpressure, public→sync trigger surface (F1-064), admin admin sync auth

Za svaki batch: triage live u ovaj file kao `[F3-NNN]` nalazi.

---

## Findings

(Početak Batch 1 u sljedećem commit-u.)
