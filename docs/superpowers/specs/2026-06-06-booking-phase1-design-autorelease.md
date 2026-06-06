I have all the context required. Here is the implementation-ready spec.

---

# SPEC — EXPIRED-OPTION FULL AUTO-RELEASE (Phase 1 sub-area)

## 0. Problem confirmed in code (current behavior)

`OptionExpiryService.syncExpiredOptions` (`.../reservation/job/OptionExpiryService.kt:526-588`) has two branches, **neither of which frees the offer or closes the flow**:

- **Step 1 (partner-detected, :538-552):** calls `reservationMutationService.refreshReservation(...)` (`ReservationMutationService.kt:107-119`). That method copies `status/externalStatus/sysStatus` only — it never calls `offerMutationService.updateOfferStatus(...)`, so `offer.status` stays at its stale `OPTION`/`OPTION_WAITING`/`RESERVED` value. The yacht stays falsely held in search.
- **Step 2 (our 6h grace timer, :558-580):** sets `reservation.sysStatus = CANCELLED` + stamps `flow.cancelationRequest = "[SYSTEM] …"`, but **does not** free the offer (`offer.status` not touched), **does not** call partner `cancelOption`, **does not** close `ReservationFlow.status`.

The correct template already exists:
- `ReservationMutationService.cancelReservation` (`:379-401`) flips `sysStatus = CANCELLED` **and** `offerMutationService.updateOfferStatus(offer.id, OfferStatus.FREE)`.
- `AdminReservationController.cancelReservation` (`:368-419`) shows the safe ordering: audit-stamp (REQUIRES_NEW) → partner cancel → local CANCELLED flip → email, each in its own try/catch with drift logging.

Additional gaps confirmed:
- `OfferMutationService.updateOfferStatus` (`OfferMutationService.kt:14-27`) evicts `offersByYachtAndStatusCache` but **never refreshes the matview** → released yacht stays falsely held in `/public/yachts` for up to 2 min (the `SearchViewRefreshJob` cron, `SearchViewRefreshJob.kt:43`). An on-demand path exists: `SearchViewRefreshService.requestRefresh()` (`SearchViewRefreshService.kt:53`).
- My Bookings list has **no status filter**: `ReservationFlowQueryingService.getMyReservations` (`:33-39`) → `ReservationViewRepository.findAllByReservationUserId` (`ReservationViewRepository.kt:14`) returns CANCELLED rows; FE `my-bookings/page.tsx:36-44` keeps CANCELLED visible in "Past" for 60 days (`CANCELLED_RESERVATION_RETENTION_DAYS`).
- Partner-side `external_reservations` rows with `status=OPTION AND option_expiration<now`, and `offer` rows flipped to `OPTION` / synthesized with `ext_status='SYNTHETIC_OPTION'` (`MmkAvailabilitySyncService.kt:151-165, 234`; `NauSysAvailabilitySyncService.kt:153-165, 240`), are **never proactively released** when the option lapses — only re-evaluated when the partner availability sync next runs over that yacht.

## 1. No schema migration required

All needed columns exist: `reservation.sys_status`, `reservation.external_id` (nullable), `reservation_flow.status` (enum `ReservationFlowStatus` already has `ABANDONED(3)`, `ReservationFlowStatus.kt:9`), `reservation_flow.cancelation_request` / `cancelation_request_at`, `offer.status`, `offer.ext_status`, `external_reservations.status` / `option_expiration`. **No new entity/column/Flyway file.** Only new repository query methods + a new service method + wiring.

## 2. Backend changes (file-level)

### 2.1 NEW shared method — `ReservationMutationService.releaseExpiredOption(...)`
**File:** `.../reservation/service/ReservationMutationService.kt` (add a new method; constructor already injects `offerMutationService`, `reservationRepository`, `reservationFlowRepository`).

Add `private val searchViewRefreshService: SearchViewRefreshService` to the constructor (import `hr.workspace.boat4you.domains.catalouge.services.SearchViewRefreshService`). It is NOT `@Profile("data-sync")`-restricted, so injectable on any node.

