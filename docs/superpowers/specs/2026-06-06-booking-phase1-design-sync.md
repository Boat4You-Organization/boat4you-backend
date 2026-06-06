I have full confirmation of the entire chain. Now I'll write the implementation-ready spec.

---

# SPEC — Partner Sync Fidelity + Cadence (Phase 1)

Sub-area: make `external_reservations` trustworthy as the honest busy source. Three changes: (1) MMK availability cadence 1x→3x/day, (2) fix `ExternalReservationStatus.fromMmkValue` so blocking partner codes stop leaking as bookable, (3) make the OPTION availability-sync branch overlap-aware in both partners. All changes verified against `mmk_api_2_1_5.yaml` and the canonical `OfferStatus.fromMmkValue` doc-table.

---

## GROUND TRUTH established by code-reading (load-bearing)

The MMK `/availability/{year}` endpoint (`MmkAvailabilityIntegrationService.kt:31` → `getAvailability`) returns `AvailabilityResponse.status` as a **Long**. The SAME status value is fed to both:
- `OfferStatus.fromMmkValue(...)` in the offer/reservation sync (`MmkYachtOfferSyncService.kt:163`, `MmkReservationIntegrationService.kt:171,196`), and
- `ExternalReservationStatus.fromMmkValue(...)` in availability sync (`MmkAvailabilitySyncService.kt:117`).

`OfferStatus.fromMmkValue` (file `.../catalouge/enums/OfferStatus.kt:31-61`) carries the **authoritative documented code table** (0-11) and is the proven-correct mapping (it powers the live offer status). The 0-11 meanings:

| code | meaning | bookable? |
|---|---|---|
| 0 | Free | yes |
| 1 | Reservation | NO |
| 2 | Option | yes (held, inquiry-only) |
| 3 | Option expired | yes |
| 4 | Service | NO |
| 5 | Cancelled (storno) | yes |
| 6 | Owner week | NO |
| 7 | Offer (sent to a client) | yes |
| 8 | Custom | yes |
| 9 | Option on waiting | yes (second option) |
| 10 | Regatta | NO |
| 11 | Sleep Aboard | NO |

CRITICAL DISCREPANCY (do NOT use): the `shortAvailability` endpoint doc (`mmk_api_2_1_5.yaml:903-909`) lists a *different, shorter* code set (0=Available,1=Reservation,2=Option,3=Option-in-expiration,4=Service,B=Sleepaboard). That is a SEPARATE endpoint we do NOT call. The sync uses `/availability/{year}`, whose codes are the 0-11 table above. Map against the 0-11 table.

The **current `ExternalReservationStatus.fromMmkValue` (`ExternalReservationStatus.kt:25-40`) is WRONG**: it maps `1→RESERVATION, 2→OPTION, 4→SERVICE, 7→FREE` and collapses `3,5,6,8,9,10,11→UNKNOWN` (and `0→UNKNOWN` via else). Because `updateOffer` treats `UNKNOWN` as a no-op (`MmkAvailabilitySyncService.kt:184-187`), genuinely-blocking codes **6 (owner week), 10 (regatta), 11 (sleep aboard)** leak through → those weeks stay FREE/bookable. Code `7=Offer` is mis-mapped to FREE (harmless — it IS non-blocking) and `0=Free` falls to UNKNOWN (harmless — no-op is correct for free).

Persistence: `ExternalReservation.status` is `@Enumerated(EnumType.STRING)` (`ExternalReservation.kt:38-40`) → **adding a new enum value is ordinal-safe**, no migration needed for the column. But the enum is also serialized to the FE detail calendar verbatim via `YachtAvailabilityDto.status` (`YachtAvailabilityDto.kt:12` ← `getYachtAvailability` `YachtQueryingService.kt:1035-1057`). FE detail collapses blocking → UNAVAILABLE via `SimpleOfferStatus` (SERVICE/RESERVATION → UNAVAILABLE). **Decision: reuse the existing `SERVICE` value for all blocking codes** rather than introduce a new `BLOCKED` value — `SERVICE` already exists in both `OfferStatus` and `ExternalReservationStatus`, is already handled as blocking in `updateOffer` (`RESERVATION, SERVICE -> UNAVAILABLE`), the detail calendar, and search. Zero new FE wiring, zero new `when()` exhaustiveness breaks. (A new `BLOCKED` value would force edits in `updateOffer` both partners + FE detail-calendar status labels.)

