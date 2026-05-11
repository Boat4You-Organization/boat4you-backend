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

---

## Batch 1 — Scheduled jobs (2026-05-11)

### [F4-001] HIGH availability — Spring `@Scheduled` koristi default single-thread `TaskScheduler`; long-running sync chains blokiraju ostale jobove + cron drift
**Lokacija:** `application.yml` (no `spring.task.scheduling.pool.size` config anywhere) + `domains/external/nausys/job/NausysSyncJob.kt:76-89` (runYachtSync chains yacht+offer)
**Detekcija:** statička + grep za task config
**Opis:** Spring Boot default `TaskScheduler` koristi `ThreadPoolTaskScheduler` s `poolSize = 1`. Bez override-a (`spring.task.scheduling.pool.size: N` u application.yml — verify: NIŠTA ne postoji), **sve `@Scheduled` metode dijele JEDAN thread.**

VM3 ima ~20 @Scheduled metoda (MmkSyncJob 7, NausysSyncJob 4, OptionExpiry 4, PaymentPending 2, BirthdayEmail 1, PreCharter 1, ReservationSync 1, ImageDownload 1, GenerateInvoice 1, DeleteExpired 1, ExchangeRate 1). Ako bilo koja overrun-uje svoj cron slot, **sve ostale čekaju u queue-u.**

**Concrete failure scenario:**
- 01:30 `runYachtSync` (NausysSyncJob.kt:76) starts. Chain-a `yachtSync()` + `yachtOfferSync()`. Typical wall-time za 50+ agencija s F3-001 (no timeouts) + F3-005 (no jitter) može biti 1-2h.
- 03:20 `availabilitySync` (NausysSyncJob.kt:117) supposed to fire. **Queued** behind runYachtSync.
- 06:00 `runCatalogueSync` (MmkSyncJob.kt:27) supposed to fire. **Queued.**
- 06:00 `DeleteExpiredReservationsAndOffers` supposed to fire. **Queued.**
- 06:00 `OptionExpiry.send24Hour` supposed to fire. **Queued.**
- 06:05 `OptionExpiry.send48Hour`. **Queued.**
- 06:10 `MmkYachtSync` (06:10). **Queued.**
- ...

Spring's behaviour: queued tasks fire LATE (not skipped), but if backlog grows faster than throughput, queue grows unbounded → memory growth + cron drift.

**`misfire` behaviour:** Spring `@Scheduled` doesn't have configurable misfire policy like Quartz. Default = fire-when-possible. If 06:00 cron fired at 09:30 (queued for 3.5h), it still fires — but business logic (`shouldRunScheduledSync` check) may abort jer 24h-old cache marker not yet stale.

**Plus:** `image-sync` profile (F4-004 below) — ImageDownloadJob normally runs every 2h `0 50 */2 * * ?` (50min past, every 2h). If `image-sync` profile not set, never fires → no thread contention from ImageDownloadJob; if set, it adds 12 fires per day × significant work.
**Posljedica:** scheduled jobs late or missed silently. Backup-sync pattern (F4-008 INFO positive) provides some safety, ali postoji race window. **Pre-prod blocker** ako prvi prod sync takne real load.
**Predloženi fix:**
```yaml
# application.yml
spring:
  task:
    scheduling:
      pool:
        size: 8  # adjust based on @Scheduled count + concurrency expectations
      thread-name-prefix: b4y-sched-
```

Plus razmotri:
- **Per-job @Async** za long-running tasks (NausysSyncJob.runYachtSync mogla bi @Async svoj nested call), tako da scheduler thread oslobođen brzo.
- **Per-job time limits** (Spring `@Scheduled` ne podržava timeout natively; treba ručno `CompletableFuture.orTimeout(...)` u wrapping logic-u).

**Riziko-procjena fixa:** trivijalan yml change. Concurrency increase implies potential race conditions ako jobs međusobno dijele state (treba audit-irati nakon povećanja pool size-a).
**Status:** OPEN — **HIGH, pre-prod blocker**

