I have all the ground truth needed. The booking path's only protection against double-booking is the partner's `createOption` call (which may reject the second concurrent guest), but our DB write of the flow happens regardless and the offer.status check at line 80 is a lock-free TOCTOU read. Now I'll synthesize the adversarial review.

---

# ADVERSARIAL DESIGN REVIEW — Phase-1 combined design (search-honesty + status-gate + auto-release + sync-fidelity)

Verified against live code. Every line citation below was confirmed by reading the actual files.

## PRIORITIZED REQUIRED-BEFORE-IMPLEMENTATION RISKS

---

### CRIT-1 — Auto-release frees an offer that a SECOND live reservation still holds (shared-offer free)
**Amends: auto-release sub-design (§2.1 `releaseExpiredOption`)**

**Problem (confirmed):** `ReservationFlow.offer` is `@ManyToOne` (`ReservationFlow.kt:53-56`, `@JoinColumn(name="offer_id")`) — **many reservation flows can point to one offer row**. The booking path has no lock (see CRIT-2), so two guests can both option the same offer; and a partner `OPTION` + an `OPTION_WAITING` (status 9) legitimately co-exist on one slot. The proposed `releaseExpiredOption` copies `cancelReservation` verbatim — `offerMutationService.updateOfferStatus(offer.id, OfferStatus.FREE)` (`ReservationMutationService.kt:391`) — flipping the shared offer to FREE **unconditionally**. When option #1 expires, the offer is freed even though reservation #2 (or a just-confirmed RESERVED booking on the same offer) still holds it. The existing `cancelReservation` has the identical latent bug, but auto-release runs **unattended every 30 min** and so will trigger it at scale.

**Concrete fix:** Before freeing, re-derive the offer's true status from all *other* live reservations/flows on the same offer. In `releaseExpiredOption`, after flipping the expiring reservation to CANCELLED, query the remaining holders:
```kotlin
// Only free the offer if NO other active hold remains on it.
val otherActive = reservationRepository.findByOfferIdAndSysStatusIn(
    offer.id, listOf(OPTION, OPTION_WAITING, RESERVATION))
    .filter { it.id != reservation.id }
when {
    otherActive.any { it.sysStatus == RESERVATION } -> updateOfferStatus(offer.id, RESERVED)
    otherActive.any { it.sysStatus == OPTION || it.sysStatus == OPTION_WAITING } -> updateOfferStatus(offer.id, OfferStatus.OPTION)
    else -> updateOfferStatus(offer.id, OfferStatus.FREE)
}
```
(Needs a new `findByOfferIdAndSysStatusIn` repo method — there is currently NO reservation-by-offer finder.) This is REQUIRED before shipping auto-release.

---

### CRIT-2 — Booking-time TOCTOU: no lock, no atomic re-check; the honest search makes the race MORE likely
**Amends: search-honesty sub-design (must add a Phase-1 booking-time guard) + status-gate**

**Problem (confirmed):** The only booking-time availability gate is `ReservationFlowMutationService.createReservationFlow:80` — `if (offer.status != OfferStatus.FREE) throw`. This is a **lock-free read of a snapshot**. The flow is: (1) read `offer.status==FREE` (no `SELECT … FOR UPDATE`), (2) `createExternalReservation` → partner `createOption` (slow HTTP, `ReservationIntegrationService.kt:40+`), (3) `createReservation` flips offer to OPTION (`ReservationMutationService.kt:224`). Between (1) and (3) the row is never locked, and `createExternalReservation`/Stripe `promoteReservationToBooking` (`StripePaymentService.kt:376-379`) never re-validate offer freshness. Two concurrent customers both pass (1); the partner *may* reject the second's `createOption` (→ `IllegalStateException` at `PublicReservationController.kt:63`), so today the partner is the de-facto lock — but for `skipExternalSystem` agencies and the GLOBAL kill-switch (`ReservationIntegrationService.kt:48-62`) a **synthesized OPTION wrapper** is returned with NO partner check, so both bookings succeed against the same offer.

**Why the new search makes it worse:** Phase-1 search becomes *more accurate* and surfaces optioned-but-bookable yachts as inquiry-only and frees them faster (auto-release + 3×/day sync). That concentrates real booking attempts onto genuinely-free slots and shortens the staleness window, so more users converge on the same just-freed offer simultaneously → higher contention on the exact rows with no lock.

