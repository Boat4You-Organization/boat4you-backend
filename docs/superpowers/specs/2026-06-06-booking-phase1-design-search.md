The stale task #1 is unrelated to this read-only design task, so I'll leave the task list alone rather than mutate the user's PSI tracking. I have all the ground truth needed. Here is the spec.

---

# SPEC — SEARCH HONESTY (sub-area: `/public/yachts`)

## 0. Ground-truth confirmed from the code

- `offer` rows are discrete priced slots; the matview `yacht_search_view` (`R__1_03_yacht_search_view.sql`) flattens them, has NO date filter, carries `offer_status` + `date_from/date_to` + per-day price.
- The honest busy source is `external_reservations` (`ExternalReservation.kt`): `status` is `@Enumerated(STRING)` (V1_90 `V1_90__migrate_enum_columns_to_string.sql:358-363` migrated `external_reservations.status` smallint→varchar(31)), 5-state `ExternalReservationStatus` (UNKNOWN/OPTION/RESERVATION/SERVICE/FREE), `option_expiration timestamp`, indexed only on `yacht_id` (`V1_03__boat4you.sql:1438`).
- **The half-open-overlap pattern is already an established precedent in this codebase**: `V9_11__backfill_offer_unavailable_overlapping_reservation.sql:19-30` (`o.date_from < er.date_to AND o.date_to > er.date_from`, `er.status IN ('RESERVATION','SERVICE')`, OPTION deliberately left bookable) and `YachtRepository.findForReplacementSearch` (`YachtRepository.kt:166-204`). The new predicate reuses this exact shape.
- The A1 bug is `YachtQueryingService.buildYachtSearchPredicates` lines **807-841** (`DATE_FLEX_DAYS=3` start-proximity, ignores duration).
- Option-honesty (live `optionExpiration > now` gate) already exists for the badge via `ExternalReservationRepository.findOptionsByYachtIdsAndPeriod` (`ExternalReservationRepository.kt:52-67`) consumed in `getYachts` (`YachtQueryingService.kt:327-365`).
- FE card `BoatListingItemCard.tsx` has DEMO scaffolding to remove: `showSocialProof`/`interestedCount` (`:263-264`), `demoMap` id%10 status (`:292-306`), `pickDemoAmenities`/`demoAmenities` (`:223-257`), `isDealOfTheWeek`+countdown (`:348-374`,`:479-542`), `isCloseDayMatch` client inference (`:319-329`).

---

## DESIGN DECISION (a): keep matview for attributes/price, resolve availability at query-time via NOT EXISTS half-open overlap against `external_reservations` + a btree_gist daterange index. **Chosen. Justification below.**

**Why NOT rebuild the matview around dateranges:** the matview is the freeze-mitigation artifact (refreshed CONCURRENTLY every 2 min, `SearchViewRefreshJob.kt`). Its `offer_status` is *intentionally* up to ~2 min stale, which is exactly the dishonesty source. The honest truth (option expiry crossing `now`, a reservation synced 30s ago) changes faster than the matview refreshes. So availability MUST be resolved against the live `external_reservations` table at query time, NOT baked into the snapshot. We keep the matview for the expensive join (attributes, price, location, charter type) and the published-slot windows, and bolt the live truth on as a correlated subquery.

**Why this is sargable at ~494k rows:** the availability subquery is correlated per *yacht* (`er.yacht_id = root.id`), not per matview row. After the candidate set is already narrowed by location/vessel/price/etc. predicates (the matview filter indexes from `R__1_03` lines 146-153), only the surviving yacht-ids probe `external_reservations`. The probe is a half-open daterange `&&` overlap. We add:

```sql
-- NEW migration: V1_98__external_reservations_overlap_index.sql  (resolve-by-name; idempotent)
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- needed to combine yacht_id (btree) + daterange (gist) in one GiST index
CREATE INDEX IF NOT EXISTS idx_ext_reservation_yacht_daterange
  ON public.external_reservations
  USING gist (yacht_id, daterange(date_from, date_to, '[)'));
GRANT SELECT ON public.external_reservations TO boat4you_app;  -- already owner=boat4you_owner; ensure app role can read
```
`'[)'` = half-open (turnaround-safe, matches V9_11 semantics). The `(yacht_id, daterange)` composite GiST lets one index satisfy both the yacht-id equality and the `&& daterange(:from,:to,'[)')` overlap in the EXISTS probe. This is the standard Postgres exclusion/overlap pattern and stays cheap because the row count of `external_reservations` is small relative to the 494k matview rows (it is busy-intervals only, not the full catalogue).