---

### [F4-002] HIGH availability/safety — Profile-only locking (`@Profile("data-sync")`); ako 2 VM-a slučajno imaju profil, svi jobs double-fire (no ShedLock / distributed lock)
**Lokacija:** Svi `*Job.kt` fajlovi imaju `@Profile("data-sync")` ali nigdje nema distributed lock manager
**Detekcija:** statička
**Opis:** Per spec sektion 120: "*locking VM2 vs VM3 (kako se zna da samo VM3 pokreće? ShedLock / Quartz cluster?)*". **Odgovor: nema ničega osim Spring profile gating-a.**

Trenutni model:
- VM2 (API) ima `SPRING_PROFILES_ACTIVE=prod` (verify) — `data-sync` profil NIJE aktivan → Spring ne registrira `*Job.kt` beans → cron-evi se ne pokreću.
- VM3 (sync) ima `SPRING_PROFILES_ACTIVE=prod,data-sync` (verify) → Spring registrira beans → cron-evi se pokreću.
- **Garancija "only one VM runs cron" je: deploy artefakt eksplicitno postavlja profil u env varijabli. Nije runtime check.**

**Failure scenariji:**
1. **Deploy mistake:** scaling test, A/B test, ili copy-paste env config — VM2 dobije `data-sync` profil. **Oba VM-a pokreću sve cron-eve istovremeno**:
   - 2× MMK catalogue sync u 06:00 → 2× partner API pressure
   - 2× DeleteExpiredReservationsAndOffers → 2× DB DELETE conflicting (probably FK-rejection one of them)
   - 2× birthday email cron → svaki user dobije 2 birthday emails
   - 2× PaymentPendingNotification → customers dobiju duple reminder emails
2. **Failover scenario:** primary sync VM dies, secondary takes over. Without explicit handover, double-fire window between primary-死 detection i secondary-takeover-completed.
3. **`docker-compose.yml:17`** confirms dev environment has `SPRING_PROFILES_ACTIVE=dev,data-sync`. Lokalno svaki dev pokreće sve cron-eve. Acceptable za dev, ali demonstrira fragility (jedan env var differs).

**Industry standard pattern za multi-VM safe cron:**
- **ShedLock** (Maven: `net.javacrumbs.shedlock:shedlock-spring`) — DB-based distributed lock. Decorate `@Scheduled` with `@SchedulerLock(name = "syncJob", lockAtMostFor = "PT15M")`. ShedLock writes lock row u tablicu, only one VM holds lock at a time.
- **Quartz cluster mode** — Quartz scheduler with `org.quartz.jobStore.isClustered = true`, uses DB-backed coordination.

Memory `project_boat4you_baselines.md` doesn't indicate ShedLock dependency. **Verify pom/gradle** — likely missing.

Plus: combined s F3-037 (yachtSyncsInProgress JVM-local) — both rely on JVM-singleton assumption.
**Posljedica:** silent multi-VM double-execution risk. Pre-prod nije akutni problem dok deploy is correct, ali single-env-typo-away from disaster. Plus failover scenario unsafe.
**Predloženi fix:** dvojni:
- (a) **Add ShedLock dependency + @SchedulerLock annotations:**
  ```kotlin
  @Scheduled(cron = "0 0 6 * * ?")
  @SchedulerLock(name = "mmkCatalogueSync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  fun runCatalogueSync() { ... }
  ```
  ShedLock writes lock row to `shedlock` tabela (Flyway migracija za tablicu). Single source of truth across VMs.
- (b) **Application startup assertion:** if `data-sync` profile active, write VM id + boot timestamp to `sync_vm_lease` tablicu. Other VMs check lease before activating sync beans (custom bean condition). More work, ali ne zahtjeva ShedLock dep.

