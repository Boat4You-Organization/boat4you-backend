# Faza 4 — Scheduled jobs + heavy native

**Status:** in progress (inventory + read pass)
**Datum starta:** 2026-05-11
**Scope per spec (`2026-05-07-boat4you-prod-review-design.md:111-122`):**
- Svi `*Job.kt`: NausysSyncJob, MmkSyncJob, ImageDownloadJob, GenerateInvoiceJob, ExchangeRateSyncJob, PaymentPendingNotificationJob, OptionExpiryJob, DeleteExpiredReservationsAndOffersJob, ReservationSyncJob, PreCharterReminderJob, BirthdayEmailJob
- Svaka `@Scheduled` metoda
- OpenCV korištenja (`ImageUtils`)
- openhtmltopdf + PDFBox (`CharterAgreementService`)
- ImageDownload na NFS putanju

---

## Inventory

### Scheduled jobs (11 files, 622 lines)

| Path | Lines | Domena |
|---|---|---|
| `domains/catalouge/job/DeleteExpiredReservationsAndOffersJob.kt` | 25 | Cleanup (F2-022 fix here) |
| `domains/catalouge/job/ExchangeRateSyncJob.kt` | 26 | Exchange rate refresh |
| `domains/external/job/ImageDownloadJob.kt` | 20 | NFS image download (spec mentioned) |
| `domains/external/mmk/job/MmkSyncJob.kt` | 128 | MMK catalogue/yacht/offer sync |
| `domains/external/nausys/job/NausysSyncJob.kt` | 130 | NauSys catalogue/yacht/offer sync |
| `domains/invoice/job/GenerateInvoiceJob.kt` | 26 | Invoice PDF generation cron |
| `domains/reservation/job/OptionExpiryJob.kt` | 60 | Auto-expire option reservations |
| `domains/reservation/job/PaymentPendingNotificationJob.kt` | 37 | Payment reminder emails |
| `domains/reservation/job/PreCharterReminderJob.kt` | 47 | "Tomorrow you sail" reminder |
| `domains/reservation/job/ReservationSyncJob.kt` | 30 | Yacht-swap detection sync |
| `domains/users/job/BirthdayEmailJob.kt` | 93 | (already reviewed Phase 3 F3-034 INFO) |

Plus `domains/external/sync/jpa/SyncJob.kt` — entity, not Spring scheduled component (excluded from job scope).

### Heavy native code (3 files, 478 lines)

| Path | Lines | Native |
|---|---|---|
| `common/services/ImageUtils.kt` | 153 | OpenCV (org.opencv) |
| `domains/catalouge/services/CharterAgreementService.kt` | 282 | openhtmltopdf + PDFBox (PDF gen) |
| `domains/catalouge/services/YachtImageService.kt` | 43 | image processing helper |

### Cron schedule snapshot (from README_PROD.md, Phase 2 cross-ref)

| Job | Cron |
|---|---|
| MmkSyncJob (multiple methods) | Daily 06:30, 07:20, 08:10 |
| DeleteExpiredReservationsAndOffersJob | Daily 06:00 (F2-022 fix verified) |
| GenerateInvoiceJob | Every 2h at 7min past |
| ExchangeRateSyncJob | Daily 17:00 UTC |
| BirthdayEmailJob | Daily 09:00 (Phase 3 F3-034) |

---

## Spec questions to answer

Per design spec section 111-122:
- **Locking VM2 vs VM3 — kako se zna da samo VM3 pokreće?** (ShedLock / Quartz cluster / `@Profile("data-sync")` only?)
- **Idempotencija job re-runa** — što ako se ručno trigger-a već-pokrenuti job?
- **Overlapping run protection** — što ako prethodna cron iteracija nije završila do sljedeće?
- **OpenCV native memory** — `Mat.release()`, `use{}` patterns u ImageUtils?
- **PDF gen OOM i XXE** — openhtmltopdf XHTML parser settings, PDFBox memory limits?
- **ImageDownload path traversal** — F1-021 family check
- **NFS timeout** — image storage path latency
- **Dugotrajne transakcije** — `@Transactional` na cron metodama
- **Batch vs single-message email petlje** — per F3-031 (no outbox)

---

## Workflow Faze 4

Plan reading pass-a u 2 batch-a:

1. **Batch 1 — Scheduled jobs** (all 11 *Job.kt files) — profile gating, ShedLock/locking pattern, cron timings overlap, idempotency, transaction scope, error handling
2. **Batch 2 — Heavy native** (ImageUtils OpenCV + CharterAgreementService PDF + YachtImageService) — native memory cleanup, OOM bounds, XXE settings, ImageDownload path traversal

Za svaki batch: triage live u ovaj file kao `[F4-NNN]` nalazi.

---

## Findings

(Početak Batch 1 u sljedećem commit-u.)
