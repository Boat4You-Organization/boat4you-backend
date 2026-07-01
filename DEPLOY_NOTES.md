# Backend deploy notes

## 2026-07-02 — ⚠️ PENDING DEPLOY: cache-warm Hikari connection-pinning fix (commit 5c7aa53)

Root cause of the nightly/daily "Connection is not available" bursts (18–41 errors in one
second, booking flow → "technical difficulties"): `ExternalSyncService`'s class-level
read-only transaction pinned a Hikari connection while the location-path cache-warm waited
up to 5 min on nested @Async tasks that starved/dropped on the same 6-thread pool
(F1-064 handler drops leave futures that never complete). Postgres
(`idle_in_transaction_session_timeout=5min` on cusma4) killed the session each cycle
(SQLSTATE 08006). Steady state: 6/25 connections gone + warm markers never written →
same ranges re-warmed forever. Fix: no ambient transaction on the location path,
partner syncs run in-thread sequentially (bounded by HTTP timeouts), per-yacht path
keeps its bounded read-only tx. NO migration in this commit — safe to ride along with
the V9_26 deploy below (any jar built from main ≥ 5c7aa53 carries it).

**Verify after deploy (cusma2):**
- `journalctl -u boat4you --since "<deploy time>" | grep -c "Apparent connection leak"` → should stay 0
  (pre-fix: ~1700/day in 5-min lockstep on AsyncThread-*).
- psql on cusma4: `SELECT count(*) FROM pg_stat_activity WHERE client_addr='192.168.55.2' AND state='idle in transaction' AND now()-xact_start > interval '90 seconds'` → 0 across a few samples
  (pre-fix: constantly 3–6).
- "Failed to sync yacht offers … TimeoutException" should drop to ~0 (pre-fix 1.5–4k/day);
  "Connection is not available" bursts should disappear over the following day.

---

## 2026-07-02 — ⚠️ PENDING DEPLOY: V9_26 phone trunk-zero data fix (commit 64b8dd4)

`V9_26` rewrites stored `+3850…` phone numbers to `+385…` (reservation_flow 18, inquiry 2 —
customers typed national format 098… and the old PhoneInput stored the trunk zero; undialable
abroad). The FE fix (boat4you-web `ba943da`, PhoneInput strips trunk zero except IT/SM) is
ALREADY LIVE on cusma1. Deploy backend cusma2 (applies V9_26) + cusma3 when the parallel
image-spam session (commit 38a8d9d, same jar) finishes its own deploy — do NOT race two
deploys of the same service. Verify after: `SELECT count(*) FROM reservation_flow WHERE
phone LIKE '+3850%'` → 0.

---

## 2026-07-01 — First-payment deadline clamped to option expiry (commit f2c09c2, DEPLOYED)

**Bug (Mario):** payment page / emails said "pay by 08.07" while the NauSys option expired
06.07 23:59 (Zen 100183/2026). A customer paying between the two dates pays for a boat the
agency may already have re-let. Root cause: the first-phase deadline comes from the PARTNER
payment plan ("first installment within N days"), which is independent of the option window.

**Fix:** `ReservationMutationService.clampFirstPaymentDeadlineToOptionExpiry` — at reservation
creation (customer path only), the EARLIEST unpaid phase deadline is clamped to
`optionExpiresAt.toLocalDate()`. Later installments keep the partner schedule (the option
ceases to matter once the first payment confirms). Null expiry → untouched (never invent
deadlines). Same transaction → payment page, wire emails, and reminders all read the clamped
date. `V9_25` fixed pre-existing rows (live unconfirmed options, earliest unpaid phase only —
prod dry-run + actual: exactly 1 row, the Zen reservation: 08.07 → 06.07).

**Deployed 2026-07-01 ~21:52 UTC** cusma2 (V9_25 applied, verified Zen phase 88 = 2026-07-06)
+ cusma3. Rollback: `webservice.jar.bak.e2106ce` (both). Note: an already-open booking session
caches phases in sessionStorage — fresh page loads / my-bookings / emails read the DB.

---

## 2026-07-01 — Payment-method fees: card +5% / bank transfer 32 EUR (commit e2106ce, DEPLOYED)

**Policy (Mario 1.7.2026):** card payments +5% processing fee on the amount being paid
(per installment); bank transfers a fixed 32 EUR per reservation split evenly across
installments (2 phases → 16 EUR per wire, mandatory); all fees whole-euro (no cents).

**How it works:** the fee infrastructure already existed (settings `CARD_PAYMENT_SURCHARGE`
+ `BANK_TRANSFER_FIXED_FEE`, public endpoints, FE display) — card surcharge was already
applied to the Stripe charge but the setting was unset (0), and the bank fee was
display-only cosmetics. This deploy: `V9_24` seeds 5/32 (admin-editable later); Stripe
surcharge now rounded HALF_UP to whole EUR; NEW `BankTransferFeeShare` splits 32 whole-euro
across phases (earlier phases absorb remainder: 3 phases → 11/11/10); the wire "Transfer
amount" in fewMoreDetails / optionExpiryReminder / reservationPaymentPending emails now
carries the phase's share + a localized mandatory-fee notice (all 10 email locales).
**Payment phase rows keep the base charter price** — the fee is a payment-channel
surcharge applied at charge/communication time, so card payers never pay the wire fee
and vice versa; no phase mutation, no confirmed-price interaction.

**Frontend (boat4you-web 5e888c2, deployed cusma1 BUILD_ID GJ1QezPFzD74fW8uoKH22):**
UnifiedPaymentStep + PayNowModal mirror the backend math exactly (Math.round card fee;
per-installment bank share via `bankFeeShareForPhase` — was showing the full 32 on one
installment).

**Deployed 2026-07-01 ~21:35 UTC:** cusma2 (V9_24 applied, `/public/settings/*` return
5/32), cusma3 (scheduler jar for reminder emails; flags preserved), cusma1 FE swap.
Rollbacks: `webservice.jar.bak.78b8027` (both), `.next.bak-202607012138` (cusma1).

---

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
