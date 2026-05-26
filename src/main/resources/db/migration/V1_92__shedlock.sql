-- V1_92: Distributed lock store for @Scheduled cron jobs.
--
-- F4-002 (HIGH prod-blocker for 2-VM deploy). Previously the deploy
-- relied on a single VM ever activating the `data-sync` Spring profile —
-- if Mario ever runs both VM2 and VM3 with `data-sync` enabled (for
-- redundancy, debug, or a misapplied env), every @Scheduled cron would
-- fire on both VMs simultaneously. For state-changing jobs (NauSys/MMK
-- catalogue sync, delete-expired, invoice generation, option expiry,
-- pre-charter reminder, payment-pending) that means duplicated partner
-- calls, duplicated emails, and duplicated DB mutations — the same
-- attack surface as F1-019 / F3-022 but on the cron axis instead of the
-- webhook axis.
--
-- ShedLock (net.javacrumbs.shedlock) provides exactly one solution:
-- whichever VM grabs the row first runs the job, others see the row
-- claimed and skip. The lock-row is keyed by job name (set by the
-- `@SchedulerLock(name = ...)` on each cron method).
--
-- Schema is the canonical ShedLock JDBC schema for Postgres — do not
-- alter column types or names, the provider matches them by literal
-- SQL. lock_until uses TIMESTAMP (NOT TIMESTAMPTZ); the provider's
-- `usingDbTime()` setting in SchedulerLockConfig means lock comparisons
-- happen against DB clock, not JVM clock, so VMs in different TZs do
-- not race on clock skew.
--
-- Retention: rows are reused — one row per distinct job name. A failed
-- job leaves its lock_until in the future for at most `lockAtMostFor`
-- duration, so a crashed VM cannot wedge the lock indefinitely.
CREATE TABLE shedlock (
    name         VARCHAR(64)  NOT NULL,
    lock_until   TIMESTAMP    NOT NULL,
    locked_at    TIMESTAMP    NOT NULL,
    locked_by    VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON shedlock TO boat4you_app;