---

## CHANGE 1 — MMK availability cadence 1x → 3x/day

File: `boat4you-backend/boat4you-ws-main/src/main/kotlin/hr/workspace/boat4you/domains/external/mmk/job/MmkSyncJob.kt`

Current: `availabilitySync()` at lines 129-136, cron `"0 10 8 * * ?"` (once daily at 08:10), `@SchedulerLock(name = "mmkAvailabilitySync", lockAtMostFor = "PT1H")`.

Change the cron to 3x/day mirroring NauSys's cadence (NauSys runs `0 20 3,12,18`). MMK catalogue runs at 06:00, yacht/offer at 06:10–07:00, lang at 07:20. Pick hours that don't collide and that match the spirit of "3×/day, hours apart". Recommended: **`"0 40 8,13,19 * * ?"`** (08:40 after the daily catalogue/yacht/offer/lang chain finishes; 13:40; 19:40). Keep `@SchedulerLock` unchanged.

Exact edit (replace lines 129-136):
```kotlin
    /**
     * Syncs MMK yacht availability 3x/day (08:40, 13:40, 19:40) so a partner-side
     * reservation/option surfaces within hours, matching the NauSYS cadence
     * (0 20 3,12,18). 08:40 leaves headroom after the daily MMK catalogue (06:00),
     * yacht/offer (06:10–07:00) and lang (07:20) chain. Keeps @SchedulerLock so only
     * one scheduler node (cusma3) runs it.
     */
    @Scheduled(cron = "0 40 8,13,19 * * ?")
    @SchedulerLock(name = "mmkAvailabilitySync", lockAtMostFor = "PT1H")
    fun availabilitySync() {
        log.info("Syncing MMK yacht availability")
        val startTime = System.currentTimeMillis()
        mmkAvailabilityIntegrationService.syncYachtAvailability()
        log.info("Syncing MMK yachts availability took ${System.currentTimeMillis() - startTime} ms")
    }
```