> Note on owner/grants: app DB user is `boat4you_owner` per the task; `btree_gist`/`CREATE EXTENSION` may need a one-off superuser like `pg_trgm` did (`V1_93__search_perf_indexes.sql:31-34` documents the same caveat — replicate that deploy note). The GiST `CREATE INDEX` is non-CONCURRENTLY inside Flyway (table lock at startup, acceptable — same rationale as V1_93:24-30); document a manual `CREATE INDEX CONCURRENTLY` path for hot-prod rebuild.

---

## DESIGN DECISION (b): the new predicate + repo query + option-honesty integration

The current single matview-row aggregation cannot express EXACT/SHIFTED/SHORTER/LONGER per *slot*, because it GROUP BYs one row per yacht and picks one window. **Two-layer design:**

### Layer 1 — Candidate filtering inside `buildYachtSearchPredicates` (replaces lines 807-841)

Replace the `±DATE_FLEX_DAYS` start-proximity block with TRUE interval logic. New constant + predicate:

```kotlin
// REMOVE companion DATE_FLEX_DAYS (lines 83-90). ADD:
private const val NEARBY_WINDOW_DAYS = 3L  // honest "shifted week" reach for SHIFTED/SHORTER/LONGER

// In buildYachtSearchPredicates, replace lines 807-841 with:
val isCustomYacht = cb.isNull(root.get<LocalDate>("dateFrom"))
if (searchParams.startDate != null && searchParams.endDate != null) {
    if (!searchParams.startDate.isBefore(searchParams.endDate))
        throw IllegalArgumentException("Starting date must be before end date")
    val from = searchParams.startDate
    val to = searchParams.endDate
    // A published slot qualifies if its OWN window overlaps [from-N, to+N] —
    // i.e. it is the requested week OR a genuinely-nearby published week.
    // Pure interval logic on the slot's real window; duration is NOT assumed.
    val slotMatches = cb.and(
        cb.lessThan(root.get<LocalDate>("dateFrom"), to.plusDays(NEARBY_WINDOW_DAYS)),
        cb.greaterThan(root.get<LocalDate>("dateTo"), from.minusDays(NEARBY_WINDOW_DAYS)),
    )
    predicates.add(cb.or(isCustomYacht, slotMatches))

    // AVAILABILITY HONESTY: drop slots whose real window is hard-blocked
    // (RESERVATION/SERVICE) by external_reservations. OPTION is NOT excluded
    // here — optioned yachts MUST stay visible (badged, inquiry-only).
    // Correlated EXISTS over the slot's OWN window (date_from/date_to), not
    // the searched window, so a SHIFTED slot is judged on its real dates.
    val subAvail = cq.subquery(Long::class.java)
    val er = subAvail.from(ExternalReservation::class.java)
    subAvail.select(cb.literal(1L)).where(
        cb.equal(er.get<Yacht>("yacht").get<Long>("id"), root.get<Long>("id")),
        er.get<ExternalReservationStatus>("status")
            .`in`(ExternalReservationStatus.RESERVATION, ExternalReservationStatus.SERVICE),
        cb.lessThan(er.get<LocalDate>("dateFrom"), root.get<LocalDate>("dateTo")),
        cb.greaterThan(er.get<LocalDate>("dateTo"), root.get<LocalDate>("dateFrom")),
    )
    predicates.add(cb.or(isCustomYacht, cb.not(cb.exists(subAvail))))
}
// keep the startDate-only / endDate-only branches but rewrite them with the
// same overlap shape (no ±3 start clamp).
```

Apply identically in `getYachtSearchTotalCount` (it shares `buildYachtSearchPredicates` already, so the count stays consistent automatically — line 614-620).

**Why this keeps options visible:** the EXISTS subquery only excludes RESERVATION/SERVICE. OPTION rows never reach it, so an agency that options a whole marina leaves every yacht in the result set — they just carry the OPTION badge + inquiry gate (Layer 2). This is the CRITICAL requirement.

### Layer 2 — True per-yacht status + matched window, computed in `getYachts` (replaces the matview-`offer_status` aggregation)

The matview `prioritizedStatus` GREATEST(CASE) projection (`YachtQueryingService.kt:154-170`) and the `offerStatus`-based `isOption` gate (`:327-374`) get **replaced by a single live truth-resolution pass**:

