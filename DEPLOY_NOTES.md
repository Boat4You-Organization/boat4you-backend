# Backend deploy notes

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
- Only then set **`RECONCILE_SHADOW=false`** on cusma3 (env `boat4youscheduler_vars.env` + restart, no
  redeploy). Live deletion of stale rows begins; the natural-key reconcile then drains the 96k
  mapping-less + duplicate backlog over normal cycles, within the per-agency 30% breaker.

### Verify (post-deploy)
- Vi La Ut (yacht 4736) week 08–15.08.2026 shows bookable on the site; DB has no res for that week.
- `AvailabilityIntegrityDetectorJob` (06:40 daily) logs: contradictions → trending to ~0, mapping-less
  → trending down, duplicate partner-ids → 0. WARN if contradictions > 25.
- Reservation count snapshot before/after the shadow flip must drop only by the projected shadow count.
