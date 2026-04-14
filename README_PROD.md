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

All scheduled jobs run exclusively on **VM3**. They handle external data synchronisation (NauSYS, MMK), invoice generation, and customer email notifications.
All values are in the server timezone.

| Job class                               | Method                               | Cron expression      | Schedule                                                 |
|-----------------------------------------|--------------------------------------|----------------------|----------------------------------------------------------|
| `NausysSyncJob`                         | `runCatalogueSync`                   | `0 0 1 * * ?`        | Every day at 01:00                                       |
| `NausysSyncJob`                         | `runYachtSync`                       | `0 30 1 * * ?`       | Every day at 01:30                                       |
| `NausysSyncJob`                         | `availabilitySync`                   | `0 20 3,12 * * *`    | Every day at 03:20 and 12:20                             |
| `MmkSyncJob`                            | `runCatalogueSync`                   | `0 0 6 * * ?`        | Every day at 06:00                                       |
| `MmkSyncJob`                            | `runYachtSync`                       | `0 10 6 * * ?`       | Every day at 06:10                                       |
| `MmkSyncJob`                            | `runYachtOfferSync`                  | `0 30 6 * * ?`       | Every day at 06:30                                       |
| `MmkSyncJob`                            | `runYachtLangSync`                   | `0 20 7 * * ?`       | Every day at 07:20                                       |
| `MmkSyncJob`                            | `availabilitySync`                   | `0 10 8 * * ?`       | Every day at 08:10                                       |
| `DeleteExpiredReservationsAndOffersJob` | `deleteExpiredReservationsAndOffers` | `0 0 6 * * ?`        | Every day at 06:00                                       |
| `GenerateInvoiceJob`                    | `runJob`                             | `0 7 0/2 ? * *`      | Every 2 hours at 7 minutes past (00:07, 02:07, 04:07, …) |
| `ExchangeRateSyncJob`                   | `updateExchangeRates`                | `0 0 17 * * *` (UTC) | Every day at 17:00 UTC                                   |
| `PaymentPendingNotificationJob`         | `run1DayInAdvance`                   | `0 0 12 ? * *`       | Every day at 12:00                                       |
| `PaymentPendingNotificationJob`         | `run3DaysInAdvance`                  | `0 10 12 ? * *`      | Every day at 12:10                                       |
| `OptionExpiryJob`                       | `send24HourOptionExpirationReminder` | `0 0 * * * *`        | Every hour (on the hour)                                 |
| `OptionExpiryJob`                       | `send48HourOptionExpirationReminder` | `0 5 * * * *`        | Every hour at 5 minutes past                             |
| `OptionExpiryJob`                       | `syncExpiredOptions`                 | `0 */30 * * * ?`     | Every 30 minutes                                         |
| `ImageDownloadJob`                      | `runImageDownload`                   | `0 50 */2 * * ?`     | Every 2 hours at 50 minutes past                         |