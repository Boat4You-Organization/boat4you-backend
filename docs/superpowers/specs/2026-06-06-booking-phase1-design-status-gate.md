I now have a complete picture of the status flow across both backend and frontend. I have everything needed to write the implementation-ready spec. Let me compose the final deliverable.

---

# SPEC — STATUS UNIFICATION + DETAIL + RESERVE-vs-INQUIRY GATE (Phase 1)

## 0. Root cause & the new status flow (read first)

Two parallel status systems exist today and they disagree:

- **5-state truth**: `ExternalReservationStatus {UNKNOWN(0), OPTION(1), RESERVATION(2), SERVICE(3), FREE(4)}` — the honest busy source in `external_reservations`, with `optionExpiration`. Used by `/availability`.
- **Lossy 3-state**: `SimpleOfferStatus {FREE, OPTION, UNAVAILABLE}` — derived in `OfferMapper.kt:32` from the discrete priced `offer.status` (`OfferStatus`, 10-state). Used by `/standard-offers` + detail `getYacht().offers` → drives `selectedOffer.status` → drives the reserve-vs-inquiry gate.

The lossiness: `SimpleOfferStatus.fromOfferStatus` collapses RESERVED / SERVICE / OPTION_EXPIRED / CANCELLED / OPTION_WAITING / INFO / UNKNOWN all into `UNAVAILABLE` (everything that isn't FREE/OPTION/OPTION_WAITING). Two problems:
1. RESERVATION and SERVICE are indistinguishable from each other and from an expired option.
2. An **expired** option (`OfferStatus.OPTION_EXPIRED`, or an `OPTION` whose backing `external_reservations.optionExpiration < now`) reads as `UNAVAILABLE` instead of the honest `FREE` — so a yacht that is actually bookable shows "not available" and the reserve button stays disabled.

**Target unified customer-facing status = 4 effective states**, expressed on the existing `ExternalReservationStatus` semantics:

| Effective status | Meaning | Gate behavior |
|---|---|---|
| `FREE` | bookable (incl. expired option, CANCELLED, OPTION_EXPIRED, INFO, UNKNOWN, FREE) | **reserve** |
| `OPTION` | live option (optionExpiration ≥ now) — visible, selectable | **inquiry-only** |
| `RESERVATION` | partner reservation | **hard-blocked** (not selectable, no inquiry) |
| `SERVICE` | maintenance / owner block | **hard-blocked** |

Single source-of-truth mapper (BE): one function that takes `OfferStatus` + the offer's live option-expiry knowledge and returns the effective `ExternalReservationStatus`. Both `/standard-offers` (via `OfferMapper`) and `/availability` (via the translator) converge onto it.

---

## 1. BACKEND

### 1.1 New enum: customer-facing 4-state — reuse `ExternalReservationStatus`

Do **not** invent a new enum; the FE already has `ReservationStatus` (5-state, string) and `OfferStatus` (10-state). Standardize the **wire status** that both detail feeds emit onto `ExternalReservationStatus` string values (`FREE`/`OPTION`/`RESERVATION`/`SERVICE`; `UNKNOWN` is mapped to FREE by the helper so it never escapes for offers).

### 1.2 NEW helper — collapse `OfferStatus` → `ExternalReservationStatus`

**File (new):** `boat4you-backend/.../domains/catalouge/enums/OfferStatusUnification.kt`

```kotlin
package hr.workspace.boat4you.domains.catalouge.enums

// Customer-facing collapse of the 10-state partner OfferStatus onto the 4 honest
// states. Phase 1: expired options (handled by caller, see Offer.effectiveStatus)
// surface as FREE. RESERVED→RESERVATION, SERVICE→SERVICE (hard-block),
// OPTION/OPTION_WAITING→OPTION (inquiry). Everything else (FREE, OPTION_EXPIRED,
// CANCELLED, INFO, UNKNOWN, UNAVAILABLE owner/regatta) → FREE per Phase-1 goal of
// "honest over the periods partners already publish": only an active partner
// hold (reservation/service/live-option) blocks/limits a published priced slot.
fun OfferStatus.toCustomerStatus(): ExternalReservationStatus =
    when (this) {
        OfferStatus.RESERVED -> ExternalReservationStatus.RESERVATION
        OfferStatus.SERVICE -> ExternalReservationStatus.SERVICE
        OfferStatus.OPTION, OfferStatus.OPTION_WAITING -> ExternalReservationStatus.OPTION
        else -> ExternalReservationStatus.FREE
    }
```

> Edge note on `UNAVAILABLE(4)` (owner week / regatta / sleep-aboard): a discrete *priced* offer row almost never carries this (those become absent rows, not priced slots). If one does, Phase-1 default is FREE since the row carries a partner price; if QA shows owner-weeks leaking as priced FREE slots, add `OfferStatus.UNAVAILABLE -> RESERVATION` to the `when`. Flag, don't guess. **Confirm with a prod count** (verify step V4) before deciding.

### 1.3 `Offer` entity — expired-option awareness for the detail feed

`/standard-offers` reads `Offer` rows (priced slots) which carry `OfferStatus.OPTION`/`OPTION_WAITING` but **no expiry** — the expiry lives in `external_reservations`. To make an expired option read FREE on `/standard-offers`, the mapper must know whether a **live** option backs each optioned offer for its exact period.

**Decision (minimal):** do not join per-offer in the mapper. Instead, in `OfferQueryingService.getYachtStandardOffers`, after loading the offers, bulk-fetch the live options for the yacht over `[dateFrom,dateTo]` (reusing the existing `findOptionsByYachtIdsAndPeriod` — already filters `optionExpiration > CURRENT_TIMESTAMP`) and pass an "is this offer's period backed by a live option" predicate into the mapper. Offers flagged `OPTION`/`OPTION_WAITING` whose period has **no** live backing → mapper emits FREE; with live backing → OPTION.

### 1.4 `OfferMapper.kt` — stop collapsing; emit effective status

**File:** `domains/catalouge/mapper/OfferMapper.kt`

- **Line 5** import: replace `import ...enums.SimpleOfferStatus` with `import ...enums.ExternalReservationStatus` + `import ...enums.toCustomerStatus`.
- **Signature** (`toDto`, :16-19): add a parameter so the caller injects live-option knowledge:
  ```kotlin
  fun toDto(
      offer: Offer,
      currency: CurrencyEnum?,
      hasLiveOption: Boolean = false,
  ): OfferDto {
  ```
- **Line 32** replace:
  ```kotlin
  status = SimpleOfferStatus.fromOfferStatus(offer.status),
  ```
  with:
  ```kotlin
  // Honest customer status: a partner OPTION/OPTION_WAITING that no longer
  // has a live external_reservations hold (optionExpiration < now) is treated
  // as FREE — the slot is bookable again. RESERVED→RESERVATION, SERVICE stays
  // SERVICE (both hard-blocked on the FE). Everything else (incl. OPTION_EXPIRED,
  // CANCELLED, INFO) → FREE.
  status =
      offer.status?.let { s ->
          val collapsed = s.toCustomerStatus()
          if (collapsed == ExternalReservationStatus.OPTION && !hasLiveOption) {
              ExternalReservationStatus.FREE
          } else {
              collapsed
          }
      } ?: ExternalReservationStatus.FREE,
  ```

### 1.5 `OfferDto.kt` — change `status` type

**File:** `domains/catalouge/dto/OfferDto.kt`
- **Line 5** import: `SimpleOfferStatus` → `ExternalReservationStatus`.
- **Line 22**: `val status: SimpleOfferStatus? = null` → `val status: ExternalReservationStatus? = null`.

`ExternalReservationStatus` is a value-bearing enum; Jackson serializes Kotlin enums by **name** by default (no `@JsonValue` on it), so the wire string becomes `"FREE"/"OPTION"/"RESERVATION"/"SERVICE"` — exactly what the FE `ReservationStatus`/`Status` enums need. **Confirm during verify (V2)** that no `ObjectMapper` global config forces enum-by-ordinal (grep `WRITE_ENUMS_USING_INDEX`).

### 1.6 `OfferQueryingService.kt` — inject live-option flag into both offer feeds

**File:** `domains/catalouge/services/OfferQueryingService.kt`

`getYachtStandardOffers` (:31-48): after fetching `offers`, bulk-resolve live options and pass the flag:

```kotlin
fun getYachtStandardOffers(
    yachtId: Long,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    currency: CurrencyEnum?,
): List<OfferDto> {
    getValidYacht(yachtId)

    val offers =
        offerRepository.findOffersByYachtIdAndDateFromAndDateToAndOfferType(
            yachtId, dateFrom, dateTo, OfferType.STANDARD,
        )

    // Live options (optionExpiration > now) overlapping the window — used to
    // demote stale OPTION offer rows to FREE. Half-open overlap is intrinsic to
    // findOptionsByYachtIdsAndPeriod (date_from <= end AND date_to >= start).
    val liveOptions =
        externalReservationRepository.findOptionsByYachtIdsAndPeriod(
            listOf(yachtId),
            ExternalReservationStatus.OPTION,
            dateFrom,
            dateTo,
        )

    return offers.map { offer ->
        val hasLiveOption =
            liveOptions.any { r ->
                // overlap of THIS offer's exact period with a live option row
                offer.dateFrom != null && offer.dateTo != null &&
                    r.dateFrom != null && r.dateTo != null &&
                    r.dateFrom!! < offer.dateTo!! && r.dateTo!! > offer.dateFrom!!
            }
        offerMapper.toDto(offer, currency, hasLiveOption)
    }
}
```

- Add constructor dep `private val externalReservationRepository: ExternalReservationRepository`.
- Add imports: `ExternalReservationStatus`, `ExternalReservationRepository`.
- `getYachtOffers` (:50-66) — same `offerMapper.toDto(it, currency)` call; the default `hasLiveOption = false` keeps it compiling. Apply the same live-option enrichment here too **only if** `/offers` feeds a user-facing gate (it currently feeds admin/cart per architecture map). For Phase 1, leave `getYachtOffers` on the default (documented), since its `status` was already collapsed identically before. **Confirm consumer (V1)**.

### 1.7 `ExternalReservationRepository.kt` — filter expired options in detail availability queries

**File:** `domains/catalouge/jpa/ExternalReservationRepository.kt`

The two queries powering `/availability` (`findYachtAvailabilityByYear` :17-27, `findYachtAvailabilityByAdjustedYearAndMonth` :29-40) return raw rows including `OPTION` rows whose `optionExpiration < now`. Those must read FREE. Two options — pick **(A)** for minimal blast radius:

**(A) Filter at the translator (keep queries as-is), demote in code.** Change `toYachtAvailabilityDto` (see 1.8) to take "now" and demote lapsed options. Simplest, no SQL risk. **Chosen.**

**(B) (alternative)** add `AND (r.status <> OPTION OR r.optionExpiration IS NULL OR r.optionExpiration > CURRENT_TIMESTAMP)` to both queries so lapsed OPTION rows are simply dropped (they fall through to FREE = absence of a row). Drops the row entirely, which also works because `YachtAvailabilityDto` consumers treat "no row" as available. Use B only if you want lapsed options gone from the payload entirely.

Keep `findOptionsByYachtIdsAndPeriod` (:52-67) unchanged — it's already correct.

### 1.8 `ExternalReservationTranslators.kt` — demote lapsed options to FREE

**File:** `domains/catalouge/services/ExternalReservationTranslators.kt` (currently :6-11)

```kotlin
package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtAvailabilityDto
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import java.time.LocalDateTime

fun ExternalReservation.toYachtAvailabilityDto(now: LocalDateTime = LocalDateTime.now()): YachtAvailabilityDto {
    // An OPTION whose hold has lapsed (optionExpiration < now) is bookable again
    // → surface as FREE. RESERVATION/SERVICE/FREE pass through untouched.
    val effective =
        if (status == ExternalReservationStatus.OPTION &&
            (optionExpiration == null || optionExpiration!!.isBefore(now))
        ) {
            ExternalReservationStatus.FREE
        } else {
            status
        }
    return YachtAvailabilityDto(from = dateFrom, to = dateTo, status = effective)
}
```

**Caller** `YachtQueryingService.getYachtAvailability` (:1051-1054): compute `now` once and pass it:
```kotlin
val now = LocalDateTime.now()
val yachtAvailability = reservations.map { it.toYachtAvailabilityDto(now) }
```
Add `import java.time.LocalDateTime`. (Edge: an OPTION row with `optionExpiration == null` — partner never sent an expiry — is treated as FREE here, consistent with `findOptionsByYachtIdsAndPeriod` which requires `optionExpiration IS NOT NULL` to count as a live option. Documented as Phase-1 conservative choice; UNKNOWN rows already pass through and the FE treats them as available.)

### 1.9 `SimpleOfferStatus.kt` — delete

**File:** `domains/catalouge/enums/SimpleOfferStatus.kt` — delete after the only two referencing files (`OfferDto.kt`, `OfferMapper.kt`) are migrated. Confirm via `grep -rln "SimpleOfferStatus" src/main/kotlin` → empty before removing. (Generated swagger files under `build/` are regenerated; ignore.)

### 1.10 Search path (`YachtQueryingService.getYachts` :154-375) — leave as-is

The card/search status already uses the honest live-option gate (`isOption` requires both `offerStatus IN (2,3)` **and** a non-null live `optionExpiryByYachtId`, :363-365). `prioritizedStatus` is internal to the search aggregation and not customer-facing per-offer status; **no change** needed for this sub-area. (The A1 ±3-day proximity bug and the search→honest-busy reconciliation are owned by the other sub-area.)

### 1.11 No migration required

No DB schema change. `external_reservations.optionExpiration` and `offer.status` already exist. No Flyway file. (Resolve-by-name rule N/A here.)

---

## 2. FRONTEND

### 2.1 `Status` enum — extend to the 4 honest states

**File:** `src/models/yacht-offer.model.ts` (:5-9)

```ts
export enum Status {
  FREE = 'FREE',
  OPTION = 'OPTION',
  RESERVATION = 'RESERVATION',
  SERVICE = 'SERVICE',
  // Retained as a fallback bucket for any legacy/unknown wire value; FE treats
  // it as hard-blocked. Backend no longer emits UNAVAILABLE for offers.
  UNAVAILABLE = 'UNAVAILABLE',
}
```

Keeping `UNAVAILABLE` in the enum means existing code that references `Status.UNAVAILABLE` still compiles; we re-point its **meaning** to "hard-blocked" and route RESERVATION/SERVICE into the same disabled branch (see helper 2.2). `OfferDto.status` now arrives as `FREE|OPTION|RESERVATION|SERVICE`.

### 2.2 NEW shared gate helper — single source of truth for the gate

**File (new):** `src/utils/static/offerStatusGate.ts`

```ts
import { Status } from '@/models/yacht-offer.model';

/** Customer can reserve directly. */
export const isReservable = (s?: Status | null): boolean => s === Status.FREE;

/** Visible + selectable, but inquiry-only (live option). */
export const isOptionInquiry = (s?: Status | null): boolean => s === Status.OPTION;

/** Hard-blocked: not selectable, no inquiry (reservation / service / legacy). */
export const isHardBlocked = (s?: Status | null): boolean =>
  s === Status.RESERVATION || s === Status.SERVICE || s === Status.UNAVAILABLE;

/**
 * Single gate decision used by ALL detail surfaces.
 * `yacht` flags are orthogonal yacht-level inquiry triggers (custom /
 * isInquireOnly === optionApproval||CUSTOM, Yacht.kt:285) — they force inquiry
 * even when the slot is FREE.
 */
export type GateMode = 'reserve' | 'inquiry' | 'blocked';

export const resolveGate = (
  status: Status | null | undefined,
  opts: { custom?: boolean; inquireOnly?: boolean }
): GateMode => {
  if (isHardBlocked(status)) return 'blocked';
  if (isOptionInquiry(status) || opts.custom || opts.inquireOnly) return 'inquiry';
  return 'reserve';
};
```

All gate sites below import from here so they can never drift again.

### 2.3 `BoatCalendarForm.tsx` — desktop reserve/inquiry gate

**File:** `src/components/BoatCalendar/BoatCalendarForm/BoatCalendarForm.tsx`

- **:18** keep `import { Status } from '@/models/yacht-offer.model';`; add `import { resolveGate } from '@/utils/static/offerStatusGate';`.
- **:64-65** replace the two ad-hoc booleans:
  ```ts
  const isSelectedOfferOption = selectedOffer?.status === Status.OPTION;
  const isSelectedOfferUnavailable = selectedOffer?.status === Status.UNAVAILABLE;
  ```
  with a single gate:
  ```ts
  const gate = resolveGate(selectedOffer?.status, { custom: yacht.custom, inquireOnly: inquireOnly });
  const isSelectedOfferOption = gate === 'inquiry' && selectedOffer?.status === Status.OPTION;
  const isSelectedOfferBlocked = gate === 'blocked';
  ```
  Then replace every remaining `isSelectedOfferUnavailable` reference (:197, :324, :339) with `isSelectedOfferBlocked`. (Semantics preserved: "not available / red box" now correctly means RESERVATION/SERVICE only, not lapsed options.)
- **:174-182** `handleReserveClick`:
  ```ts
  const handleReserveClick = () => {
    if (gate === 'blocked') return;            // defensive: button is disabled anyway
    if (gate === 'inquiry') { toggleBoatInquiryModalOpen(); return; }
    handleReservation();
  };
  ```
- **:321-328** Button `disabled` + label:
  ```tsx
  disabled={variant === 'inner' ? (!isCalculatedPrice || gate === 'blocked') : false}
  ...
  {variant === 'inner' && gate === 'reserve' ? t('reserve') : t('inquireNow')}
  ```
  (When `gate==='blocked'` the button is disabled; label falls to `inquireNow` but it's non-interactive — acceptable. Optionally guard label to show `t('notAvailable')` when blocked.)
- **:101** (`unavailableDates` builder) — **keep** `RESERVATION || SERVICE` (already correct; this is the date-picker hard-block, which must exclude exactly the two hard-blocked states). No change.

### 2.4 `BoatMobileNavigation.tsx` — mobile gate (mirror of 2.3)

**File:** `src/views/Boat/BoatMobileNavigation/BoatMobileNavigation.tsx`

- **:15** add `import { resolveGate } from '@/utils/static/offerStatusGate';`.
- **:55-58** replace:
  ```ts
  const isSelectedOfferOption = selectedOffer?.status === Status.OPTION;
  const isSelectedOfferUnavailable = selectedOffer?.status === Status.UNAVAILABLE;
  const isCalculatedPrice = ...;
  const isInquireFlow = isSelectedOfferOption || yacht.custom || yacht.inquireOnly;
  ```
  with:
  ```ts
  const gate = resolveGate(selectedOffer?.status, { custom: yacht.custom, inquireOnly: yacht.inquireOnly });
  const isCalculatedPrice = calculatedPrice && Object.keys(calculatedPrice).length > 0;
  const isInquireFlow = gate === 'inquiry';
  const isSelectedOfferBlocked = gate === 'blocked';
  ```
- **:63-71** `handleReservationClick`: add the blocked guard:
  ```ts
  const handleReservationClick = () => {
    if (isSelectedOfferBlocked) return;
    if (isInquireFlow) { toggleBoatInquiryModalOpen(); return; }
    handleReservation();
  };
  ```
- **:189-191** Button: `disabled={isSelectedOfferBlocked || (!isInquireFlow && (!isCalculatedPrice))}` and keep label `{isInquireFlow ? inquireNow : reserve}`.
- Replace the three `isSelectedOfferUnavailable` props passed to `ChangeDatesContent`/`PriceDetailsContent` (:112, :120, :148, :120) with `isSelectedOfferBlocked`. **Check the prop name** in `ChangeDatesContent.tsx` / `PriceDetailsContent.tsx` and rename the prop there too (or keep prop name `isSelectedOfferUnavailable` and just feed it the blocked boolean — minimal). Recommend: keep prop name, feed `isSelectedOfferBlocked` → zero changes in child files.

### 2.5 `AvailabilityDateSelector.tsx` — A5 bug fix (block RESERVATION **and** SERVICE)

**File:** `src/views/Boat/.../AvailabilityDateSelector/AvailabilityDateSelector.tsx`

- **:1-8** add `import { ReservationStatus } from '@/models/reservation-status.model';` (currently it compares a raw `'RESERVATION'` string literal).
- **:44-58** `unavailableDates` — replace the single-string check:
  ```ts
  if (item.status === 'RESERVATION') {
  ```
  with the two hard-blocked states (mirror BoatCalendarForm:101):
  ```ts
  if (item.status === ReservationStatus.RESERVATION || item.status === ReservationStatus.SERVICE) {
  ```
  This keeps OPTION dates selectable (they remain bookable-as-inquiry) and correctly blocks SERVICE.

### 2.6 `AvailabilitySlider.tsx` (heatmap) — status map + ANY-date scaffold

**File:** `src/views/Boat/.../LiveCalendar/AvailabilitySlider.tsx`

**(a) `mapStatus` (:47-53)** — `offer.status` is now 4-state. Map RESERVATION/SERVICE to the new distinct hard-block tier (see 2.7) vs OPTION:
```ts
const mapStatus = (s: Status | undefined): WeekData['status'] => {
  if (s === Status.OPTION) return 'option';                 // inquiry, selectable
  if (s === Status.RESERVATION) return 'booked';            // hard-block
  if (s === Status.SERVICE) return 'service';               // hard-block, distinct tier
  if (s === Status.UNAVAILABLE) return 'booked';            // legacy fallback
  return 'available';
};
```

**(b) Sat-Sat hardcode (`generateSaturdayToSaturdayPeriods` :117-118, merge :119-130)** — must render ANY published date pattern, not only Sat-Sat. Replace the scaffold-then-merge with a **published-offers-first** build:

```ts
const weeks = useMemo<WeekData[]>(() => {
  // Render exactly what the partner published (any check-in day, any length).
  // Sort by dateFrom so the strip reads chronologically. No Sat-Sat scaffold —
  // gaps are simply absent (Phase 1: honest over published periods only).
  const published = [...safeYachtOffers].sort((a, b) => a.dateFrom.localeCompare(b.dateFrom));
  return withTiers(published.map(o => toWeek(o)));
}, [safeYachtOffers]);
```

Remove the `generateSaturdayToSaturdayPeriods` import (:21) and the `Status.UNAVAILABLE` placeholder cast (:126). This eliminates the fake "booked" gap weeks that the Sat-Sat scaffold synthesized for any non-Sat-Sat fleet (NauSys collapses most to Sat-Sat, but MMK OfferType.OTHER and non-Sat-Sat partners were previously all-grey). Chunk math (`WEEKS_PER_CHUNK=26`, 18-month horizon) is unaffected — it slices whatever array length results.

> Note `toWeek` (:75) already casts `offer.status as Status`; that's fine post-2.1. `mapStatus` now reads RESERVATION/SERVICE directly.

**(c) `handleSelect` (:152-175)** — currently `if (w.status === 'booked') return;`. Extend the hard-block to the new `service` tier so SERVICE cells are non-selectable too:
```ts
if (w.status === 'booked' || w.status === 'service') return;
```
And `firstAvail` (:147): `weeks.find(w => w.status !== 'booked' && w.status !== 'service')`.

### 2.7 `tier-helpers.ts` — add the `'service'` hard-block tier

**File:** `src/views/Boat/.../LiveCalendar/parts/tier-helpers.ts`

- **:6** extend the union:
  ```ts
  export type WeekStatus = 'available' | 'option' | 'booked' | 'service' | 'selected';
  ```
- **`statusLabel` (:36-37)** add `service`:
  ```ts
  export const statusLabel = (s: WeekStatus): string =>
    s === 'booked' ? 'Reserved'
    : s === 'service' ? 'Unavailable'
    : s === 'option' ? 'Pre-reserved'
    : s === 'selected' ? 'Selected'
    : 'Available';
  ```
- **`statusColor` (:40-48)** add a distinct (grey/slate) pair for `service` so it reads as a hard-block but visually distinct from a RESERVATION:
  ```ts
  if (s === 'service') return { fg: '#475569', bg: '#E2E8F0' };
  ```

### 2.8 `HeatmapStrip.tsx` — render the `service` tier as hard-block

**File:** `src/views/Boat/.../LiveCalendar/parts/HeatmapStrip.tsx`

- **:31** add `const isService = w.status === 'service';` and `const isBlocked = isBooked || isService;`.
- **:39, :40 (onClick/disabled)**: use `isBlocked` instead of `isBooked` so SERVICE cells are non-clickable.
- **:48, :51 (background/cursor)**: `background: isBlocked ? '#F3F4F6' : tierBg(tier)`, `cursor: isBlocked ? 'not-allowed' : 'pointer'`.
- **:56-65 hatch overlay**: render for `isBooked` as today; for `isService` render a **distinct** overlay (e.g. solid slate dot grid or diagonal slate stripes) so the two hard-block reasons are visually separable:
  ```tsx
  {isService && (
    <Box sx={{ position:'absolute', inset:0, borderRadius:'6px',
      background:'repeating-linear-gradient(135deg, transparent 0 4px, rgba(71,85,105,0.18) 4px 5px)' }} />
  )}
  ```

### 2.9 `AvailabilityCard.tsx` — handle `service` like a hard-block

**File:** `src/components/AvailabilityCard/AvailabilityCard.tsx`
- **:36** `const isBooked = w.status === 'booked' || w.status === 'service';` (keeps disabled card + grey price for both hard-block reasons; `statusColor`/`statusLabel` already differentiate the pill text via 2.7).

### 2.10 `Legend.tsx` — add the `service`/Unavailable swatch

**File:** `src/views/Boat/.../LiveCalendar/parts/Legend.tsx`
- After the "Reserved" entry (:44-55), add an "Unavailable" entry with the slate diagonal swatch matching 2.8 so the legend explains the new tier.

### 2.11 `useStandardOffers.tsx` — gate by honest status (RESERVATION/SERVICE not selectable)

**File:** `src/utils/hooks/useStandardOffers.tsx`

`getPeriodStatus` currently returns `isAvailable = status===FREE`, `isOption = status===OPTION`. With the new 4-state wire this is already correct (RESERVATION/SERVICE yield both-false → chip neither available nor option → `handleChipClick` in `AvailabilityStandardOffers.tsx:141` won't select). **No logic change required**, but add a comment documenting that RESERVATION/SERVICE intentionally fall through to a non-selectable chip. (Verify the `PeriodChip` renders a sensible disabled visual for the both-false case — V6.)

### 2.12 `BoatListingItemCard.tsx` — remove DEMO scaffolding

**File:** `src/components/BoatListingItemCard/BoatListingItemCard.tsx`

Per the requirement, strip the fake/demo affordances that fabricate status/urgency (these contradict "honest availability"):
- **Demo amenities** (:205-240 `demoPool`/`pickDemoAmenities`, :251-257 fallback): drop the fallback so only real `amenityKeys` render (empty row when none). Remove `demoPool`, `pickDemoAmenities`; `demoAmenities` → `realAmenities`.
- **Demo status mix** (:283-306 `demoMap` inside `resolvedStatus`): remove the `demoMap` branch. `resolvedStatus` becomes `offerStatus ?? (isOption ? OfferStatus.OPTION : OfferStatus.FREE)`.
- **Demo social proof** (:259-264 `showSocialProof`/`interestedCount`, render :699-725): remove (fabricated "X people interested").
- **DEAL OF THE WEEK countdown** (:343-374, render :479-542): the countdown deadline is fabricated per-id (`dealDeadline = now + (24 + id%25)h`). Remove the timer + ribbon, OR (if the discount badge is genuinely backed by `listPriceEur > clientPriceEur`) keep a static "Deal" pill without the fake countdown. Recommend: remove the countdown entirely; keep the real `−{discountPercent}%` pill (:738-762) which is backed by real list price.

> This is card-level cosmetic cleanup; it does **not** affect the gate. Scope it tightly to the demo blocks above. The card's real availability badge (`isAvailable` from `resolvedStatus`, :308) keeps working off the real `offerStatus`.

### 2.13 `YachtAvailability` model & `ReservationStatus` — no change

`src/models/yacht.model.ts:151-155` `YachtAvailability.status: ReservationStatus` already covers `FREE/OPTION/RESERVATION/SERVICE/UNKNOWN/CANCELLED`. The `/availability` payload now never carries a lapsed-option OPTION (demoted to FREE in 1.8). No FE model change for `/availability`.

---

## 3. End-to-end status flow (after change)

```
Partner sync ─┬─ offer.status (OfferStatus 10-state, priced slot)
              └─ external_reservations.status (ExternalReservationStatus 5-state) + optionExpiration

DETAIL /standard-offers:
  OfferQueryingService.getYachtStandardOffers
    → bulk live-options (findOptionsByYachtIdsAndPeriod, optionExpiration>now)
    → OfferMapper.toDto(offer, hasLiveOption)
        → offer.status.toCustomerStatus()  [RESERVED→RESERVATION, SERVICE→SERVICE, OPTION→OPTION, else→FREE]
        → if OPTION && !hasLiveOption → FREE        ◄── expired option = FREE
    → OfferDto.status : ExternalReservationStatus  → JSON "FREE|OPTION|RESERVATION|SERVICE"

DETAIL /availability:
  YachtQueryingService.getYachtAvailability
    → ExternalReservation.toYachtAvailabilityDto(now)
        → OPTION && optionExpiration<now → FREE     ◄── expired option = FREE
    → YachtAvailabilityDto.status : ExternalReservationStatus

FE selectedOffer.status (from yacht.offers[0] / heatmap setselectedOffer / standard-offers)
    → resolveGate(status, {custom, inquireOnly})
        FREE        → reserve
        OPTION      → inquiry  (visible + selectable)
        RESERVATION → blocked  (not selectable, no inquiry)
        SERVICE     → blocked
    consumed identically by BoatCalendarForm (desktop) + BoatMobileNavigation (mobile)
    heatmap: option→selectable-inquiry, RESERVATION→booked, SERVICE→service (both non-selectable)
    date-pickers: RESERVATION+SERVICE excluded; OPTION dates stay pickable
```

---

## 4. Edge cases

1. **OPTION offer row, no live external_reservations backing** (stale sync): `/standard-offers` → FREE (reservable). Matches search-card behavior (`isOption` requires live backing) and `/availability` (no live OPTION row → FREE).
2. **OPTION row with `optionExpiration == null`**: treated as FREE on `/availability` (1.8) and as "not a live option" on `/standard-offers` (findOptions requires NOT NULL). Conservative, consistent. Documented.
3. **Half-open overlap**: live-option matching in `OfferQueryingService` uses `r.dateFrom < offer.dateTo && r.dateTo > offer.dateFrom` (standard half-open), matching the project convention.
4. **Yacht-level inquiry** (`custom` / `isInquireOnly = optionApproval||CUSTOM`, Yacht.kt:285): orthogonal — folded into `resolveGate` opts; forces inquiry even on FREE slots. Unchanged backend behavior; FE `yacht.custom`/`yacht.inquireOnly` still drive it.
5. **Non-Sat-Sat published slots** (OfferType.OTHER): heatmap now renders them (2.6b) instead of overlaying a fake Sat-Sat scaffold. Chunk labels derive from real `dateFromIso` so they stay correct.
6. **Legacy `UNAVAILABLE` wire value**: backend no longer emits it for offers, but the FE enum keeps it and routes it to `blocked` so any stale cached client or `/offers` consumer degrades safely.
7. **RESERVATION vs SERVICE distinct tiers**: heatmap + card + legend now separate them visually; the gate treats both as hard-block (per requirement) but the user sees *why*.
8. **`OfferStatus.UNAVAILABLE` (owner week/regatta) on a priced offer**: defaults to FREE in `toCustomerStatus`; flagged for QA count (V4). If it leaks, add the explicit `UNAVAILABLE -> RESERVATION` branch.

---

## 5. Verify approach (project tooling)

**Backend (in `boat4you-backend/boat4you-ws-main`):**
- **V0 compile + lint:** `./gradlew compileKotlin ktlintCheck` — catches the enum type change in `OfferDto`/`OfferMapper`, the deleted `SimpleOfferStatus`, new helper, translator signature. Fix any `SimpleOfferStatus` dangling ref first (`grep -rln "SimpleOfferStatus" src/main/kotlin` must be empty).
- **V1 consumer audit:** `grep -rn "getYachtOffers\b" src/main/kotlin` and trace `/offers` controller (YachtController :264+) to confirm whether it feeds a customer gate; decide `getYachtOffers` enrichment accordingly.
- **V2 enum serialization:** `grep -rn "WRITE_ENUMS_USING_INDEX\|SerializationFeature\|ObjectMapper" src/main/kotlin` to confirm enums serialize by name (so `ExternalReservationStatus` → `"FREE"` not `4`).
- **V3 smoke (journal + curl)** after deploy to cusma2/cusma3:
  - `curl -s '.../public/yachts/<slug>/standard-offers?dateFrom=YYYY-MM-DD&dateTo=YYYY-MM-DD' | jq '.[].status'` → only `FREE|OPTION|RESERVATION|SERVICE`, no `UNAVAILABLE`.
  - Pick a yacht with a known **expired** option (SQL below) and confirm its slot now returns `FREE`.
  - `curl -s '.../public/yachts/<slug>/availability?year=2026' | jq '.[].status'` → lapsed OPTION rows absent/FREE.
  - `journalctl -u boat4you.service -n 100` → no mapping/serialization exceptions.
- **V4 owner-week count (decide edge 8):**
  `psql ... -c "SELECT status, count(*) FROM offer WHERE offer_type='STANDARD' AND status IN (4,6,8,10,11) GROUP BY status;"` (user `boat4you_owner`). If non-trivial owner-week priced rows exist, add the explicit `UNAVAILABLE→RESERVATION` branch.
  Expired-option fixture: `SELECT y.slug FROM external_reservations r JOIN yacht y ON y.id=r.yacht_id WHERE r.status='OPTION' AND r.option_expiration < now() LIMIT 5;`

**Web (in `boat4you-web/boat4you-web-main`):**
- **V5 typecheck + lint:** `npx tsc --noEmit` then `npm run lint` (eslint). Confirms the `Status` enum extension, new `offerStatusGate.ts`, `WeekStatus` union (`'service'`) flows through `HeatmapStrip`/`AvailabilityCard`/`Legend`/`statusLabel`/`statusColor` exhaustively, removed `generateSaturdayToSaturdayPeriods` import in AvailabilitySlider, and the demo-block removals in `BoatListingItemCard` leave no unused imports (`AccessTime`, `useEffect`/`useState` for the countdown, etc.).
- **V6 manual (Chrome MCP / local dev :3000, per feedback_debug_approach):** on a yacht detail page —
  - FREE week → "Reserve" enabled, clicking opens reservation flow.
  - OPTION week → selectable, label "Inquire now", opens inquiry modal.
  - RESERVATION + SERVICE weeks → heatmap cell non-clickable + distinct hatch; date-picker excludes those days; the standard-offers chip non-selectable.
  - Mobile bottom bar mirrors desktop (same gate).
  - A non-Sat-Sat fleet (MMK OfferType.OTHER) renders real weeks in the heatmap (no all-grey scaffold).
  - BoatListingItemCard: no fake countdown / "X people interested" / demo amenities.

**Deploy (manual, per memory):** backend jar → cusma2 (`boat4you.service`) + cusma3 (`boat4youscheduler.service`); web → cusma1 build-on-server. No Flyway migration in this changeset.

---

## 6. File change inventory

**Backend (7 files):**
- `enums/OfferStatusUnification.kt` — NEW (`toCustomerStatus`)
- `enums/SimpleOfferStatus.kt` — DELETE
- `mapper/OfferMapper.kt` — :5 import, :16-19 sig (`hasLiveOption`), :32 status logic
- `dto/OfferDto.kt` — :5 import, :22 status type → `ExternalReservationStatus`
- `services/OfferQueryingService.kt` — ctor dep + imports; `getYachtStandardOffers` :31-48 live-option enrichment
- `services/ExternalReservationTranslators.kt` — `toYachtAvailabilityDto(now)` demote lapsed option
- `services/YachtQueryingService.kt` — :1051-1054 pass `now` (+ import)
- (`jpa/ExternalReservationRepository.kt` — unchanged with option A; optional change if option B chosen)

**Web (10 files):**
- `models/yacht-offer.model.ts` — :5-9 `Status` += RESERVATION, SERVICE
- `utils/static/offerStatusGate.ts` — NEW (gate SoT)
- `components/BoatCalendar/BoatCalendarForm/BoatCalendarForm.tsx` — :64-65,101(keep),174-182,197,321-328,339
- `views/Boat/BoatMobileNavigation/BoatMobileNavigation.tsx` — :15,55-58,63-71,112-120,148,189-191
- `views/Boat/.../AvailabilityDateSelector/AvailabilityDateSelector.tsx` — :1-8 import, :44-58 (A5 fix)
- `views/Boat/.../LiveCalendar/AvailabilitySlider.tsx` — :21(drop import),47-53,117-130(any-date),147,152-175
- `views/Boat/.../LiveCalendar/parts/tier-helpers.ts` — :6,36-37,40-48 (`service`)
- `views/Boat/.../LiveCalendar/parts/HeatmapStrip.tsx` — :31,39-65 (`service` hard-block + overlay)
- `components/AvailabilityCard/AvailabilityCard.tsx` — :36 (`service`→isBooked)
- `views/Boat/.../LiveCalendar/parts/Legend.tsx` — add Unavailable swatch
- `components/BoatListingItemCard/BoatListingItemCard.tsx` — remove demo blocks (:205-240,251-264,283-306 demoMap,343-374,479-542,699-725)
- (`utils/hooks/useStandardOffers.tsx` — no logic change; comment only)

**Absolute paths root:** backend `…/boat4you-backend/boat4you-ws-main/src/main/kotlin/hr/workspace/boat4you/domains/catalouge/…`; web `…/boat4you-web/boat4you-web-main/src/…`.