1. Keep selecting `offerDateFrom/offerDateTo` via `exactOrEarliest` BUT change the source so the chosen window is the slot that best matches the request among slots that survived Layer 1. Add a `matchKind` rank so EXACT wins over SHIFTED/SHORTER/LONGER. Compute `matchKind` in Kotlin from the chosen `(offerDateFrom, offerDateTo)` vs `(searchStart, searchEnd)`:
   - EXACT: `from==start && to==end`
   - SHIFTED: same duration (`to-from == end-start`), different start
   - SHORTER: `to-from < end-start`
   - LONGER: `to-from > end-start`
2. After the page is materialized (`results`), do ONE bulk query for the true status of each yacht over its *chosen matched window* (not the searched window), mirroring `findOptionsByYachtIdsAndPeriod` but for all three blocking states + expiry:

```kotlin
// NEW in ExternalReservationRepository.kt — bulk true-status probe.
@Query("""
    SELECT r FROM ExternalReservation r
    WHERE r.yacht.id IN :yachtIds
      AND r.dateFrom < :endDate AND r.dateTo > :startDate
""")
fun findOverlappingByYachtIdsAndPeriod(
    @Param("yachtIds") yachtIds: List<Long>,
    @Param("startDate") startDate: LocalDate,
    @Param("endDate") endDate: LocalDate,
): List<ExternalReservation>
```
   Group by yacht-id; for each yacht's chosen window resolve TRUE status:
   - any overlapping `RESERVATION`/`SERVICE` → that slot was already excluded in Layer 1, so it won't appear; defensive fallback = next free window or drop.
   - any overlapping `OPTION` with `optionExpiration == null || optionExpiration > now()` → `OPTION` (VISIBLE, inquiry-only), carry soonest `optionExpiration` to `optionExpiresAt`.
   - else → `FREE` (reservable).

   This **discards the stale matview `offer_status` entirely** for the customer-facing status. The matview status is no longer the source of truth — `external_reservations` + `optionExpiration > now` is. (The existing `OfferStatus.UNAVAILABLE` filter at `:670-674` stays as a coarse pre-filter to drop owner-weeks/regatta that have no reservation row, since those live only on `offer.status`.)

> Because per-window status must be resolved per yacht, the `prioritizedStatus` GREATEST/CASE block (`:136-170`, `:203-205`) and the `offerStatus` column on `YachtSearchSelectResult` are no longer needed for status; keep `offerStatus` projection only if you still want the UNAVAILABLE coarse exclusion to remain in the matview (it does, via the WHERE predicate, not the SELECT — so the GREATEST block can be **deleted**).

---

## DESIGN DECISION (c): DTO additions + mapper changes + FE consumption

### Backend DTO

