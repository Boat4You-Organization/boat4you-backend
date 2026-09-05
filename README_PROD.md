# Boat4You — Production Deployment Guide

## Infrastructure Overview

The production environment consists of four virtual machines:

| VM  | Role                   | Notes                                               |
|-----|------------------------|-----------------------------------------------------|
| VM1 | Frontend               | Uses VM2 as its backend                             |
| VM2 | Backend (API)          | Serves API requests; scheduled jobs are disabled    |
| VM3 | Backend (Scheduler)    | Same application as VM2; scheduled jobs are enabled |
| VM4 | PostgreSQL 18 database | Shared by VM2 and VM3                               |

Both VM2 and VM3 run Java 21 and connect to the database on VM4.

### Image Storage & NFS

Yacht images are stored on a disk mounted at `/mnt/data` on VM3. VM2 accesses these images over NFS:

- **VM3** — NFS server, exports `/mnt/data`
- **VM2** — NFS client, mounts the shared volume at `/mnt/shared`

---

## Application Services

The backend is deployed as a `systemd` service on both VM2 and VM3.

### VM2 — Service definition (`/etc/systemd/system/boat4you.service`)

```ini
[Unit]
Description=Boat4You Web Service
After=syslog.target network.target

[Service]
StartLimitInterval=0
Type=simple
Restart=on-failure
RestartSec=5
User=cusma2
WorkingDirectory=/home/cusma2/boat4you
ExecStart=java -Xmx4096m -jar /home/cusma2/boat4you/webservice.jar
ExecStop=/bin/kill -15 $MAINPID
SuccessExitStatus=143
EnvironmentFile=/home/cusma2/boat4you/boat4you_vars.env

[Install]
WantedBy=multi-user.target
```

Environment variables must be defined in `/home/cusma2/boat4you/boat4you_vars.env`.

### VM3 — Service definition (`/etc/systemd/system/boat4youscheduler.service`)

```ini
[Unit]
Description=Boat4You Web Service
After=syslog.target network.target

[Service]
StartLimitInterval=0
Type=simple
Restart=on-failure
RestartSec=5
User=cusma3
WorkingDirectory=/home/cusma3/boat4you
ExecStart=java -Xmx6144m -jar /home/cusma3/boat4you/webservice.jar
ExecStop=/bin/kill -15 $MAINPID
SuccessExitStatus=143
EnvironmentFile=/home/cusma3/boat4you/boat4youscheduler_vars.env

[Install]
WantedBy=multi-user.target
```

Environment variables must be defined in `/home/cusma3/boat4you/boat4youscheduler_vars.env`.

---

## Environment Variables

Configuration is provided via environment files, one per VM. These files contain all required environment variables (database connection, external API credentials, payment processor keys, etc.) and are **not** stored in version control. Values are supplied separately for each environment (VM2, VM3, and VM4).

---

## Building the Application

Build a deployable JAR from the project root (requires Java 21):

```bash
./gradlew bootJar
```

The resulting artifact will be located at:

```
build/libs/boat4you-0.0.1-SNAPSHOT.jar
```

---

## Deploying a New Version

### VM2

```bash
# 1. Stop the service
sudo systemctl stop boat4you

# 2. Upload build/libs/boat4you-0.0.1-SNAPSHOT.jar to /home/cusma2/boat4you/webservice.jar

# 3. Start the service
sudo systemctl start boat4you
```

### VM3

```bash
# 1. Stop the service
sudo systemctl stop boat4youscheduler

# 2. Upload build/libs/boat4you-0.0.1-SNAPSHOT.jar to /home/cusma3/boat4you/webservice.jar

# 3. Start the service
sudo systemctl start boat4youscheduler
```

---

## Viewing Logs

| VM   | Log directory                      |
|------|------------------------------------|
| VM2  | `/home/cusma2/boat4you/logs/`      |
| VM3  | `/home/cusma3/boat4you/logs/`      |

To follow logs in real time:

```bash
tail -f /home/cusma2/boat4you/logs/<logfile>   # VM2
tail -f /home/cusma3/boat4you/logs/<logfile>   # VM3
```

---

## Scheduled Jobs

All scheduled jobs run exclusively on **VM3** (`data-sync` profile, ShedLock JDBC `usingDbTime()`, scheduling pool of 8 threads). They handle external data synchronisation (NauSYS, MMK), invoice generation, retention and customer notifications.
**All times are UTC** (VM3 `timedatectl` = Etc/UTC; only `ExchangeRateSyncJob` sets `zone=` explicitly). Table regenerated from the `@Scheduled` / `@SchedulerLock` annotations on 2.9.2026; "measured" = journal `took N ms` lines, 7-day window.