(a) je standard pattern; ~1 day work + 1 Flyway migracija. Solves F3-037 (cross-VM mutex) i F3-014 (NauSys @Async one-call-at-a-time) too.

**Plus:** combined s F4-001 (single-thread executor) — solving both simultaneously gives proper foundation za scheduled work.
**Riziko-procjena fixa:** dira hot job foundation. Staging test obavezan.
**Status:** OPEN — **HIGH, pair s F3-037 + F3-014**, pre-prod blocker scenario

---

### [F4-003] MED operability — Cron clustering na xx:00 (06:00 ima 3+ jobs); DB connection contention + thread pool pressure
**Lokacija:**
- `DeleteExpiredReservationsAndOffersJob.kt:20` — `0 0 6 * * ?`
- `MmkSyncJob.kt:27` — `0 0 6 * * ?` (catalogue)
- `OptionExpiryJob.kt:20` — `0 0 * * * *` (24h reminder, fires every hour at xx:00 including 06:00)
- `OptionExpiryJob.kt:42` — `0 5 * * * *` (48h reminder, every hour at xx:05)

**Detekcija:** statička
**Opis:** Top-of-hour cluster:
- **06:00**: DeleteExpired + MMK catalogue + OptionExpiry 24h
- **07:00**: OptionExpiry 24h + MMK backup catalogue (07:00)
- **08:00**: OptionExpiry 24h + MMK backup (07:00 → expires, 11:00 → next)
- **17:00**: ExchangeRate UTC + OptionExpiry 24h

Combined s F4-001 (single-thread executor): only one fires at a time, ostali queue up. **Queue serialization** dodaje ~30 sec — 5 min latency per job depending on duration.

Plus: DB pool size **25 HikariCP** (per memory). MMK catalogue sync može držati 5-10 conn-a istovremeno (parallel queries). DeleteExpired uses 1-2 conn. OptionExpiry depends on volume — for 100 active options, ~5 conn. Cumulative pri F4-001 fix (multi-thread): possible HikariCP saturation u peak window.

**Sub-finding:** `OptionExpiryJob` ima 4 separate cron metode (`:00`, `:05`, `:25`, `*/30`). To je 4 fires every hour. Manage-able na single-thread executor.
**Posljedica:** prod nije akutni problem dok F4-001 ne fix-an + load ne raste. Once multi-thread enabled, contention plot thicker.
**Predloženi fix:** rebalance cron timings:
- DeleteExpired → 04:00 (off-peak, before sync starts at 01:30 NauSys)
- MMK catalogue → 06:00 (current) — ok jer NauSys catalogue je at 01:00, no overlap
- OptionExpiry 24h: koristiti `0 0,15,30,45 * * * *` (quarter-hourly) — manje data per fire, ali više fires. Or keep as-is.
- Plus: `application.yml` add `spring.task.scheduling.pool.size: 8` (F4-001 fix) — multi-thread eliminira queue-serialization concern.

**Riziko-procjena fixa:** dira cron timings — verify ops impact (data freshness expectations).
**Status:** OPEN — pair s F4-001 fix decision

---

### [F4-004] MED operability — `ImageDownloadJob` zahtjeva `image-sync` profile koji NIJE postavljen nigdje u tracked deploy config-ima
**Lokacija:** `domains/external/job/ImageDownloadJob.kt:8` (`@Profile("data-sync & image-sync")`)
**Detekcija:** statička + grep
**Opis:** `@Profile("data-sync & image-sync")` — Spring AND-expression requires **both** profiles active. Trenutno tracked code:
- `docker-compose.yml:17` → `SPRING_PROFILES_ACTIVE=dev,data-sync` — nema `image-sync`
- `application*.yml` ne reference image-sync (samo `image-sync-count`/`image-sync-batch` config keys, ne profile)
- Nikakav `application-image-sync.yml` ne postoji
- VM3 prod deploy artefakt (out-of-repo) — moramo verificirati