**`YachtSearchResponseDto.kt`** — add:
```kotlin
val realFrom: LocalDate? = null,   // the matched slot's TRUE check-in
val realTo: LocalDate? = null,     // the matched slot's TRUE check-out
val matchKind: MatchKind? = null,  // EXACT | SHIFTED | SHORTER | LONGER (null = no date search)
```
Keep `offerStatus` (now carries the TRUE FREE/OPTION), `isOption`, `optionExpiresAt`, `custom`. Deprecate-in-place `offerDateFrom/offerDateTo` (alias to realFrom/realTo so existing consumers don't break) — or repoint FE to `realFrom/realTo` and drop the old pair.

**New enum** `hr.workspace.boat4you.domains.catalouge.enums.MatchKind { EXACT, SHIFTED, SHORTER, LONGER }`.

**`YachtSearchSelectResult.kt`** — no longer needs `offerStatus: Int?` (status now from live probe). Keep `offerDateFrom/offerDateTo` as the chosen window.

### Mapper

**`YachtMapper.toDto`** (`:41-106`): drop the `result.offerStatus?.let { raw -> … }` resolution (`:59-62`); accept `trueStatus: OfferStatus`, `realFrom`, `realTo`, `matchKind`, `optionExpiresAt` as params and set them directly. `isOption = trueStatus == OPTION`.

### FE — `BoatListingItemCard.tsx`

Consume server truth; REMOVE all client inference/demo:
- DELETE `resolvedStatus` demo `demoMap` (`:283-306`) → use `offerStatus` directly. `isAvailable = offerStatus === FREE`. OPTION → render an "Under option — inquire" badge (replaces the misleading "SPECIAL PROMOTION" red ribbon `:438-474`, which currently dresses options up as deals — dishonest).
- DELETE social-proof demo (`:259-264`, `:699-725`).
- DELETE deal-of-the-week + countdown (`:343-374`, `:479-542`); `showListPrice`/`discountPercent` may stay ONLY if `listPriceEur` is real (it is, from backend) — but remove the fabricated 40%-deal ribbon.
- REPLACE `isCloseDayMatch` client-side date math (`:319-329`) with the server `matchKind`: render the badge label off `matchKind` (`EXACT` → none; `SHIFTED`/`SHORTER`/`LONGER` → "Closest week" + `realFrom–realTo`). Show `realFrom/realTo` (the honest window), not the user's searched dates.
- KEEP `demoPool`/`pickDemoAmenities` deletion (`:205-257`) — fall back to nothing rather than fake icons when `amenityKeys` empty.

**FE model** `yacht.model.ts` `YachtModelShortInfo` (`:193-247`): add `realFrom?: string|null`, `realTo?: string|null`, `matchKind?: 'EXACT'|'SHIFTED'|'SHORTER'|'LONGER'|null`; keep `offerStatus`, `optionExpiresAt`, `custom`, `isOption`.

The reserve-vs-inquiry gate (`BoatCalendarForm.tsx:174-182`, `BoatMobileNavigation.tsx:55-70`) already keys on `Status.OPTION || custom || inquireOnly` — no change needed; OPTION yachts surfaced by the new search route into inquiry exactly as designed.

---

## DESIGN DECISION (d): the search-time sync trigger must not undermine honesty

Current flow (`YachtController.kt:158-164`): if `startDate/endDate` set AND duration ≠ 7 days, fire `externalSyncService.syncYachtOffers(startDate, endDate, locations)` — `@Async`, gated by `serviceCallCacheService.shouldCallYachtSearch` (skip-cache), and the matview refreshes on a 2-min cron. **The 7-day skip is the honesty hole:** a Sat-Sat (7-day) search NEVER triggers a live partner sync, so it serves whatever the periodic catalogue sync loaded — and the matview status (now bypassed) was the stale part.

Recommended changes (minimal, honest):
1. **Remove the `!= 7L` skip** (`YachtController.kt:158-160`) so *every* dated search with `locations` fires the throttled async sync. The `shouldCallYachtSearch` cache + 5-min hard cap + cross-VM mutex already throttle it (`ExternalSyncService.kt:43-46`, `:69-76`); the skip was an optimization that specifically starved the most common (Sat-Sat) query of fresh data.
2. **Honesty does not depend on the sync completing** because availability is now resolved against `external_reservations` live at query time (Layer 1/2), not the matview snapshot. The sync only enriches *future* requests; the current request is already honest from whatever reservation rows exist. So even when the async sync is mid-flight or cache-skipped, the result is "honest as of last sync," never a false "available for your exact week."
3. **Matview staleness is now status-irrelevant.** Since customer-facing status comes from the live probe, the 2-min matview lag only affects attributes/price (acceptable, as the job comment already states). No change to `SearchViewRefreshJob`.
4. Optional safety: the existing `OfferStatus.UNAVAILABLE` coarse pre-filter (`:670-674`) can lag the matview by ≤2 min — but Layer 1's RESERVATION/SERVICE EXISTS catches anything the matview missed, so a freshly-reserved week is excluded *immediately* regardless of matview freshness. This is the key honesty guarantee.

---

## Files to touch (file-level checklist)

Backend:
- `…/catalouge/services/YachtQueryingService.kt` — companion (drop `DATE_FLEX_DAYS`, add `NEARBY_WINDOW_DAYS`); `buildYachtSearchPredicates` 807-841 rewrite + RESERVATION/SERVICE EXISTS; delete `prioritizedStatus` block 136-170,203-205; rewrite `getYachts` status pass 327-375 to use live probe + matchKind.
- `…/catalouge/jpa/ExternalReservationRepository.kt` — add `findOverlappingByYachtIdsAndPeriod`.
- `…/catalouge/jpa/YachtSearchSelectResult.kt` — drop `offerStatus: Int?`; keep windows.
- `…/catalouge/dto/YachtSearchResponseDto.kt` — add `realFrom/realTo/matchKind`.
- `…/catalouge/enums/MatchKind.kt` — NEW.
- `…/catalouge/mapper/YachtMapper.kt` — `toDto` 41-106 signature + status/window/matchKind wiring.
- `…/catalouge/controllers/YachtController.kt` — 158-160 remove 7-day skip.
- `…/db/migration/V1_98__external_reservations_overlap_index.sql` — NEW (btree_gist + GiST index + grant).

Web:
- `…/components/BoatListingItemCard/BoatListingItemCard.tsx` — delete demo blocks (status/social/deal/closest-day inference), consume `offerStatus`/`realFrom`/`realTo`/`matchKind`; OPTION badge.
- `…/models/yacht.model.ts` — `YachtModelShortInfo` add `realFrom/realTo/matchKind`.

No change required: `BoatCalendarForm.tsx`, `BoatMobileNavigation.tsx` (gate already correct), `SearchViewRefreshJob.kt`.

---

## Edge cases

- **Custom yachts** (`entry_type=CUSTOM`, NULL dates, `offer_status='FREE'` in matview branch 2): keep the `isCustomYacht` OR escape in every dated predicate (already there at 807) so they always show as "On request"/inquiry; they have no `external_reservations`, so the EXISTS is vacuously false → never excluded.
- **Yacht with overlapping OPTION whose `optionExpiration` already passed** (stale partner row, `ExternalReservationRepository.kt:48-50` notes this): the `optionExpiration > now` gate must run in Layer 2 → treat as FREE, not OPTION. Matches existing `findOptionsByYachtIdsAndPeriod` semantics.
- **OPTION with NULL `optionExpiration`** (some older MMK rows, DTO doc `:84-87`): treat as still-live OPTION (visible, inquiry-only) — fail safe toward "don't claim bookable."
- **Turnaround day** (charter ends the morning the next begins): half-open `[)` / strict `<`,`>` makes it NOT a conflict — matches V9_11:10-11.
- **Multiple overlapping reservations** for one yacht/window: any RESERVATION/SERVICE → excluded; among OPTIONs pick soonest expiry (existing `expiries.min()` logic, `:348`).
- **No-date search** (`startDate==null`): no availability predicate, no matchKind — behaves as today (browse mode), status from live probe over the chosen earliest window or just FREE.
- **`getYachtSearchTotalCount`** must use the SAME predicates (it does, shared method) so page count == visible count after honesty filtering.
- **Hibernate empty `IN` list**: `findOverlappingByYachtIdsAndPeriod` is only called when the page has ids — guard `if (ids.isEmpty()) return emptyMap()` (mirrors `fetchTopAmenities:395`).

---

## Verify approach (this project's tools)

Backend:
- `./gradlew compileKotlin ktlintCheck` (in `boat4you-ws-main`) — Criteria subquery + new repo method + DTO/enum compile + lint. Watch the V1_90 STRING-enum gotcha: compare `er.status` against `ExternalReservationStatus` enum, never an int.
- `./gradlew flywayValidate` (or app boot on a scratch DB) to confirm `V1_98` applies and `btree_gist` is creatable (else document superuser one-off like `pg_trgm`).
- `EXPLAIN (ANALYZE, BUFFERS)` the new search SQL on a prod-size copy: confirm the GiST `idx_ext_reservation_yacht_daterange` is used for the EXISTS probe and the matview filter indexes (`R__1_03:146-153`) still drive candidate narrowing; assert total stays in the ~seconds range, not regressing the `>60s→~3.6s` win.
- curl smoke on a running instance:
  - `GET /public/yachts?did=r-XX&startDate=…&endDate=…` (Sat-Sat): assert no slot is FREE when an overlapping RESERVATION exists; assert SHIFTED slots carry `realFrom/realTo` ≠ requested + `matchKind`.
  - Option case: pick a yacht with a live OPTION row → assert it APPEARS with `offerStatus=OPTION`, `optionExpiresAt` set, and is not dropped.
  - Expired-option case: `optionExpiration < now` → assert `offerStatus=FREE`.
- `journalctl -u boat4you.service -f` during smoke for the async-sync WARN/ERROR lines (`ExternalSyncService.kt:78`) and to confirm removing the 7-day skip doesn't flood partner APIs (the cache marker `shouldCallYachtSearch` should suppress repeats).

Web:
- `npx tsc --noEmit` + `npx eslint src/components/BoatListingItemCard/BoatListingItemCard.tsx src/models/yacht.model.ts` (lint hook is enforced — clean to 0, no `--no-verify`).
- Visual: run a Sat-Sat search, confirm OPTION yachts show inquiry badge (not "SPECIAL PROMOTION"), SHIFTED yachts show "closest week" with the real window, and no fabricated countdown/social-proof renders.