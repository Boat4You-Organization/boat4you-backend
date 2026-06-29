# Backend deploy notes

## 2026-06-30 — NauSys createOption INSUFFICIENT_DATA fix for strict agencies (commit 797f9bd)

**Symptom:** customers could not place an option on yachts of *strict* NauSys agencies
(Navigare = our agency 286 / NauSys companyId 122957; Dream Yacht Charter). The boat-detail
"enter-your-details" step failed; createInfo returned OK (with a price) but `createOption`
returned `INSUFFICIENT_DATA (201)`. Reported via Nedo (yacht 4548 / NauSys 37302180).

**Root cause (proven live, not guessed):** for strict agencies NauSys `createOption` requires the
client to carry a **COMPLETE postal address**. With only name+surname the option is rejected.
Surprisingly, supplying a client **email** *also* triggers `INSUFFICIENT_DATA` (NauSys then tries a
registered-client lookup that needs more fields). Live isolation matrix on 2026-06-30:
- name+surname only → createOption INSUFFICIENT (Navigare); OK for lenient agencies.
- name+surname + **address** (no email) → createOption **OK** for Navigare AND all 4 lenient agencies tested.
- address + **email** → INSUFFICIENT again. So: address required, email must be omitted.

**Fix:** `NausysReservationIntegrationService.createOption` now builds the createInfo `RestClient`
with name + surname + the **broker agency's registered address** (Vrboran 37, 21000 Split,
countryId=1=HRV — Cusmanich d.o.o., matches the NauSys agency profile) and **no email**. We don't collect the customer's address, and the option is a hold
we place as the broker, so the broker address is correct. Constants live in a `private companion object`.

**Scope:** API node only (`createOption` runs on the booking request path = cusma2). The scheduler
(cusma3) never serves bookings, so this is functionally a no-op there — sync its jar to 797f9bd at the
next idle window for consistency (preserve the `-Dreconcile.shadow-mode=false` ExecStart flag).

**Deploy (DONE 2026-06-30 ~23:05 UTC):** built JDK21 bootJar, scp to cusma2 `webservice.jar.new`,
atomic swap (rollback backup `webservice.jar.bak.c6b88c5`), `systemctl restart boat4you`. App up in
10.5s, `/public/countries` → 200. Verified: live createOption for Nedo (37302180/122957) with the exact
deployed recipe → OPTION created (price 7743.50 EUR), test hold stornoed. Lenient agencies unaffected
(4 tested, both old and new recipe succeed).

---

## 2026-06-29 — Permanent availability-mirror reconcile fix (natural-key + shadow + V9_23 cleanup + detector)

**What:** the absent-reconcile no longer depends on `external_mapping` integrity. It now matches our
reservations to the partner's complete response by NATURAL KEY (yacht + dates + status), so stale
(cancelled-at-partner) RESERVATION/SERVICE rows are removed even when their mapping is missing (96k
legacy rows) or duplicate-mapped to another yacht (the Vi La Ut case). Ships behind a SHADOW flag.

### Deploy order (standard backend deploy)
1. **cusma2 FIRST** — applies Flyway `V9_23` (FLYWAY_TARGET_VERSION=latest live). Restart `boat4you`.
2. **cusma3 SECOND** — scheduler (Flyway-pinned 1.43 → does NOT apply V9_23). Restart scheduler.

### PRE-DEPLOY dry-run (29.6.2026 ~18:30 UTC, prod)
`V9_23` deletes self-contradictory future hard-blocks (RESERVATION/SERVICE, option_expiration NULL,
date_to>today, overlapping one of OUR FREE offers on the same yacht):
- **438 reservations across 334 yachts** will be deleted.
- **Includes Vi La Ut res 283386 (yacht 4736, 08/08→15/08)** → that boat reappears as bookable.
**VERIFY post-migration:** Flyway-deleted count ≈ 438 (`SELECT count(*)` with the same criteria → 0 after).

### POST-DEPLOY (cusma2)
- The search hard-block reads `external_reservations` LIVE (correlated NOT EXISTS), so the fix is
  effective the instant the migration commits — **no manual matview refresh required.** (The
  `yacht_search_view` 5-min refresh updates the FREE/price display in due course.)

### SHADOW → LIVE (the catastrophe firewall — do NOT skip)
- Ships `RECONCILE_SHADOW=true` (default in code: `reconcile.shadow-mode:true`). While ON,
  `reconcileAbsent` LOGS what it WOULD delete (`[SHADOW] reconcile WOULD delete ...`) and deletes
  NOTHING. The migration above still runs (it is independent), so the 888/438 customer-facing damage
  is fixed on deploy regardless.
- After **3–7 full sync cycles**, review the `[SHADOW]` log on cusma3:
  - every WOULD-delete line must be a real cancellation / known-stale row,
  - **zero** WOULD-delete lines on a row a live partner read confirms is still booked,
  - per-agency counts in the low tens, not thousands (a thousands spike = breaker should fire = key bug).
- **DONE 29.6.2026 ~20:51 UTC** — after shadow evidence (tiny per-agency fractions, 30% breaker fired
  correctly) + 2 live partner spot-checks (Vi La Ut on NauSys, Eleonora on MMK), flipped to LIVE via
  the systemd ExecStart `-D` flag (NOT an env var). Live deletion drains the 96k mapping-less +
  duplicate backlog over normal cycles, within the per-agency 30% breaker. Verified first live run.

### cusma3 systemd state (server-only ops config — NOT in git; recorded here for reproducibility)
Current live `ExecStart` in `/etc/systemd/system/boat4youscheduler.service`:
```
ExecStart=java -Xmx2048m -Dreconcile.shadow-mode=false -jar /home/cusma3/boat4you/webservice.jar
```
- `-Xmx2048m` — heap cap (from the 29.6 sync-freq deploy; was 6144m). Backup: `~/boat4youscheduler.service.bak.6144`.
- `-Dreconcile.shadow-mode=false` — reconcile in LIVE delete mode. Backup: `~/boat4youscheduler.service.bak.shadow`.
- REVERT reconcile to shadow (deletes nothing): drop the `-D` flag → `daemon-reload` → restart.

### Verify (post-deploy)
- Vi La Ut (yacht 4736) week 08–15.08.2026 shows bookable on the site; DB has no res for that week.
- `AvailabilityIntegrityDetectorJob` (06:40 daily) logs: contradictions → trending to ~0, mapping-less
  → trending down, duplicate partner-ids → 0. WARN if contradictions > 25.
- Reservation count snapshot before/after the shadow flip must drop only by the projected shadow count.
