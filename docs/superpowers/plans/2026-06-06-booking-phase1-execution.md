# Booking engine Phase 1 — execution plan (4 ordered deploys)

**Date:** 2026-06-06 · **Goal:** honest real-time-ish availability over published slots + options VISIBLE & inquiry-only + expired options FULLY auto-released. (Phase 2 = live partner quotes for arbitrary dates — separate.)

Design specs: `docs/superpowers/specs/2026-06-06-booking-phase1-design-*.md`. Adversarial review: `…-adversarial-review.md`.
**The adversarial review found 3 CRIT issues → this is NOT one deploy. Ship as 4 ordered, independently-revertible sub-deploys**, fidelity→release→lock→search. Each: build (subagent) + verify (gradle compileKotlin+ktlint / tsc+eslint) + adversarial verify + manual deploy (cusma2 first, cusma3; web on cusma1) + smoke.

Key principle the review established: **availability = matview (attributes/price + offer_status UNAVAILABLE pre-filter, fail-safe) MINUS live `external_reservations` half-open overlap.** The matview pre-filter stays (owner-weeks/regatta live only on offer.status, no reservation row — HIGH-6); the live probe only ADDS blocking + option-honesty, never un-blocks. Half-open `[)` everywhere (turnaround-safe).

---

## DEPLOY 1 — Sync fidelity (lowest risk; backend-only; NO migration; foundation)
Makes `external_reservations` trustworthy as the busy source everything else reads.
- **MMK status remap** `ExternalReservationStatus.fromMmkValue` (`enums/ExternalReservationStatus.kt:25-41`): target table `1→RESERVATION, 2→OPTION, 9→OPTION(_WAITING), 4/6/10/11→SERVICE, 0/3/5/7/8→FREE, null→UNKNOWN`. Today 3,5,6,8,9,10,11 collapse to UNKNOWN (no-op) → owner-week/regatta/sleep-aboard (6/10/11) leak as bookable. Cross-check against `OfferStatus.fromMmkValue` (6/10/11→UNAVAILABLE) so they agree. **No over-block** for 4/6/10/11 (all documented non-bookable).
- **OPTION sync branch overlap-aware** (audit A3): `NauSysAvailabilitySyncService.kt:150-167` + `MmkAvailabilitySyncService.kt:148-166` use exact `findAllByYachtAndDateFromAndDateTo` for OPTION while RESERVATION/SERVICE use half-open `findAllByYachtAndDateRangeOverlap`. Make OPTION flip overlapping FREE offers→OPTION (half-open); synthesize only when truly none. Keep the `extStatus=SYNTHETIC_OPTION` skip so syncs don't thrash.
- **MMK availability cadence** 1×→3×/day: `MmkSyncJob.kt:129-136` cron `0 10 8 * * ?` → `0 40 8,13,19 * * ?` (keep @SchedulerLock).
- **Gate:** JUnit asserting the full remap table (merge gate). Verify owner-week/regatta now block. (Ops: one-time prod `/availability` sample to confirm MMK code 3/5 semantics — HIGH-4.)

## DEPLOY 2 — Expired-option full auto-release (B1) WITH shared-offer guard (backend-only; NO migration)
Depends on Deploy 1 (sweep reads corrected `external_reservations`).
- **`releaseExpiredOption(reservationId)`** in `ReservationMutationService` mirroring `cancelReservation:379-401` BUT with the **CRIT-1 shared-offer guard**: `ReservationFlow.offer` is `@ManyToOne` (many flows → one offer). Before freeing, requery OTHER live holders on that offer (new `findByOfferIdAndSysStatusIn`): if any RESERVATION → set offer RESERVED; if any OPTION/OPTION_WAITING → keep OPTION; else → FREE. Null-guard `offer` (fictitious/skip external_id=null flows). Safe order: audit-stamp `[SYSTEM]` → (our-timer branch only) partner `cancelOption` runCatching → reservation sysStatus/status/externalStatus → offer status (guarded) → `ReservationFlow.status=ABANDONED` → matview refresh → email. Re-read + guard inside own `@Transactional` (MED-10).
- **HIGH-5:** treat overlapping `OPTION_WAITING` (partner code 9 = next option) as keeping offer OPTION, never FREE.
- Wire into BOTH `OptionExpiryService.syncExpiredOptions` branches (`:526-588`): Step1 (partner-flipped, refreshReservation) + Step2 (our 6h timer).
- **My Bookings:** exclude `sysStatus=CANCELLED` from client list (`ReservationFlowQueryingService.getMyReservations:33-39` / `ReservationViewRepository.findAllByReservationUserId:14`).
- **Proactive sweep:** `external_reservations` where `status=OPTION AND optionExpiration<now` → released; revert lingering `SYNTHETIC_OPTION`/`OPTION_WAITING` offer rows whose option lapsed → FREE (with the same shared-offer guard); then matview refresh.
- **Must NOT touch** paid RESERVATION / active / fictitious. Idempotent (query keys sysStatus=OPTION).