Optional backup pass (matches the project's existing backup-sync pattern, e.g. `runCatalogueBackupSync`): NOT strictly required — availability sync already swallows per-agency/per-year exceptions internally (`MmkAvailabilityIntegrationService.kt:33-35`), so a failure of one agency doesn't abort the run, and the next of the 3 daily passes self-heals. If a backup is wanted for parity with NauSys robustness, add a 4th `@Scheduled(cron = "0 10 21 * * ?")` guarded by `serviceCallCacheService.shouldRunScheduledSync(...)` — but this requires adding a `MethodCacheEnum.SCHEDULED_MMK_AVAILABILITY_SYNC` and calling `saveScheduledSync` at the end of `availabilitySync()`. **Recommendation: skip the backup pass for Phase 1** (3 self-healing passes is sufficient; less code per the project's minimal-code rule). Note this explicitly so the implementer doesn't add the cache enum unnecessarily.

`@SchedulerLock` requirement is satisfied (unchanged). `lockAtMostFor = "PT1H"` is fine since runs are 5h apart.

---

## CHANGE 2 — Fix `ExternalReservationStatus.fromMmkValue` (stop blocking-code leakage)

File: `boat4you-backend/boat4you-ws-main/src/main/kotlin/hr/workspace/boat4you/domains/catalouge/enums/ExternalReservationStatus.kt`

Replace `fromMmkValue` (lines 25-40) with a mapping aligned to the canonical 0-11 table, reusing `SERVICE` for all hard-blocking codes (6 owner-week, 10 regatta, 11 sleep-aboard) and `4 Service` itself. Map non-blocking-but-available codes (0,3,5,7,8) to `FREE` (so `updateOffer` no-op leaves offer-sync as source of truth) and option codes (2,9) to `OPTION`. CANCELLED (5) is explicitly mapped to `FREE` (boat is available) — handled explicitly because `/availability` returns canceled reservations per the spec (`mmk_api_2_1_5.yaml:861` "All reservations are returned including canceled reservations").

New mapping (add a doc-comment mirroring OfferStatus, and keep enum value list unchanged — UNKNOWN/OPTION/RESERVATION/SERVICE/FREE; NO new value):

```kotlin
        /**
         * MMK /availability status codes (same code space as OfferStatus.fromMmkValue):
         *  0 Free, 1 Reservation, 2 Option, 3 Option-expired, 4 Service, 5 Cancelled,
         *  6 Owner week, 7 Offer, 8 Custom, 9 Option-on-waiting, 10 Regatta, 11 Sleep-aboard.
         *
         * We collapse into the 5 ExternalReservationStatus states used by updateOffer:
         *  - RESERVATION / SERVICE  -> updateOffer marks overlapping offers UNAVAILABLE
         *  - OPTION                 -> updateOffer flips overlapping offers to OPTION
         *  - FREE / UNKNOWN         -> updateOffer no-op (offer sync owns FREE)
         *
         * Blocking codes 6/10/11 (owner week, regatta, sleep aboard) MUST map to a blocking
         * status (SERVICE) — previously they fell to UNKNOWN and leaked as bookable.
         * CANCELLED (5) is returned by /availability per spec and is NON-blocking -> FREE.
         */
        fun fromMmkValue(value: Long?): ExternalReservationStatus {
            return when (value?.toInt()) {
                0 -> FREE              // Free
                1 -> RESERVATION       // Reservation (blocking)
                2 -> OPTION            // Option (held)
                3 -> FREE              // Option expired -> boat available
                4 -> SERVICE           // Service / maintenance (blocking)
                5 -> FREE              // Cancelled (storno) -> boat available
                6 -> SERVICE           // Owner week -> blocking
                7 -> FREE              // Offer sent to client -> boat available
                8 -> FREE              // Custom (charter internal) -> boat available
                9 -> OPTION            // Option on waiting -> held
                10 -> SERVICE          // Regatta -> blocking
                11 -> SERVICE          // Sleep aboard -> blocking
                else -> UNKNOWN
            }
        }
```

Notes on this mapping:
- This is consistent with `OfferStatus.fromMmkValue`: codes 6/10/11 there are `UNAVAILABLE`; here the `ExternalReservationStatus.SERVICE` branch in `updateOffer` produces `OfferStatus.UNAVAILABLE` — same end-state.
- Codes 3/5/7/8 → `FREE` rather than `UNKNOWN`: behaviorally identical today (both are no-ops in `updateOffer`), but `FREE` is semantically honest and renders correctly if ever surfaced via `YachtAvailabilityDto` to the FE detail calendar.
- Code 9 (option-on-waiting) → `OPTION`: surfaces as inquiry-only "pre-reserved", which is correct (the boat is held pending the first option's expiry).
- `else -> UNKNOWN` is preserved as the safe fallback for any future/unparseable code (no-op, never over-blocks).
- `fromNausysValue` (lines 16-23) is UNCHANGED — NauSys enum is already correct.

---

## CHANGE 3 — OPTION branch: overlap-aware (audit A3)

Two files, identical change:
- `.../nausys/service/NauSysAvailabilitySyncService.kt` — `updateOffer`, OPTION branch lines 153-168.
- `.../mmk/service/MmkAvailabilitySyncService.kt` — `updateOffer`, OPTION branch lines 151-166.

Problem: the OPTION branch uses `matchingOffers = findAllByYachtAndDateFromAndDateTo(yacht, dateFrom, dateTo)` (exact-date, computed at `NauSys:150` / `MMK:148`), while RESERVATION/SERVICE correctly use `findAllByYachtAndDateRangeOverlap` (half-open). A partial-week or non-Saturday-aligned OPTION (e.g. Wed→Wed) finds NO exact-match Sat-Sat offer, so it falls to `synthesizeOptionOffer` and **leaves the overlapping 7-day Sat-Sat offers FREE** → those weeks stay bookable+reservable even though they're under another agent's option.

Fix: in the OPTION branch, flip ALL overlapping FREE/available offers to OPTION using the existing half-open `findAllByYachtAndDateRangeOverlap` query (`OfferRepository.kt:92-106`, already present, already half-open). Synthesize ONLY when truly no overlapping offer exists.

The `matchingOffers` variable (NauSys:150 / MMK:148) is used ONLY by the OPTION branch (RESERVATION/SERVICE re-query overlap inline; FREE/UNKNOWN no-op). So replace its use in the OPTION branch with an overlap query. Cleanest: move the overlap query into the OPTION branch and delete the now-unused `matchingOffers` line.

### NauSys edit

Delete line 150 (`val matchingOffers = offersRepository.findAllByYachtAndDateFromAndDateTo(yacht, dateFrom, dateTo)`) and its preceding comment (148-149).

Replace the OPTION branch (153-168):
```kotlin
            ExternalReservationStatus.OPTION -> {
                // Overlap, NOT exact-date match: an option held by another agent blocks
                // bookability of EVERY week it touches — including partial-week and
                // non-Saturday-aligned options that exact matching silently left FREE+reservable
                // (audit A3). Flip overlapping FREE offers to OPTION (inquiry-only). Already-OPTION
                // rows are left as-is. Synthesize a stand-in only when NO offer overlaps at all.
                val overlapping = offersRepository.findAllByYachtAndDateRangeOverlap(yacht, dateFrom, dateTo)
                if (overlapping.isNotEmpty()) {
                    overlapping.forEach { offer ->
                        if (offer.status != OfferStatus.OPTION) {
                            offer.status = OfferStatus.OPTION
                            offersRepository.save(offer)
                            log.info(
                                "Offer ${offer.id} (yacht ${yacht.id} ${offer.dateFrom}→${offer.dateTo}) " +
                                    "flipped to OPTION (overlaps OPTION $dateFrom→$dateTo, " +
                                    "external_reservation ${externalReservation.id})",
                            )
                        }
                    }
                } else {
                    synthesizeOptionOffer(yacht, externalReservation, existingYachtOffers)
                }
            }
```

### MMK edit

Identical change in `MmkAvailabilitySyncService.kt`: delete line 148 (`val matchingOffers = ...`), replace OPTION branch (151-166) with the same block (the existing MMK log strings already prefix "MMK offer"; keep that prefix in the log message for consistency with surrounding MMK logs).

### Important edge-case warnings for the OPTION-overlap change

1. **Over-blocking a multi-week / mis-aligned overlap is the risk to watch.** `findAllByYachtAndDateRangeOverlap` will flip EVERY Sat-Sat offer the option touches to OPTION. For a partial-week option (Wed→Sat, 3 days) this still flips the full surrounding Sat-Sat week to OPTION — which is the intended honest behavior (the yacht genuinely cannot be cleanly chartered that week), and OPTION is inquiry-only not blocked, so the user can still inquire. This mirrors what RESERVATION/SERVICE already do for blocking. Acceptable and correct.

2. **OPTION must not stomp a real RESERVATION.** Half-open overlap could match an offer already set `UNAVAILABLE` by a RESERVATION on the same yacht for an adjacent overlapping interval. The guard `if (offer.status != OfferStatus.OPTION)` would flip an `UNAVAILABLE` offer DOWN to `OPTION`, weakening a real block. **Add a downgrade guard**: only flip offers that are currently FREE (leave UNAVAILABLE and already-OPTION untouched). Tighten the condition to:
   ```kotlin
   if (offer.status == OfferStatus.FREE) {
   ```
   This is safer than `!= OPTION` and prevents an OPTION reservation from un-blocking a reserved week. (RESERVATION/SERVICE branch keeps its own `!= UNAVAILABLE` guard, which is fine because UNAVAILABLE is the strongest state.) **Use `== OfferStatus.FREE` in both the NauSys and MMK OPTION blocks above** — adjust the snippet's `if (offer.status != OfferStatus.OPTION)` to `if (offer.status == OfferStatus.FREE)`.

3. **Synthetic OPTION rows**: `synthesizeOptionOffer` only runs when `overlapping.isEmpty()`. Synthetic rows carry `extStatus = SYNTHETIC_OPTION` and are skipped by offer-sync cleanup — unchanged. A synthetic row created on a prior pass that now overlaps will already have `status = OPTION`, so the `== FREE` guard leaves it alone. Good.

---

## OVER-BLOCKING RISK NOTE (CHANGE 2)

The danger in Change 2 is mapping a legitimately-free code to a blocking status. Verified against the canonical 0-11 table and cross-checked with `OfferStatus.fromMmkValue`:
- Codes mapped to `SERVICE` (blocking): **4, 6, 10, 11** — all are documented non-bookable (Service, Owner week, Regatta, Sleep Aboard) and match `OfferStatus`'s `UNAVAILABLE/SERVICE`. No legitimately-free week is blocked.
- Codes 3 (option-expired), 5 (cancelled/storno), 7 (offer), 8 (custom) → `FREE` — all documented as "boat is available". NOT blocked. This is the key over-blocking safeguard: a naive "anything not in {0,7}→blocked" would have wrongly killed cancelled/expired/offer weeks. The explicit per-code mapping prevents that.
- `else -> UNKNOWN` (no-op) means any undocumented future code defaults to NON-blocking — fails open, never over-marks.

This asymmetry is deliberate and correct for Phase 1 (honest availability without false unavailability).

---

## VERIFY APPROACH (project tools)

Backend, from `boat4you-backend/boat4you-ws-main`:
1. `./gradlew compileKotlin` — confirms the `when` exhaustiveness still holds (no new enum value added, so existing `when(externalReservation.status)` blocks in both sync services stay exhaustive; the `FREE/UNKNOWN/null` arm already covers the FREE outputs the new mapping produces). Confirms `findAllByYachtAndDateRangeOverlap` signature matches.
2. `./gradlew ktlintCheck` (or `ktlintMainSourceSetCheck`) — the project enforces ktlint via pre-commit hook; clean to 0, no `--no-verify`. Watch line-length on the new log strings (the file uses 120-col wrapping with `+` concatenation — keep that style).
3. Targeted unit check (optional, fast): a small JUnit over `ExternalReservationStatus.fromMmkValue` asserting 1→RESERVATION, 4/6/10/11→SERVICE, 2/9→OPTION, 0/3/5/7/8→FREE, null/99→UNKNOWN. If the project has no enum tests, a `gradle test` of the external sync package suffices.

Smoke (post-deploy, cusma3 = scheduler node, `data-sync` profile):
4. `journalctl -u boat4youscheduler.service --since "today" | grep -i "Syncing MMK yacht availability"` — expect 3 entries/day at ~08:40/13:40/19:40 after deploy.
5. After an availability pass: `journalctl -u boat4youscheduler.service | grep -iE "flipped to OPTION \(overlaps|set to UNAVAILABLE \(overlaps"` — expect the new overlap-driven OPTION flip log lines for partial-week options, and continued RESERVATION/SERVICE overlap lines.
6. DB spot-check (psql as `boat4you_owner` on cusma4 `boat4you_db`): pick a yacht with a known MMK owner-week/regatta (status 6/10) reservation —
   `SELECT status, count(*) FROM external_reservations WHERE yacht_id = <id> GROUP BY status;` should now show `SERVICE` rows where previously `UNKNOWN`. Then confirm the overlapping `offer` rows for that interval are `UNAVAILABLE` (4), not `FREE` (1).
7. Materialized view propagation: `yacht_search_view` refreshes CONCURRENTLY every 2 min (`SearchViewRefreshJob`) — re-query search for that yacht's week after ~2-3 min and confirm it no longer shows as freely bookable.

No new migration is required (enum is `@Enumerated(STRING)`, no column/DB change; crons are code annotations). If the optional MMK backup pass is added, it WOULD require a new `MethodCacheEnum` value — recommendation is to skip it.

---

## FILE CHANGE SUMMARY

| File | Change |
|---|---|
| `.../external/mmk/job/MmkSyncJob.kt` (129-136) | cron `0 10 8 * * ?` → `0 40 8,13,19 * * ?`; keep `@SchedulerLock`; updated doc-comment. No backup pass (recommended). |
| `.../catalouge/enums/ExternalReservationStatus.kt` (25-40) | rewrite `fromMmkValue` to canonical 0-11 table; codes 4/6/10/11→SERVICE (blocking), 1→RESERVATION, 2/9→OPTION, 0/3/5/7/8→FREE, else→UNKNOWN. No new enum value. |
| `.../external/nausys/service/NauSysAvailabilitySyncService.kt` (148-168) | drop exact-match `matchingOffers`; OPTION branch uses `findAllByYachtAndDateRangeOverlap`; flip only `status == FREE` → OPTION; synthesize only when no overlap. |
| `.../external/mmk/service/MmkAvailabilitySyncService.kt` (148-166) | identical OPTION-branch change as NauSys. |

No new entity, column, migration, DTO field, or repo query — `findAllByYachtAndDateRangeOverlap` (`OfferRepository.kt:92-106`) already exists and is already half-open. Deploy = manual to cusma2 (boat4you.service) + cusma3 (boat4youscheduler.service, the node that actually runs `@Profile("data-sync")` jobs).