**Failure scenario:** ako prod env ne postavi `image-sync`, `ImageDownloadJob` se nikad ne pokreće → yacht images se nikad ne downloada-ju iz partner sustava → frontend pokazuje stock placeholder slike umjesto stvarnih yacht slika. **Sales impact** — customer vidi yacht bez slike → niži conversion.

Plus: combined s F2-022 fix-precedent (silent scheduled job failure), ovo je drugi "scheduled job mrtav" pattern. F2-022 had partner API syntax error; F4-004 has profile config gap.

Verify: postoji li prod env config gdje `image-sync` se zaista postavi? Mario answer: yes/no.
**Posljedica:** ovisi o partner-side image freshness. Ako MMK/NauSys image URLs su stabilni (cached locally already), niski impact. Ako se images mijenjaju (yacht renovacija) → stale slike na frontend-u.
**Predloženi fix:** dva pristupa:
- (a) **Dodati `image-sync` u VM3 deploy env** ako prod treba job: `SPRING_PROFILES_ACTIVE=prod,data-sync,image-sync`.
- (b) **Skinuti `image-sync` zahtjev iz `@Profile`** ako se job uvijek izvršava kad je data-sync aktivan. Comment u `@Profile` line 8 ne objašnjava razlog odvojenog profila.

Pretpostavka: image-sync postoji jer image downloads su heavyweight (NFS write, bandwidth) i možda se željelo opcionalno toggle-irati. Ako tako — onda (a). Verify Mario intent.
**Status:** WAITING-DECISION (verify prod env + Mario intent)

---

### [F4-005] MED resource — `NausysSyncJob.runYachtSync` chains `yachtSync()` + `yachtOfferSync()` u jedan @Scheduled method bez time-budget-a
**Lokacija:** `domains/external/nausys/job/NausysSyncJob.kt:76-89`
**Detekcija:** statička
**Opis:**
```kotlin
@Scheduled(cron = "0 30 1 * * ?")
fun runYachtSync() {
    log.info("Syncing NauSYS yachts")
    val startTimeYachts = System.currentTimeMillis()
    nauSysYachtIntegrationService.yachtSync()
    serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_SYNC)
    log.info("Syncing NauSYS yachts took ${System.currentTimeMillis() - startTimeYachts} ms")

    log.info("Syncing NauSYS offers")
    val startTimeOffer = System.currentTimeMillis()
    nauSysYachtOfferIntegrationService.yachtOfferSync()
    serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_NAUSYS_YACHT_OFFER)
    log.info("Syncing NauSYS yacht offers took ${System.currentTimeMillis() - startTimeOffer} ms")
}
```

Dva strane partner sync-a u jednoj method-i. Per F3-001 (no timeouts) + F3-005 (no jitter) + F3-002 (retry on any Exception) + F3-008 (getReservation 9× amplification):
- `yachtSync()` for 50 agencija × chunking 100 yachts = ~100-300 NauSys calls per sync. Pri normal latency ~500ms each = 50-150 sec wall-time. Pri partner spike → undefined.
- `yachtOfferSync()` for same agencies × 7 day-of-week combos × N intervals = thousands of calls. Wall-time može biti 30-60 min normal, hours during spike.

**Combined wall-time:** typical 30-90 min, peak 2-4h.

Combined s F4-001 (single-thread executor) — blocks `availabilitySync` at 03:20 i sve ostale cron-eve dok ne završi.

Plus: comment u `runCatalogueBackupSync` (line 51-54) priznaje "Backup sync in case main sync fails" — but main also blocks backup if it overruns.
**Posljedica:** drift cascade. Single failure dominates day's sync window.
**Predloženi fix:** decoupling:
- (a) **Razdvoj u 2 @Scheduled** — yachtSync @01:30, yachtOfferSync @02:00 (offset). Each ima manji blast radius.
- (b) **Per-method `@Async` wrap** — yachtSync fires async, doesn't block cron thread. Job manager (Spring TaskScheduler) free for next cron immediately.
- (c) **Add `CompletableFuture.orTimeout(60, MINUTES)`** wrap to cap wall-time. Pattern model: `MmkYachtOfferIntegrationService.yachtOfferSync` (F3-020 INFO) already does this s 15-min timeout.