## DEPLOY 3 — Booking-time atomic re-check (CRIT-2) (backend-only; NO migration; hardens money path)
Ship BEFORE the honest search (which amplifies contention onto freed rows).
- In `ReservationFlowMutationService.createReservationFlow` (`:80` lock-free `offer.status!=FREE`): pessimistic lock `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), then re-assert against `external_reservations` half-open overlap (RESERVATION/SERVICE + live-OPTION) for the offer's own window, inside the same tx, before creating the flow. Closes the offer-status race + the `skipExternalSystem`/kill-switch double-book (synthesized OPTION with no partner check).
- **Gate:** two concurrent guests on one offer → exactly one succeeds.

## DEPLOY 4 — Honest search + status-gate + FE (highest blast radius; LAST; BE+FE together; 1 index)
- **Search Layer-1** (`YachtQueryingService.buildYachtSearchPredicates:807-841`): replace ±3 start-proximity with true interval — slot overlaps `[from-N,to+N]` (honest "nearby" window) + correlated `NOT EXISTS` half-open overlap excluding **only RESERVATION/SERVICE** (OPTION stays visible → inquiry-only). Same predicate in `getYachtSearchTotalCount` (MED-9: build subquery against its own `cq`).
- **Search Layer-2 / card:** carry the ACTUAL matched window (realFrom/realTo) + matchKind (EXACT/SHIFTED/SHORTER/LONGER) + true status + real price. Keep matview UNAVAILABLE pre-filter (HIGH-6 option b; ≤2-min un-block lag accepted, fail-safe).
- **CRIT-3 half-open everywhere:** convert `findOptionsByYachtIdsAndPeriod:59`, `findYachtAvailabilityByAdjustedYearAndMonth:33`, `findYachtAvailabilityByYear:21` from closed `<=/>=` to half-open `< / >` (turnaround). Verify admin /offers caller.
- **Status unification:** stop collapsing RESERVATION/SERVICE/OPTION_EXPIRED→UNAVAILABLE (`OfferMapper.kt:32`, `SimpleOfferStatus.kt:11-18`); expired options (optionExpiration<now) read FREE. FE `Status` 3→ richer; gate consistent (BoatCalendarForm + BoatMobileNavigation + AvailabilityDateSelector A5 fix [block RESERVATION+SERVICE, OPTION→inquiry] + AvailabilitySlider heatmap any-pattern, drop Sat-Sat hardcode); remove `BoatListingItemCard` DEMO scaffolding.
- **Index (HIGH-7):** benchmark a PLAIN btree `(yacht_id, date_from, date_to)` on `external_reservations` FIRST (avoids `btree_gist` superuser/boot risk); only add GiST daterange if `EXPLAIN ANALYZE` needs it. Migration resolve-by-name, ownership boat4you_owner, document CONCURRENTLY path (mirror V1_93).
- **Drop 7-day-skip** in `YachtController:158-164`. Ship FE+BE together (status wire-contract).
- **Gates:** option-whole-marina → all yachts visible + total==visible (MED-9); turnaround off-by-one (CRIT-3); EXPLAIN ANALYZE sub-second @494k; count==page.

## Residual honesty caveat (MED-8, document for owner)
Our-side reservations honest in seconds (live EXISTS). Partner-side third-party bookings honest only within MMK sync interval (~5h worst case at 3×/day) + 2-min matview. Acceptable; tighten only by raising cadence further.