**Concrete fix (REQUIRED in Phase 1, minimal):** Add a pessimistic lock + re-check at booking commit. In `createReservationFlow`, replace `offerRepository.findById(offerId)` with a locking read:
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM Offer o WHERE o.id = :id")
fun findByIdForUpdate(id: Long): Offer?
```
and additionally re-assert against `external_reservations` overlap (RESERVATION/SERVICE/live-OPTION) for the offer's own window *inside the same transaction* before creating the flow — reusing the same half-open EXISTS the search uses. This closes both the offer-status race and the skip-agency double-book. Do NOT ship the honesty search without this guard, or you advertise honesty the booking path cannot keep.

---

### CRIT-3 — Half-open interval is NOT applied consistently; the option-honesty gate and detail availability use CLOSED intervals (off-by-one over-blocking on turnaround)
**Amends: search-honesty (Layer 2 probe), status-gate (§1.6 live-option enrichment), auto-release (lapsed-option finder)**

**Problem (confirmed):** The design claims half-open `[)` everywhere, but the gates it reuses are **closed `[ ]`**:
- `findOptionsByYachtIdsAndPeriod` (`ExternalReservationRepository.kt:59`): `r.dateFrom <= :endDate AND r.dateTo >= :startDate` — **inclusive both ends**. The status-gate spec §1.6 and the search Layer-2 status pass both reuse this for OPTION matching. An option that *ends* on the morning the searched charter *begins* (turnaround) will be falsely matched → a free, bookable yacht is badged "under option, inquire-only" and the reserve button is suppressed. That is the inverse of the owner's honesty goal — it hides reservable inventory behind inquiry.
- `findYachtAvailabilityByAdjustedYearAndMonth` (`:33`) and `findYachtAvailabilityByYear` (`:21`) — also closed; the detail calendar inherits the same off-by-one.
- The search Layer-1 RESERVATION/SERVICE EXISTS and `findAllByYachtAndDateRangeOverlap` (`OfferRepository.kt:102`) ARE correctly half-open (`< :dateTo AND > :dateFrom`), matching `V9_11`. So within ONE design the blocking predicate is `[)` but the option/inquiry predicate is `[ ]` → a slot can be simultaneously "not hard-blocked" (correct) and "under option" (wrong) on a turnaround boundary.

**Concrete fix:** Convert the OPTION/availability matching to half-open. Change `findOptionsByYachtIdsAndPeriod` and the two `findYachtAvailability*` queries to `r.dateFrom < :endDate AND r.dateTo > :startDate`. NOTE this query is also consumed by the existing admin /offers badge and `getYachts:334` — verify those callers tolerate the boundary change (they should; turnaround was always a non-conflict). REQUIRED so the inquiry gate and the block gate agree on turnaround.

---

### HIGH-4 — Sync-fidelity remap is correct, but MMK code `3` (option-expired) → FREE diverges from `ReservationStatus.fromMmkValue` which maps `3 → CANCELLED`, and the spec asserts the wrong "current is broken" framing for codes 1/2
**Amends: sync-fidelity sub-design (Change 2)**

**Problem (confirmed):** The proposed `ExternalReservationStatus.fromMmkValue` remap is *directionally right* — codes 6/10/11 (owner-week/regatta/sleep-aboard) currently fall to `UNKNOWN` (`ExternalReservationStatus.kt:32,36,37`) which `updateOffer` treats as no-op (`MmkAvailabilitySyncService.kt:184-187`), so those genuinely-blocking weeks leak as bookable. Mapping them to `SERVICE` is correct and matches `OfferStatus.fromMmkValue` (6/10/11→`UNAVAILABLE`, `OfferStatus.kt:53,57,58`). **No over-blocking risk** for the codes the spec maps to SERVICE — all four (4/6/10/11) are documented non-bookable, verified against the canonical table in `OfferStatus.kt:32-43`.

BUT two real issues:
1. **The spec's framing that current 1/2 are "WRONG" is false** — current already maps `1→RESERVATION, 2→OPTION` (`ExternalReservationStatus.kt:27-28`), identical to the proposal. Only 3/5/6/8/9/10/11 change. This is cosmetic but means the "stop blocking-code leakage" claim only actually fixes 6/10/11 (and reclassifies 9→OPTION). Don't over-state the blast radius in the changelog.
2. **MMK code `3` (option-expired): proposed `→FREE`, but `ReservationStatus.fromMmkValue:3→CANCELLED` and the availability sync spec for `/availability` says canceled reservations ARE returned.** FREE is the honest end-state (no-op in `updateOffer`), so functionally safe. But confirm with a prod sample that `/availability` for MMK actually emits 3/5 rows; if it emits a still-current reservation as 3 transiently during expiry, FREE is correct. Low risk, but **verify against one real agency's `/availability` payload before deploy** — the spec itself flagged the `shortAvailability` vs `/availability` code-set discrepancy and that ambiguity is unresolved in the spec.

**Concrete fix:** Ship the remap as specified (it is safe and correct), but (a) correct the changelog to "fixes 6/10/11 leakage + reclassifies 9→OPTION, 3/5/7/8→FREE"; (b) add the JUnit asserting `4/6/10/11→SERVICE, 1→RESERVATION, 2/9→OPTION, 0/3/5/7/8→FREE, null→UNKNOWN` (the spec proposes this — make it a merge gate); (c) one-time prod `/availability` sample to confirm code 3/5 semantics.

---

### HIGH-5 — Auto-release `OPTION_WAITING` handling can free a slot that the waiting-option is about to take; and the expiring-query status-field mismatch
**Amends: auto-release sub-design (§2.2)**

**Problem (confirmed):** Two distinct field semantics:
- `findAllBySysStatusAndOptionExpiresAtBefore` filters on **`sysStatus`** (`ReservationStatus`, `ReservationRepository.kt:40-43`).
- `findExpiringReservations` filters on **`status`** (`OfferStatus`, `:19,25`).
The spec proposes adding `OPTION_WAITING` to the expired-options query. That's coherent for `sysStatus` (ReservationStatus has `OPTION_WAITING(4)`). But MMK `OPTION_WAITING` (status 9) means "second option that becomes active when the first expires" (`OfferStatus.kt:41`). If the FIRST option expires and auto-release frees the offer to FREE, but a partner `OPTION_WAITING` row exists, the slot is NOT actually free — it's about to be promoted by the partner. Freeing it to FREE would let a customer reserve a slot the partner has earmarked. This compounds CRIT-1.

**Concrete fix:** When releasing an OPTION, the CRIT-1 "other active holds" check MUST treat any overlapping `OPTION_WAITING` (in `external_reservations` and in our reservations) as keeping the offer at `OPTION`, not FREE. Add `OPTION_WAITING` to the requery in CRIT-1's fix (already included above). REQUIRED to ship auto-release of OPTION_WAITING safely.

---

### HIGH-6 — Matview per-day price divisor + custom-branch hardcoded `'FREE'` will misreport status once the search trusts live probe
**Amends: search-honesty sub-design (decision a/b)**

**Problem (confirmed):** `R__1_03:130` hardcodes `'FREE' AS offer_status` for the CUSTOM branch and `:81` carries `o.status` for EXTERNAL. The search-honesty design "deletes the matview `offer_status` as source of truth" and resolves status from the live probe. But the design *keeps* the matview `OfferStatus.UNAVAILABLE` coarse WHERE-pre-filter (spec decision b, "stays via the WHERE predicate"). **There is no WHERE filter on `offer_status` in `R__1_03`** — the matview includes ALL offer statuses; the UNAVAILABLE filter lives in `buildYachtSearchPredicates`, not the view. The design references "`:670-674` UNAVAILABLE filter" but the per-day price (`:49` `client_price / (date_to - date_from)`) and `number_of_days` (`:52`) are computed per matview row, and once you stop using matview status, a row that is matview-`UNAVAILABLE` but live-`FREE` (sync lag in the OTHER direction — offer flipped UNAVAILABLE by a now-cancelled reservation) would be pre-filtered out and never reappear until the 2-min refresh. So the design's claim "Layer-1 EXISTS catches anything the matview missed" is only true in the block direction, NOT the un-block direction.

**Concrete fix:** Decide explicitly: either (a) drop the matview UNAVAILABLE pre-filter and rely 100% on the live EXISTS (costs: every UNAVAILABLE owner-week/regatta with no `external_reservations` row — e.g. MMK code-6 that maps to offer UNAVAILABLE but no reservation row — would re-surface as FREE; this is a real false-FREE regression), or (b) keep the matview pre-filter and accept ≤2-min un-block lag. Given owner-weeks/regatta exist only on `offer.status` (no reservation row), **(b) is required** — but then document that a just-cancelled reservation's yacht stays hidden up to 2 min (acceptable, fail-safe direction). The spec must pick (b) and stop claiming the live probe fully supersedes matview status.

---

### HIGH-7 — `btree_gist` GiST index migration: ownership + CONCURRENTLY + Flyway-in-transaction
**Amends: search-honesty sub-design (V1_98)**

**Problem (confirmed):** `V1_93:31-34` documents that `CREATE EXTENSION pg_trgm` may need a superuser one-off and that `CREATE INDEX` without `CONCURRENTLY` takes a table lock at Flyway startup (acceptable because pre-traffic). The proposed `V1_98` does `CREATE EXTENSION btree_gist` + a GiST index on `external_reservations`. Risks: (1) `btree_gist` extension creation needs superuser — the app role is `boat4you_owner`/`boat4you_app`; if not pre-installed this migration **fails the whole boot** (Flyway aborts). (2) The spec's `V1_98` also does `GRANT SELECT … TO boat4you_app` but the matview/grant pattern shows the app already reads — confirm the actual runtime role. (3) GiST `CREATE INDEX` non-CONCURRENTLY locks `external_reservations` writes during the partner availability sync if a sync overlaps boot — small table, short lock, but document the manual `CREATE INDEX CONCURRENTLY` path exactly as `V1_93` did.

**Concrete fix:** Pre-install `btree_gist` via DBA one-off (mirror the `pg_trgm` recipe in memory's cusma4 sudo-postgres note) BEFORE the deploy that carries `V1_98`; make `CREATE EXTENSION IF NOT EXISTS` defensive but do NOT rely on it succeeding under the app role. **Question the index's necessity at all:** `external_reservations` is indexed only on `yacht_id` today (`V1_03:1438`); the EXISTS probe is correlated per-yacht on an already-narrowed candidate set, so a plain composite btree `(yacht_id, date_from, date_to)` may satisfy the half-open range scan without the GiST/extension dependency. Benchmark a plain btree first; only add GiST if `EXPLAIN ANALYZE` shows it's needed. This removes the superuser-extension boot-risk from the critical path.

---

### MED-8 — Staleness math: combined design is "honest as of last write," NOT real-time; quantify the residual window
**Amends: cross-cutting (search-honesty decision d + sync-fidelity Change 1)**

**Problem (confirmed):** Stacked latencies: MMK availability 1×→3× /day (`MmkSyncJob` availabilitySync, currently `0 10 8`; NauSys already 3×/day) + matview 2-min cron (`SearchViewRefreshJob:43`) + search-sync `shouldCallYachtSearch` cache + auto-release 30-min cron (`OptionExpiryJob:syncExpiredOptions`). For a yacht booked **directly by another customer through a partner's own channel** (not via us), the busy state only lands when MMK next syncs — up to **~8 hours** between the 3 daily passes. The design's "honest as of last sync" is accurate but the auto-release + live-EXISTS only help for *our* reservations; *partner-side* bookings still lag by the sync interval. For OUR bookings, Layer-1 live EXISTS makes them honest immediately. The 30-min auto-release means an expired option can show "under option, inquire" for up to 30 min after expiry (acceptable — fail-safe toward inquiry, not false-bookable).

**Concrete fix:** No code change required, but the design must **state the residual window explicitly**: our-side reservations honest in seconds (live EXISTS); partner-side third-party bookings honest within the MMK sync interval (~5h worst case at 3×/day) + 2-min matview. This is a material honesty caveat the owner must accept. If tighter is needed, MMK availability cadence must go higher than 3×/day (the spec's chosen `0 40 8,13,19`), throttled by the existing cache. Recommend documenting, not over-engineering.

---

### MED-9 — Options-becoming-invisible: the design is correct in the search predicate, but the FE filter and `getYachtSearchTotalCount` consistency must be verified
**Amends: search-honesty (Layer 1) + status-gate (FE)**

**Problem (confirmed):** The owner's hard rule (agency options whole marina → yachts stay visible, inquiry-only). The Layer-1 predicate correctly excludes only `RESERVATION, SERVICE` from the EXISTS (`cb.or(isCustomYacht, cb.not(cb.exists(subAvail)))`), leaving OPTION rows untouched — **this is correct**, options stay in the result set. Confirmed against the existing `getYachts:363-365` which already keeps optioned yachts visible (`isOption` badge, not a filter). The risk is downstream: `getYachtSearchTotalCount` must share the identical predicate (it does, via `buildYachtSearchPredicates`) so the count matches the visible page — **but the new RESERVATION/SERVICE EXISTS subquery references `cq.subquery` on the COUNT query's `CriteriaQuery`; confirm the count path constructs the subquery against its own `cq`, not the page query's** (a copy-paste of `subAvail` bound to the wrong `cq` silently returns wrong counts or throws). Also verify no FE code path filters `offerStatus !== FREE` — `BoatListingItemCard` must render OPTION as a badge, never drop the card.

**Concrete fix:** Make Layer-1's subquery a helper that takes the `CriteriaQuery` as a param so both `getYachts` and `getYachtSearchTotalCount` build it against the correct query root. Add a smoke assertion: option an entire test marina, assert page returns N yachts all `offerStatus=OPTION` and `total==N`. REQUIRED as a test gate, not a code change.

---

### MED-10 — `releaseExpiredOption` cannot touch PAID/RESERVATION/fictitious — guard is sufficient IF ordered correctly
**Amends: auto-release sub-design (§2.1 idempotency guard) — confirmed adequate with one addition**

**Problem (confirmed):** The idempotency guard (`if sysStatus !in (OPTION, OPTION_WAITING) return`) correctly skips RESERVATION (paid, `confirmReservation:269` sets `sysStatus=RESERVATION`) and already-CANCELLED. Fictitious flows have `offer=null` (`ReservationFlowMutationService.kt:391`) — the spec's null-guard `reservation.reservationFlow?.offer?.id?.let{}` handles it. The query `findAllBySysStatusAndOptionExpiresAtBefore(OPTION, now)` only selects sysStatus=OPTION, so a paid booking is never even loaded. **One gap:** a reservation that gets paid (→RESERVATION) *between* the query and the per-row processing (the loop iterates a list fetched once) could be re-read stale. The `findById` re-fetch inside `releaseExpiredOption` + the guard re-check on fresh entity state closes this — **provided `releaseExpiredOption` re-reads and re-checks inside its own transaction** (the spec does: `findById(reservationId)` then guard). Adequate.

**Concrete fix:** Confirmed safe AS LONG AS the guard re-reads inside the method's own `@Transactional` (spec does this). Add: the Stripe webhook (`promoteReservationToBooking`) and auto-release can race on the same reservation; rely on the re-read guard + the `sysStatus` transition being monotonic (OPTION→RESERVATION is terminal-ish). No further change, but add a test: pay an option in the same minute its expiry crosses, assert auto-release skips it.

---

## RECOMMENDED SAFE IMPLEMENTATION / DEPLOY ORDER

The design is **NOT safely shippable as one Phase-1 deploy.** It touches the money path (booking, offer freeing, partner sync) with three independent failure surfaces. Split into 4 ordered sub-deploys, each independently verifiable and revertible:

**Deploy 1 — Sync fidelity (lowest risk, pure correctness, no schema, no booking-path change).**
Ship sync-fidelity Change 2 (`ExternalReservationStatus.fromMmkValue` remap) + Change 3 (OPTION overlap-aware, `==FREE` downgrade guard) + Change 1 (MMK cadence 3×/day). Backend-only, enum is `@Enumerated(STRING)` so no migration. Gate on the new JUnit (HIGH-4) + the `==FREE` guard (sync-fidelity §3.2). Verify owner-week/regatta now block. This makes `external_reservations` trustworthy — the foundation everything else reads. Revert = single jar.

**Deploy 2 — Auto-release WITH the CRIT-1/HIGH-5 shared-offer guard.**
Ship `releaseExpiredOption` (with the "other active holds" requery — CRIT-1), the OPTION_WAITING handling (HIGH-5), the My-Bookings `[SYSTEM]` marker filter, and the partner-option sweep. Backend-only, no migration. This depends on Deploy 1 (sweep reads the now-correct `external_reservations`). Auto-release is unattended, so it MUST NOT ship before its shared-offer guard exists. Verify: expire an option sharing an offer with a live RESERVATION → offer stays RESERVED. Revert = jar.

**Deploy 3 — Booking-time atomic re-check (CRIT-2), standalone.**
Ship the pessimistic-lock + live-overlap re-assert in `createReservationFlow` BEFORE any search change. This hardens the money path against the contention the honest search will amplify. Backend-only. Verify: two concurrent guests on one offer → exactly one succeeds. This is the safety net that must pre-exist the honesty search.

**Deploy 4 — Honest search + status-gate (highest blast radius, last).**
Ship search Layer-1/Layer-2, the `V1_98` index (with `btree_gist` pre-installed by DBA per HIGH-7, or a plain btree if benchmark suffices), the `OfferStatus→ExternalReservationStatus` unification, the half-open fix (CRIT-3), the matview pre-filter decision (HIGH-6 option b), the 7-day-skip removal, and the FE `Status` 4-state + demo-scaffolding removal. This is the one deploy that changes what every customer sees AND requires a migration. Ship FE and BE together (status enum wire-contract). Gate on: option-whole-marina smoke (MED-9), turnaround off-by-one (CRIT-3), `EXPLAIN ANALYZE` sub-second at 494k rows (HIGH-7), count==visible (MED-9).

**Rationale for the order:** fidelity (data trust) → release (uses trusted data, but unattended so needs its guard) → booking lock (hardens money path) → search (consumes all three, biggest customer-visible blast radius, only migration). Each deploy is independently revertible; a failure in Deploy 4 (the risky one) leaves the hardened booking path (Deploy 3) and honest data (Deploys 1-2) intact. Never combine 3 and 4 — the lock must be proven in production under real contention before the honest search drives more traffic onto contended rows.

**Files with confirmed load-bearing facts:**
- `/Users/mariokuzmanic/Downloads/boat4you-delivery/boat4you-backend/boat4you-ws-main/src/main/kotlin/hr/workspace/boat4you/domains/reservation/service/ReservationFlowMutationService.kt:80` (lock-free offer.status gate), `:53-56` ReservationFlow.offer = `@ManyToOne`
- `.../reservation/service/ReservationMutationService.kt:391` (unconditional offer→FREE), `:355-377` markCancellationInitiated stamps `[AGENT]` not `[SYSTEM]`
- `.../catalouge/jpa/ExternalReservationRepository.kt:59,33,21` (CLOSED-interval option/availability queries — off-by-one)
- `.../catalouge/jpa/OfferRepository.kt:102` (correct half-open overlap)
- `.../catalouge/enums/ExternalReservationStatus.kt:25-40` (current mmk map), `.../enums/OfferStatus.kt:45-61` + `.../reservation/enums/ReservationStatus.kt:fromMmkValue` (canonical 0-11, `3→CANCELLED` divergence)
- `.../external/mmk/service/MmkAvailabilitySyncService.kt:117,148,184-187` and `MmkReservationIntegrationService.kt:171` (same `status` Long feeds both enums — remap is consistent)
- `.../db/migration/R__1_03_yacht_search_view.sql:81,130,49,52` (no offer_status WHERE filter; custom hardcoded FREE; per-day price divisor)
- `.../catalouge/job/SearchViewRefreshJob.kt:43` (2-min cron) + `SearchViewRefreshService.kt` (3s on-demand, NOT called by updateOfferStatus)
- `.../catalouge/services/OfferMutationService.kt` (evicts cache, NO matview refresh)
- `.../reservation/job/OptionExpiryService.kt:526-588` (current release frees nothing) + `OptionExpiryJob.kt` (30-min cron)
- `boat4you-web/.../models/yacht-offer.model.ts:5-9` (3-state Status) + `reservation-status.model.ts` (5-state, has SERVICE) + `BoatCalendarForm.tsx:64-65,174-175` (gate keys on OPTION/custom/inquireOnly)