New method (mirrors `cancelReservation` `:379-401`, with null-guards for fictitious/skip flows):

```
@Transactional
fun releaseExpiredOption(
    reservationId: Long,
    externalReservation: ReservationResponseWrapper? = null,  // present when partner gave us a wrapper
): ReservationDto {
    val reservation = reservationRepository.findById(reservationId).orElseThrow()

    // IDEMPOTENCY GUARD: only act on a still-OPTION/OPTION_WAITING row.
    // RESERVATION (paid/confirmed) and already-CANCELLED are untouchable.
    if (reservation.sysStatus != ReservationStatus.OPTION &&
        reservation.sysStatus != ReservationStatus.OPTION_WAITING
    ) {
        log.info("releaseExpiredOption skip res={} — sysStatus={} (not an open option)", reservationId, reservation.sysStatus)
        return reservationMappers.toReservationDto(reservation)
    }

    // Mirror partner status labels when we have them (Step-1 path), else stamp CANCELLED (Step-2 path).
    if (externalReservation != null) {
        reservation.status = externalReservation.status
        reservation.externalStatus = externalReservation.externalStatus
    }
    reservation.sysStatus = ReservationStatus.CANCELLED
    reservationRepository.save(reservation)

    // Free OUR offer — NULL-GUARD for fictitious/skip flows (offer may be null).
    reservation.reservationFlow?.offer?.id?.let { offerId ->
        offerMutationService.updateOfferStatus(offerId, OfferStatus.FREE)
    }

    // Close the flow so it never re-enters any "open" funnel.
    reservation.reservationFlow?.let { flow ->
        flow.status = ReservationFlowStatus.ABANDONED
        if (flow.cancelationRequest?.startsWith("[AGENT]") != true) {
            flow.cancelationRequest = "[SYSTEM] Payment not received within the option deadline."
        }
        if (flow.cancelationRequestAt == null) {
            flow.cancelationRequestAt = LocalDateTime.now()
        }
    }

    // Listing must reflect the freed yacht in seconds, not the next 2-min cron tick.
    searchViewRefreshService.requestRefresh()

    return reservationMappers.toReservationDto(reservation)
}
```

Notes:
- Email is **NOT** sent inside this method (kept transactional-clean, like `cancelReservation`). The caller (`OptionExpiryService`) sends `sendExpiredEmail` after, in a runCatching, exactly as it does today.
- The `[SYSTEM]`/`[AGENT]` guard preserves the existing FE banner contract (`ReservationHeroSection.tsx` strips the marker per the inline comment at `OptionExpiryService.kt:574-577`).
- `OfferStatus.FREE` is correct (the partner-quoted Sat-Sat slot becomes bookable again). Imports `ReservationFlowStatus` already in package? It is in `hr.workspace.boat4you.domains.reservation.enums.ReservationFlowStatus` — add the import.

### 2.2 Wire BOTH branches of `syncExpiredOptions`
**File:** `.../reservation/job/OptionExpiryService.kt:526-588`.