> The "06:15 backup" that appears in code comments and notes is the **NauSys backup-sync** cron (`NausysSyncJob.runYachtBackupSync`), **not a database backup** — VM4 has no in-VM backup job (no cron/timer/pg_dump); DB snapshots are hoster-side.

### Timetable (de-conflicted 2.9.2026)

The single NauSys credential is throttled on *concurrent* calls, so NauSys jobs are strictly sequential: the nightly block **23:00 → ≈05:15** (catalogue 23:00, yachts+offers 23:20, then the first availability pass and the offer retry-queue drain are *chained* at the end of the same run), then MMK **06:00–07:50**, MMK availability **08:40 / 12:40 / 16:40 / 20:40**, NauSys availability **10:20 / 16:20 / 22:20**. `NausysSyncJob` additionally holds an in-JVM `nausysBusy` gate so a late-running night makes the next NauSys job wait/skip instead of running in parallel. Since 5.9.2026 (Mario: search is served from the DB, weekly ranges no longer warm partners live) the bookable 12 weeks are refreshed intraday by **near-term offer refreshes** — NauSys **10:40 / 16:40** (same per-agency grid capped at today+84 d, est. ≈1 h, waits ≤30 min on the gate) and MMK **10:50 / 16:50** — and the durable search-warm retry table `nausys_search_sync_retry` is drained every 15 min at :05/:20/:35/:50. The nightly `nausysYachtSync` lock is **PT10H** (the run measured 6 h 39 m on 4/5.9.2026).
**Safe restart/deploy windows for VM3:** 07:50–08:40, 13:00–16:15, ≈17:50–20:35, 21:05–22:15 UTC (10:40–≈12:00 is no longer safe: near-term refreshes).

