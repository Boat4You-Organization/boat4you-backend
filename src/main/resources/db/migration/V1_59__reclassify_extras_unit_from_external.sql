-- V1_59: backfill `yacht_extras.unit` for rows where the partner's raw
-- `external_unit` token is perfectly recognizable but our mapper used to
-- collapse it to UNKNOWN.
--
-- Background (Mario flagged 23.4.2026): yacht detail page showed
-- "Not specified" next to every extra's price. Root cause was twofold:
--   1. Nausys IDs for per-hour / per-piece / per-litre / per-pack / etc.
--      were hard-mapped to UNKNOWN in ExtrasUnitType.fromNausysValue().
--      That mapper has been expanded in the paired code change — new
--      sync runs will classify correctly going forward.
--   2. MMK transmits `per_week`, `per_booking`, `per_night_person`, ...
--      (underscore-delimited), but fromMmkValue() expected spaces. ~72k
--      MMK rows sat at UNKNOWN because no branch matched.
-- Both bugs are fixed in code; this migration repairs the existing DB
-- snapshot so users see correct unit labels immediately after deploy,
-- without waiting for a full partner re-sync.
--
-- Column semantics: `unit` is JPA @Enumerated ORDINAL on ExtrasUnitType.
-- Enum ordinals (must match Kotlin order):
--   0  UNKNOWN          5  PER_BOOKING        10 PER_HOUR    15 PER_PACK    20 PER_TON
--   1  AMOUNT           6  PER_BOOKING_PERSON 11 PER_PIECE   16 PER_PET     21 PER_TRIP
--   2  PERCENTAGE       7  PER_NIGHT          12 PER_LITRE   17 PER_SET     22 PER_GB
--   3  PER_WEEK         8  PER_NIGHT_PERSON   13 PER_MEAL    18 PER_BED     23 PER_BOTTLE
--   4  PER_WEEK_PERSON  9  PER_BOAT           14 PER_NM      19 PER_TANK    24 PER_CABIN
--                                                                           25 PER_LICENCE
--
-- Scope: only touches `yacht_extras` (2+M rows; ~147k currently UNKNOWN).
-- `offer_extras.unit` is intentionally hardcoded to PER_BOOKING by the
-- offer-sync services — leave it alone. `reservation_extras` has a
-- handful of rows, not worth targeted backfill.
--
-- Guard: only rows where `unit = 0` (UNKNOWN) are touched. If a newer
-- unit has already been resolved by sync, we don't clobber it.
--
-- Deliberately NOT re-classified (partner codes with ambiguous booking
-- semantics — one-way direction, multi-week pricing, per-crew-change):
--   Nausys IDs: 101767, 126869, 485612, 485613, 555737, 23526024,
--               1174949 (half hour — also stays UNKNOWN)

GRANT SELECT, UPDATE ON yacht_extras TO boat4you_app;

-- -------- MMK underscore strings ------------------------------------
UPDATE yacht_extras SET unit = 3
    WHERE unit = 0 AND external_unit IN ('per_week', 'per_week_started');

UPDATE yacht_extras SET unit = 4
    WHERE unit = 0 AND external_unit IN ('per_week_person', 'per_week_started_person');

UPDATE yacht_extras SET unit = 5
    WHERE unit = 0 AND external_unit = 'per_booking';

UPDATE yacht_extras SET unit = 6
    WHERE unit = 0 AND external_unit = 'per_booking_person';

UPDATE yacht_extras SET unit = 7
    WHERE unit = 0 AND external_unit IN ('per_night', 'per_day');

UPDATE yacht_extras SET unit = 8
    WHERE unit = 0 AND external_unit IN ('per_night_person', 'per_day_person');

UPDATE yacht_extras SET unit = 10
    WHERE unit = 0 AND external_unit = 'per_hour';

-- -------- Nausys numeric IDs newly classified -----------------------
-- per hour / per running hour
UPDATE yacht_extras SET unit = 10
    WHERE unit = 0 AND external_unit IN ('958014', '528326');

-- per piece
UPDATE yacht_extras SET unit = 11
    WHERE unit = 0 AND external_unit = '112719';

-- per litre
UPDATE yacht_extras SET unit = 12
    WHERE unit = 0 AND external_unit = '865925';

-- per meal
UPDATE yacht_extras SET unit = 13
    WHERE unit = 0 AND external_unit = '1202520';

-- per nautical mile
UPDATE yacht_extras SET unit = 14
    WHERE unit = 0 AND external_unit = '556692';

-- per pack
UPDATE yacht_extras SET unit = 15
    WHERE unit = 0 AND external_unit = '604502';

-- per pet
UPDATE yacht_extras SET unit = 16
    WHERE unit = 0 AND external_unit = '524553';

-- per set
UPDATE yacht_extras SET unit = 17
    WHERE unit = 0 AND external_unit = '101939';

-- per single bed
UPDATE yacht_extras SET unit = 18
    WHERE unit = 0 AND external_unit = '1091408';

-- per tank
UPDATE yacht_extras SET unit = 19
    WHERE unit = 0 AND external_unit = '1176515';

-- per ton
UPDATE yacht_extras SET unit = 20
    WHERE unit = 0 AND external_unit = '1058031';

-- round trip → PER_TRIP
UPDATE yacht_extras SET unit = 21
    WHERE unit = 0 AND external_unit = '557481';

-- per GB
UPDATE yacht_extras SET unit = 22
    WHERE unit = 0 AND external_unit = '940725';

-- per bottle
UPDATE yacht_extras SET unit = 23
    WHERE unit = 0 AND external_unit = '1312513';

-- per cabin
UPDATE yacht_extras SET unit = 24
    WHERE unit = 0 AND external_unit = '525147';

-- per licence
UPDATE yacht_extras SET unit = 25
    WHERE unit = 0 AND external_unit = '974076';