(b) + (c) je clean — `@Async` wrap + timeout. Or (a) + (c) — split crons + each ima timeout.
**Riziko-procjena fixa:** dira sync hot path. Staging test obavezan.
**Status:** OPEN — pair s F4-001 + F3-001 fix

---

### [F4-006] LOW duplication — `runCatalogueSync` + `runCatalogueBackupSync` su 90%-identične kopije; drift risk
**Lokacija:**
- `MmkSyncJob.kt:28-43` + `:46-59` (catalogue + backup)
- `MmkSyncJob.kt:63-69` + `:81-97` (yacht + backup)
- `MmkSyncJob.kt:101-107` + `:110-119` (yacht lang + backup)
- `NausysSyncJob.kt:30-43` + `:55-71` (catalogue + backup)
- `NausysSyncJob.kt:77-89` + `:95-110` (yacht + backup)

**Detekcija:** statička
**Opis:** Backup sync metode su 95%-identical kod-a s glavnim sync metodama. Razlika: glavna ne pita `shouldRunScheduledSync`; backup pita. Drift risk:
- Developer doda novu step u `runCatalogueSync` (`mmkCatalogueIntegrationService.newStep()`)
- Zaboravi dodati u `runCatalogueBackupSync` → backup ne sync-a novi step → ako main fail, backup ostavlja gap
- Code review može propustiti.

Plus: 3 različita backup sync method para u svakom job-u (catalogue/yacht/lang) — 6 metoda total samo za MMK. Code duplication redundant.
**Posljedica:** maintenance burden. Pre-prod nije akutni.
**Predloženi fix:** extract helper:
```kotlin
private fun runCatalogue(reason: String) {
    log.info("Starting MMK catalogue sync ($reason)")
    mmkCatalogueIntegrationService.countriesSync()
    ...
    serviceCallCacheService.saveScheduledSync(MethodCacheEnum.SCHEDULED_MMK_CATALOGUE_SYNC)
}

@Scheduled(cron = "0 0 6 * * ?")
fun runCatalogueSync() = runCatalogue("main")

@Scheduled(cron = "0 0 7,11,16 * * ?")
fun runCatalogueBackupSync() {
    if (serviceCallCacheService.shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_MMK_CATALOGUE_SYNC)) {
        runCatalogue("backup")
    }
}
```
Same approach for yacht + lang. Eliminates duplicate code.
**Riziko-procjena fixa:** trivijalan refactor.
**Status:** WAITING-DECISION (trivijalan, low risk)

---

### [F4-007] LOW operational — `GenerateInvoiceJob` runs every 2h bez `shouldRunScheduledSync` check; missing backup-sync pattern
**Lokacija:** `domains/invoice/job/GenerateInvoiceJob.kt:20-25`
**Detekcija:** statička
**Opis:**
```kotlin
@Scheduled(cron = "0 7 0/2 ? * *")  // every 2 hours at 7 minutes past
fun runJob() {
    log.info("Running GenerateInvoiceJob")
    val count = invoiceService.generateInvoicesFromJob()
    log.info("GenerateInvoiceJob generated $count invoices")
}
```

12 fires per day. Each call `invoiceService.generateInvoicesFromJob()`. **No idempotency check** that:
- Previous fire didn't complete (overlapping run via @Async or queued behind)
- Manual trigger doesn't conflict (admin endpoint exists?)
- Service-level guards exist (verify InvoiceService impl)

Service may have its own idempotency (e.g. only generates invoices for new ReservationFlow rows since last run), but **job-level no defense.**