| Job class | Method | Cron (UTC) | Lock (`lockAtMostFor`) | Schedule / measured |
|---|---|---|---|---|
| `NausysSyncJob` | `runCatalogueSync` | `0 0 23 * * ?` | `nausysCatalogueSync` PT2H | 23:00; 0.5–1 min |
| `NausysSyncJob` | `runYachtSync` | `0 20 23 * * ?` | `nausysYachtSync` PT10H (was PT7H; 6 h 39 m measured 5.9.2026) | 23:20; yachts 11–15 min + offers 300–400 min, then chained availability pass (4–6 min) + retry-queue drain → ends ≈04:45–06:15 |
| `NausysSyncJob` | `runCatalogueBackupSync` | `0 0 6,10,15 * * ?` | `nausysCatalogueBackupSync` PT1H | no-op unless catalogue marker >24 h |
| `NausysSyncJob` | `runYachtBackupSync` | `0 15 6,10,15 * * ?` | `nausysYachtBackupSync` PT2H | always drains `nausys_offer_sync_retry`; yachts/offers only if marker >24 h and no night still running |
| `NausysSyncJob` | `availabilitySync` | `0 20 10,16,22 * * *` | `nausysAvailabilitySync` PT1H | 3.8–6.2 min; 4th pass/day is chained into `runYachtSync` |
| `NausysSyncJob` | `runNearTermOfferRefresh` | `0 40 10,16 * * *` | `nausysNearTermOfferRefresh` PT3H | 10:40 / 16:40; same per-agency offer grid capped at today+84 d, waits ≤30 min for a running NauSys job (else skips), then drains `nausys_offer_sync_retry`; marker `SCHEDULED_NAUSYS_NEAR_TERM_OFFER`; est. ≈1 h — measure |
| `NausysSyncJob` | `runSearchRetryDrain` | `0 5/15 * * * *` | `nausysSearchRetryDrain` PT10M | every 15 min (:05/:20/:35/:50); ≤25 rows of `nausys_search_sync_retry` (failed non-weekly search warms from VM2); skipped while `nausysBusy` |
| `DeleteExpiredReservationsAndOffersJob` | `deleteExpiredReservationsAndOffers` | `0 30 5 * * ?` | `deleteExpiredReservationsAndOffers` PT1H | 05:30; ~1 s |
| `MmkSyncJob` | `runCatalogueSync` | `0 0 6 * * ?` | `mmkCatalogueSync` PT1H | 06:00; ~5 s |
| `MmkSyncJob` | `runYachtSync` | `0 10 6 * * ?` | `mmkYachtSync` PT1H | 06:10; 5.5–9 min |
| `MmkSyncJob` | `runYachtOfferSync` | `0 30 6 * * ?` | `mmkYachtOfferSync` PT2H | 06:30; 11–16 min |
| `MmkSyncJob` | `runCatalogueBackupSync` | `0 0 7,11,16 * * ?` | `mmkCatalogueBackupSync` PT1H | no-op unless marker >24 h |
| `MmkSyncJob` | `runYachtBackupSync` | `0 10 7,11,16 * * ?` | `mmkYachtBackupSync` PT1H | no-op unless marker >24 h |
| `MmkSyncJob` | `runYachtLangSync` | `0 20 7 * * ?` | `mmkYachtLangSync` PT1H | 07:20; 8–27 min |
| `MmkSyncJob` | `runYachtLangBackupSync` | `0 0 8,12,17 * * ?` | `mmkYachtLangBackupSync` PT1H | no-op unless marker >24 h |
| `MmkSyncJob` | `availabilitySync` | `0 40 8,12,16,20 * * ?` | `mmkAvailabilitySync` PT1H | 10–40 min (08:40 run longest, ≤09:20) |
| `MmkSyncJob` | `runNearTermOfferRefresh` | `0 50 10,16 * * *` | `mmkNearTermOfferRefresh` PT2H | 10:50 / 16:50; same per-agency sweep bounded today..today+84 d (one `/offers` call per agency × option group, flex 6, upsert-only); writes no marker; may overlap the tail of the 16:40 availability run — measure |
| `MmkStaleReverifyJob` | `runNightlyReverify` | `0 25 9 * * ?` | `mmkStaleOfferReverify` PT4H | 09:25; 6–10 min |
| `AvailabilityIntegrityDetectorJob` | `check` | `0 55 9 * * ?` | `availabilityIntegrityDetector` PT30M | 09:55; ~2 s (after all morning syncs) |
| `ConsistencyVerifierJob` | `runWeekly` | `0 0 10 * * SUN` | `consistencyVerifier` PT4H | Sunday 10:00; ~6 min |
| `GenerateInvoiceJob` | `runJob` | `0 7 0/2 ? * *` | `generateInvoice` PT1H | every 2 h at :07; seconds |
| `ExchangeRateSyncJob` | `updateExchangeRates` | `0 0 17 * * *` (`zone=UTC`) | `exchangeRateSync` PT30M | 17:00; ~15 s |
| `PaymentPendingNotificationJob` | `run1DayInAdvance` | `0 2 12 ? * *` | `paymentPendingNotification1Day` PT30M | 12:02 |
| `PaymentPendingNotificationJob` | `run3DaysInAdvance` | `0 12 12 ? * *` | `paymentPendingNotification3Days` PT30M | 12:12 |
| `OptionExpiryJob` | `send24HourOptionExpirationReminder` | `0 0 * * * *` | `optionExpirySend24h` PT30M | hourly :00 |
| `OptionExpiryJob` | `send48HourOptionExpirationReminder` | `0 5 * * * *` | `optionExpirySend48h` PT30M | hourly :05 |
| `OptionExpiryJob` | `send72HourOptionExpirationReminder` | `0 25 * * * *` | `optionExpirySend72h` PT30M | hourly :25 |
| `OptionExpiryJob` | `syncExpiredOptions` | `0 */30 * * * ?` | `optionExpirySync` PT20M | every 30 min |
| `ReservationSyncJob` | `runYachtSwapSync` | `0 15 * * * *` | `reservationYachtSwapSync` PT45M | hourly :15; 0–1 min (partner calls) |
| `BirthdayEmailJob` | `sendBirthdayWishes` | `0 0 9 * * *` | `birthdayEmail` PT30M | 09:00 |
| `PreCharterReminderJob` | `run` | `0 32 9 ? * *` | `preCharterReminder` PT45M | 09:32 |
| `TripPushJob` | `run` | `0 40 9 ? * *` | `tripPushReminders` PT45M | 09:40 |
| `TripChatAutomationJob` | `run` | `0 45 9 ? * *` | `tripChatAutomation` PT45M | 09:45 |
| `TripPhotoRetentionJob` | `run` | `0 50 9 ? * *` | `tripPhotoRetention` PT30M | 09:50 |
| `VoucherExpiryJob` | `expireOverdueVouchers` | `0 25 3 * * *` | `voucherExpiry` PT10M | 03:25 |
| `InquiryRetentionJob` | `purgeOldInquiries` | `0 30 3 * * *` | `inquiryRetentionPurge` PT30M | 03:30 |
| `RetentionReaperJob` | `runNightly` | `0 40 3 * * ?` | `retentionReaper` PT2H | 03:40; ~17 s |
| `ImageDownloadJob` | `runImageDownload` | `0 50 */2 * * ?` | `imageDownload` PT2H | every 2 h at :50; 0–2 min |
| `SearchViewRefreshJob` | `refresh` | `0 */5 * * * *` | `refreshYachtSearchView` PT8M (`lockAtLeastFor` PT30S) | every 5 min; ~45 s |
| `TripChatStreamRegistry` | `heartbeat` | `fixedDelay` | — | SSE keep-alive, not a cron |