- **Step 1 (:542):** replace `reservationMutationService.refreshReservation(reservation.id!!, extReservation)` with:
  - if the partner says the option is gone but it became a **RESERVATION** (customer paid out-of-band / partner converted), keep current `refreshReservation` (do NOT free — it's now a real booking). Branch on `extReservation.calculatedSysStatus`:
    - `RESERVATION` → `refreshReservation(...)` (current behavior; do NOT free).
    - `CANCELLED` / anything-not-OPTION-not-RESERVATION → `reservationMutationService.releaseExpiredOption(reservation.id!!, extReservation)` then `sendExpiredEmail(reservation)`.
- **Step 2 (:565-580):** replace the in-place `reservation.sysStatus = CANCELLED; reservationRepository.save(...)` + flow stamping block with a partner-cancel-first sequence mirroring `AdminReservationController.cancelReservation`:
  1. `reservationMutationService.markCancellationInitiated(reservation.id!!)` — REQUIRES_NEW audit stamp (already exists, `ReservationMutationService.kt:355-377`); it stamps `[AGENT]` though, so **either** add an optional `system: Boolean = false` param to `markCancellationInitiated` to stamp `[SYSTEM]` instead, **or** skip it and rely on the `[SYSTEM]` stamp inside `releaseExpiredOption` (simpler — recommend the latter; the audit-before-partner guarantee matters less for the cron than for the admin path because there is no human waiting, but if drift-evidence is wanted, add the `system` param).
  2. `val cancelWrapper = runCatching { reservationIntegrationService.deleteExternalReservation(reservation.id!!) }.getOrNull()` — partner `cancelOption`. **Double-cancel tolerance:** partner may already have released it; `deleteExternalReservation` (`ReservationIntegrationService.kt:177-231`) already synthesizes a CANCELLED wrapper for `external_id == null` / skip / kill-switch, and partner cancel of an already-expired option is a no-op on their side. Wrap in runCatching so a partner error does not block the local release.
  3. `reservationMutationService.releaseExpiredOption(reservation.id!!, cancelWrapper)` — frees offer, ABANDONED flow, matview refresh.
  4. `sendExpiredEmail(reservation)` (unchanged, runCatching inside).
- `syncExpiredOptions` stays `@Transactional(readOnly = false)`; note `releaseExpiredOption` opens its own `@Transactional` (default `REQUIRED` → joins the outer tx). The `deleteExternalReservation` partner HTTP call should happen **outside** any long-held write lock — it already does (it's a separate read-only service call); fine as-is since the loop processes one reservation at a time.
- The query that feeds the loop is `findAllBySysStatusAndOptionExpiresAtBefore(ReservationStatus.OPTION, now)` (`ReservationRepository.kt:40-43`). **Add `OPTION_WAITING`** so waiting-options also release: change the call site to iterate both statuses, or add a repo method `findAllBySysStatusInAndOptionExpiresAtBefore(statuses, now)`. Recommend the new `In` method (one query). Idempotency is preserved because `releaseExpiredOption` flips to CANCELLED, so the next tick's query no longer selects the row.

### 2.3 My Bookings — exclude CANCELLED from the client list
Two viable options; **recommend the server-side query** (single source of truth, no payload waste):

**Option A (recommended) — new filtered repo method + service:**
- **File:** `.../reservation/jpa/ReservationViewRepository.kt` — add:
  ```
  @Query("""
      SELECT rv FROM ReservationView rv
      WHERE rv.reservationUserId = :userId
        AND rv.reservationSysStatus <> hr.workspace.boat4you.domains.reservation.enums.ReservationStatus.CANCELLED
  """)
  fun findActiveByReservationUserId(userId: Long): List<ReservationView>
  ```
  (Mirrors the existing `sumCommissionByCreatedAtBetween` `<> CANCELLED` pattern at `ReservationViewRepository.kt:83`.)
- **File:** `.../reservation/service/ReservationFlowQueryingService.kt:37` — change `findAllByReservationUserId(userId)` → `findActiveByReservationUserId(userId)`.

This removes the auto-expired booking from `/secured/reservations/my-reservations` entirely, so it disappears from the client account, satisfying the owner's "fully released / removed from account" requirement.

**Caveat / decision point:** This *also* hides **admin-cancelled** (`[AGENT]`) bookings, not just `[SYSTEM]` auto-expiries. Today admin-cancelled bookings are intentionally shown in "Past" for 60 days with the cancellation-reason banner (`my-bookings/page.tsx:22, 57-58`; banner via `ReservationCTA.tsx:304-349`). If the owner wants to keep admin-cancelled visible but hide only auto-expired, filter on the marker instead:
  ```
  AND NOT (rv.reservationSysStatus = ...CANCELLED
           AND rv.reservationCancelationRequest LIKE '[SYSTEM]%')
  ```
  (`reservationCancelationRequest` is exposed on the view, `ReservationView.kt:294`.) **This marker-based variant is the safer default** — it removes auto-expired options from the account while preserving the existing admin-cancel UX. Use this unless the owner explicitly says "hide all cancelled."

**Option B (FE-only, if no backend deploy desired):** in `my-bookings/page.tsx:36-44`, additionally drop rows where `status === CANCELLED` and the cancellation marker is `[SYSTEM]`. But the DTO does not currently expose `cancellationRequest` on the list model (`MyReservationsDto` has no such field — `reservation.model.ts:114` `cancellationRequest` is on the *details* model, not `ReservationShortInfo`). So Option B would require adding `cancellationRequest` to `MyReservationsDto` + mapper anyway → no simpler than Option A. **Recommend Option A marker-variant.**

### 2.4 NEW proactive sweep — release lapsed PARTNER options
**New file:** `.../reservation/job/PartnerOptionSweepService.kt` (or fold into an existing availability-sync component; a dedicated service is cleaner and testable). `@Service`, NOT `@Profile`-restricted by itself; the *job* that calls it is `@Profile("data-sync")`.

Responsibilities (idempotent, runs every ~30 min alongside `syncExpiredOptions`):

1. **Lapsed `external_reservations` OPTION rows** → treat as released.
   - **File:** `.../catalouge/jpa/ExternalReservationRepository.kt` — add:
     ```
     @Query("""
         SELECT r FROM ExternalReservation r
         WHERE r.status = :status
           AND r.optionExpiration IS NOT NULL
           AND r.optionExpiration < CURRENT_TIMESTAMP
     """)
     fun findLapsedOptions(@Param("status") status: ExternalReservationStatus): List<ExternalReservation>
     ```
   - Action per row: **flip** `r.status = ExternalReservationStatus.FREE` (preferred over delete — keeps the next availability sync idempotent and avoids fighting the sync, which will overwrite the row anyway on its next pass; FREE is a no-op terminal state in the availability `updateOffer` switch, `MmkAvailabilitySyncService.kt:184-187`). Deleting is also acceptable (`deleteExpiredReservations` precedent at `ExternalReservationRepository.kt:13-15`) but flipping is lower-risk. **Idempotency:** querying on `status=OPTION` means once flipped to FREE the row is not re-selected.

2. **`offer` rows whose backing partner option lapsed** → revert to FREE.
   - Two populations (both from `MmkAvailabilitySyncService` / `NauSysAvailabilitySyncService`):
     - **Synthetic options:** `offer.status = OPTION AND offer.ext_status = 'SYNTHETIC_OPTION'` whose `(yacht, date_from, date_to)` no longer has a LIVE option in `external_reservations` (status=OPTION, option_expiration>now). These synthetic rows have no real FREE template counterpart, so revert by **flipping `offer.status = OfferStatus.FREE`** (they were cloned from a FREE template, `MmkAvailabilitySyncService.kt:222-247`) — they then behave like a normal bookable Sat-Sat slot. (Do **not** delete: the offer sync cleanup deliberately skips `SYNTHETIC_OPTION`, `NauSysYachtOfferSyncService.kt:147`, so deletion is unmanaged churn; flipping to FREE lets the next FREE-offer sync reconcile.)
     - **In-place flipped options:** real offers flipped `OPTION` in place (`MmkAvailabilitySyncService.kt:154-156`). Revert to `OfferStatus.FREE` under the same "no live option overlaps" condition.
   - **File:** `.../catalouge/jpa/OfferRepository.kt` — add a finder for offers with `status = OFFER_OPTION` that have **no** overlapping live option. Two-step is simplest and avoids a complex correlated subquery:
     1. `findAllByStatus(OfferStatus.OPTION)` (or a date-bounded variant) to get candidate offers.
     2. For each, check `externalReservationRepository.findOptionsByYachtIdsAndPeriod(...)` (already exists, `ExternalReservationRepository.kt:62-67`, returns only live future options) — if empty for that offer's `(yacht, dateFrom..dateTo)`, flip to FREE.
   - Use `offerMutationService.updateOfferStatus(offerId, OfferStatus.FREE)` for cache-evict consistency, OR a single batch `@Modifying` UPDATE followed by **one** `searchViewRefreshService.requestRefresh()` at the end of the sweep (batch is far cheaper than per-row cache evicts over potentially many rows — recommend batch UPDATE + single matview refresh + a single `@CacheEvict allEntries` on `offersByYachtAndStatusCache`).
   - **Idempotency:** the query keys on `status=OPTION`; once flipped to FREE the offer is not re-selected.

3. After the sweep, call `searchViewRefreshService.requestRefresh()` **once**.

   **DO NOT touch:** `offer.status IN (RESERVED, UNAVAILABLE, SERVICE)` (real bookings/maintenance), and any `external_reservations` with `status IN (RESERVATION, SERVICE)`. The sweep only ever acts on `OPTION` populations.

### 2.5 NEW cron wiring
**File:** `.../reservation/job/OptionExpiryJob.kt` — add a method calling the sweep, `@Profile("data-sync")` + `@SchedulerLock`. Reuse cadence near the existing `syncExpiredOptions` (`OptionExpiryJob.kt:60-64`):
```
@Scheduled(cron = "0 15,45 * * * *")
@SchedulerLock(name = "partnerOptionSweep", lockAtMostFor = "PT20M")
fun sweepLapsedPartnerOptions() {
    partnerOptionSweepService.sweep()
}
```
Inject `partnerOptionSweepService` into `OptionExpiryJob`'s constructor.

## 3. Exact safe ordering (per reservation, Step-2 / our-timer branch)

1. `markCancellationInitiated(id, system=true)` — REQUIRES_NEW, commits independently (audit breadcrumb survives partner/local drift). *(optional; see 2.2.)*
2. `deleteExternalReservation(id)` — partner `cancelOption`, in `runCatching` (double-cancel tolerant; synthesizes CANCELLED for skip/fictitious/null-external_id).
3. `releaseExpiredOption(id, cancelWrapper)` — idempotency guard → local CANCELLED → free offer (null-guarded) → flow ABANDONED + `[SYSTEM]` stamp → `requestRefresh()`. In its own `@Transactional`; if the local commit fails after partner success, log at ERROR (drift), same contract as `AdminReservationController.kt:399-410`.
4. `sendExpiredEmail(id)` — `runCatching`, never fails the batch.

Step-1 (partner-detected) branch: if `RESERVATION` → `refreshReservation` (no free); else → `releaseExpiredOption(id, extWrapper)` + `sendExpiredEmail`.

## 4. Edge cases (all enforced by the idempotency guard + null-guards)

- **Fictitious reservation** (`external_id=null`, `offer=null`): guard frees nothing (offer null-guard), `deleteExternalReservation` synthesizes CANCELLED (`ReservationIntegrationService.kt:200-206`); flow still ABANDONED.
- **Skip-external-system agency**: same synthesized-wrapper path, no partner HTTP call.
- **Already CANCELLED / already RESERVATION**: guard returns early — `releaseExpiredOption` is safe to re-run (no double-free, no re-email since callers gate the email behind the release path).
- **Partner already released the option**: `cancelOption` is a no-op / may 404 — swallowed by `runCatching`.
- **OPTION_WAITING**: included in both the `syncExpiredOptions` query (2.2) and the guard; freed to FREE so the slot reopens.
- **Concurrent cron + partner sync** touching the same offer: `REFRESH … CONCURRENTLY` serialises (`SearchViewRefreshService.kt:26-30`); `updateOfferStatus` last-write-wins on `offer.status`; sweep keys on `OPTION` so it won't fight a row the sync already moved to RESERVED/UNAVAILABLE.
- **`[AGENT]` already stamped** (admin began a manual cancel that the timer races): the `startsWith("[AGENT]")` guard preserves the admin reason, doesn't overwrite with `[SYSTEM]`.

## 5. Every entity / field touched

- `Reservation` — `sysStatus` (→ CANCELLED), `status`, `externalStatus` (mirrored when partner wrapper present). No new field.
- `ReservationFlow` — `status` (→ `ABANDONED`), `cancelationRequest` (`[SYSTEM]` stamp), `cancelationRequestAt`. No new field.
- `Offer` — `status` (OPTION/OPTION_WAITING/SYNTHETIC → FREE); read `ext_status` to identify `SYNTHETIC_OPTION`. No new field.
- `ExternalReservation` — `status` (lapsed OPTION → FREE), read `option_expiration`. No new field.
- `yacht_search_view` (matview) — refreshed via `requestRefresh()`; no DDL.
- Enums used as-is: `ReservationStatus.{OPTION,OPTION_WAITING,CANCELLED}`, `ReservationFlowStatus.ABANDONED`, `OfferStatus.FREE`, `ExternalReservationStatus.{OPTION,FREE}`.

## 6. New code artifacts summary

| Artifact | File | Type |
|---|---|---|
| `releaseExpiredOption(...)` | `ReservationMutationService.kt` | new method + `SearchViewRefreshService` constructor dep |
| Step-1/Step-2 rewire | `OptionExpiryService.kt:526-588` | edit |
| `findAllBySysStatusInAndOptionExpiresAtBefore` | `ReservationRepository.kt` | new query method |
| `findActiveByReservationUserId` (marker-variant `<>CANCELLED` / `NOT [SYSTEM]`) | `ReservationViewRepository.kt` | new query method |
| switch list query | `ReservationFlowQueryingService.kt:37` | edit |
| `findLapsedOptions(status)` | `ExternalReservationRepository.kt` | new query method |
| offer-OPTION finder (or batch UPDATE) | `OfferRepository.kt` | new query method |
| `PartnerOptionSweepService.sweep()` | NEW `reservation/job/PartnerOptionSweepService.kt` | new service |
| `sweepLapsedPartnerOptions()` cron | `OptionExpiryJob.kt` | new scheduled method + constructor dep |

No web change required if Option A marker-variant is used (FE `my-bookings/page.tsx` 60-day retention logic still applies to `[AGENT]` cancels, which remain in the payload; `[SYSTEM]` auto-expiries are gone server-side). If the owner instead wants the FE to also stop special-casing them, that is optional cleanup, not load-bearing.

## 7. Verify approach (this project's tools)

**Backend (cwd = `boat4you-backend/boat4you-ws-main`):**
- `./gradlew compileKotlin` — type-check the new method/queries/service.
- `./gradlew runKtlintCheckOverMainSourceSet` (or `ktlintCheck`) — lint; note the repo's lint-hook policy (no `--no-verify`; fix to 0). `runKtlintFormatOverMainSourceSet` to auto-fix.
- Smoke (local stack `:8443`, login `that@workspace.hr / 123456` per memory): create an option booking via the customer flow, set `option_expires_at` to `now()-7h` in `boat4you_owner` psql, manually trigger `syncExpiredOptions` (or wait a 30-min tick on the scheduler node), then assert via psql:
  - `reservation.sys_status = 'CANCELLED'`, `reservation_flow.status = 'ABANDONED'`, `cancelation_request LIKE '[SYSTEM]%'`.
  - the backing `offer.status = 'FREE'`.
  - `GET /secured/reservations/my-reservations` (Bearer) no longer returns the row.
  - `GET /public/yachts?did=…&dateFrom=…` shows the yacht bookable within one refresh window (`journalctl -u boat4you / boat4youscheduler` for `on-demand refresh: yacht_search_view in N ms`).
- Sweep smoke: insert an `external_reservations` row `status='OPTION', option_expiration = now()-1h`; run `sweep()`; assert it flips to `FREE` and the matching `offer` (incl. a `SYNTHETIC_OPTION`) is `FREE`. Re-run to confirm idempotency (no further changes, no errors).
- `journalctl` to confirm drift/ERROR logs only fire on genuine partner-success/local-fail.

**Web (only if FE cleanup chosen) (cwd = `boat4you-web/boat4you-web-main`):**
- `yarn tsc --noEmit` and `yarn lint`.

## 8. Deploy (per project convention)
Backend jar → cusma2 (`boat4you.service`) + cusma3 (`boat4youscheduler.service`, where `@Profile("data-sync")` jobs run); Flyway runs on startup but **no migration here**. Manual deploy (not git-triggered). Web build-on-server cusma1 only if FE touched. Both are separate manual steps.