Plus: ne logira broj invoices generated u INFO format-u koji bi pomogao admin-u (e.g. "12 invoices since last run"). `$count` print but no aggregation.
**Posljedica:** ako InvoiceService nije idempotent, dupli invoices possible. Verify InvoiceService.
**Predloženi fix:** dodati `shouldRunScheduledSync(MethodCacheEnum.SCHEDULED_INVOICE_GEN)` check + service-level idempotency review. Trivial.
**Riziko-procjena fixa:** trivijalan.
**Status:** WAITING-DECISION (verify InvoiceService idempotency prvo)

---

### [F4-008] INFO positive — Backup-sync pattern (`shouldRunScheduledSync` cache check) + per-job cron offsets sa explicit comments = solid scheduled-design osnovna
**Lokacija:**
- `MmkSyncJob.kt:47, 82, 90, 111` — `shouldRunScheduledSync` check pattern
- `PreCharterReminderJob.kt:29` — cron offset comment "09:32 (offset from top-of-hour clusters and the 12:02/12:12 PaymentPending runs)"
- `PaymentPendingNotificationJob.kt:17-19, 27-30` — both methods imaju explicit "shifted from X to avoid Y" komentare
- `NausysSyncJob.kt:117` — "20 minutes past the hour is chosen to avoid overlapping with the catalogue sync at 3:00 AM"
- `OptionExpiryJob` — 4 metode s explicit razlog komentare

**Detekcija:** statička
**Opis:** Patterni vrijedni standardize-irati:
- **Backup-sync s cache check:** odlična resilience pattern. Ako main fail, backup pokušava. Cache marker prevents duplicate work ako main je već uspio.
- **Explicit cron offset comments:** developer koji ad-hoc mijenja cron može vidjeti zašto je trenutno offset; manje vjerojatno greška.
- **Cross-job awareness:** komentari referenciraju druge job-ove (`OptionExpiry :00, :30`, `NausysSyncJob 3:20/12:20`) — developer ima context za clustering decisions.
- **`OptionExpiryJob.send72Hour` line 27-31** — eksplicit Mario decision history zabilježen.

Vrijedno dokumentirati ovo kao **scheduled-design playbook** u repu. Faza 5/6 work.

Plus: positives ne adressiraju F4-001 (single-thread) — još uvijek prod-blocker.
**Status:** INFO

---

### Sažetak Batch 1

- **HIGH (2):** F4-001 (single-thread @Scheduled blocks ostale cron-eve), F4-002 (profile-only locking; 2-VM double-fire risk)
- **MED (3):** F4-003 (cron clustering xx:00), F4-004 (image-sync profile not set), F4-005 (runYachtSync chains yacht+offer u 1 metodu)
- **LOW (2):** F4-006 (catalogue + backup duplication), F4-007 (GenerateInvoiceJob bez backup-sync check)
- **INFO (1):** F4-008 (backup-sync pattern + cron offset comments + Mario decision history)

**Najkritičniji nalaz batch-a: F4-001 + F4-002 zajedno.** Single-thread executor + profile-only locking = production VM3 fragile under load i protiv operational mistakes. Fix sequencing:
1. `application.yml` task pool size config (F4-001 fix, ~5 min)
2. ShedLock dependency + migration + @SchedulerLock annotations (F4-002 fix, ~1 day; covers F3-037 + F3-014 too)
3. Cron rebalance (F4-003 fix, paired s F4-001 verification)

**Plus operational verify:** confirm prod `SPRING_PROFILES_ACTIVE` includes `image-sync` (F4-004) and **ONLY VM3** has `data-sync` (F4-002 risk mitigation).

**Batch 1 završen. Batch 2 next:** Heavy native — `ImageUtils` (OpenCV `Mat.release()` patterns), `CharterAgreementService` (openhtmltopdf XHTML parser + PDFBox memory), `YachtImageService`. Plus F1-021 family path traversal check za ImageDownload NFS putanju.

---
