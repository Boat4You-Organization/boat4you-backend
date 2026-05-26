-- V1_90: Migrate all @Enumerated ORDINAL columns to STRING storage.
--
-- Pre-prod data-integrity fix (F2-018, F2-019). Author had a manual rule
-- (see comment in ExtrasUnitType.kt: "New values MUST be appended at the
-- end — the column is @Enumerated (ORDINAL)") that this migration makes
-- structurally enforceable. With STRING storage, reordering or deleting
-- an enum constant becomes a compile-time concern at most, not a silent
-- data-corruption bomb in prod.
--
-- Pattern per column:
--   1. Drop dependent views (re-created by R__ after this migration).
--   2. ALTER COLUMN smallint → varchar(31), CASE-mapped from declaration
--      ordinal to enum.name(). Each enum's mapping mirrors the Kotlin
--      class declaration order at the time of this migration.
--
-- Run order in Flyway:
--   V1_xx ... V1_89 → V1_90 (this) → R__1_02_reservation_view.sql
--                                  → R__1_03_yacht_search_view.sql
--                                  → R__1_05_equipment_import.sql
--                                  → other R__'s (no enum coupling)

-- ============================================================
-- 1. Drop views that read enum columns. Will be recreated by R__'s.
-- ============================================================
DROP VIEW IF EXISTS public.yacht_search_view CASCADE;
DROP VIEW IF EXISTS public.reservation_view CASCADE;

-- ============================================================
-- 2. yacht  (4 enum columns: entry_type, vessel_type, mainsail_type, genoa_type)
-- ============================================================

-- EntryType: UNKNOWN(0), EXTERNAL(1), CUSTOM(2)
ALTER TABLE yacht ALTER COLUMN entry_type DROP DEFAULT;
ALTER TABLE yacht ALTER COLUMN entry_type TYPE VARCHAR(31) USING (
    CASE entry_type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'EXTERNAL'
        WHEN 2 THEN 'CUSTOM'
        ELSE 'UNKNOWN'
    END);

-- VesselType: 13 values
ALTER TABLE yacht ALTER COLUMN vessel_type DROP DEFAULT;
ALTER TABLE yacht ALTER COLUMN vessel_type TYPE VARCHAR(31) USING (
    CASE vessel_type
        WHEN 0 THEN 'OTHER'
        WHEN 1 THEN 'CATAMARAN'
        WHEN 2 THEN 'GULET'
        WHEN 3 THEN 'HOUSE_BOAT'
        WHEN 4 THEN 'LUXURY_MOTOR_YACHT'
        WHEN 5 THEN 'MINI_CRUISER'
        WHEN 6 THEN 'MOTORBOAT'
        WHEN 7 THEN 'MOTOR_YACHT'
        WHEN 8 THEN 'MOTORSAILER'
        WHEN 9 THEN 'POWER_CATAMARAN'
        WHEN 10 THEN 'SAILING_YACHT'
        WHEN 11 THEN 'TRIMARAN'
        WHEN 12 THEN 'RUBBER_BOAT'
        ELSE 'OTHER'
    END);

-- SailTypeEnum: UNKNOWN(0), CLASSIC_SAIL(1), ROLLING_SAIL(2)
ALTER TABLE yacht ALTER COLUMN mainsail_type DROP DEFAULT;
ALTER TABLE yacht ALTER COLUMN mainsail_type TYPE VARCHAR(31) USING (
    CASE mainsail_type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'CLASSIC_SAIL'
        WHEN 2 THEN 'ROLLING_SAIL'
        ELSE 'UNKNOWN'
    END);

ALTER TABLE yacht ALTER COLUMN genoa_type DROP DEFAULT;
ALTER TABLE yacht ALTER COLUMN genoa_type TYPE VARCHAR(31) USING (
    CASE genoa_type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'CLASSIC_SAIL'
        WHEN 2 THEN 'ROLLING_SAIL'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 3. offer  (3 enum columns: status, type, product)
-- ============================================================

-- OfferStatus: 10 values
ALTER TABLE offer ALTER COLUMN status DROP DEFAULT;
ALTER TABLE offer ALTER COLUMN status TYPE VARCHAR(31) USING (
    CASE status
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'FREE'
        WHEN 2 THEN 'OPTION'
        WHEN 3 THEN 'OPTION_WAITING'
        WHEN 4 THEN 'UNAVAILABLE'
        WHEN 5 THEN 'RESERVED'
        WHEN 6 THEN 'CANCELLED'
        WHEN 7 THEN 'SERVICE'
        WHEN 8 THEN 'OPTION_EXPIRED'
        WHEN 9 THEN 'INFO'
        ELSE 'UNKNOWN'
    END);

-- OfferType: UNKNOWN(0), STANDARD(1), OTHER(2)
ALTER TABLE offer ALTER COLUMN type DROP DEFAULT;
ALTER TABLE offer ALTER COLUMN type TYPE VARCHAR(31) USING (
    CASE type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'STANDARD'
        WHEN 2 THEN 'OTHER'
        ELSE 'UNKNOWN'
    END);

-- CharterType: UNKNOWN(0), BAREBOAT(1), CREWED(2), CRUISE(3), ALL_INCLUSIVE(4)
ALTER TABLE offer ALTER COLUMN product DROP DEFAULT;
ALTER TABLE offer ALTER COLUMN product TYPE VARCHAR(31) USING (
    CASE product
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'BAREBOAT'
        WHEN 2 THEN 'CREWED'
        WHEN 3 THEN 'CRUISE'
        WHEN 4 THEN 'ALL_INCLUSIVE'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 4. offer_extras  (2: unit, payment_type)
-- ============================================================

-- ExtrasUnitType: 26 values (UNKNOWN..PER_LICENCE)
ALTER TABLE offer_extras ALTER COLUMN unit DROP DEFAULT;
ALTER TABLE offer_extras ALTER COLUMN unit TYPE VARCHAR(31) USING (
    CASE unit
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'AMOUNT'
        WHEN 2 THEN 'PERCENTAGE'
        WHEN 3 THEN 'PER_WEEK'
        WHEN 4 THEN 'PER_WEEK_PERSON'
        WHEN 5 THEN 'PER_BOOKING'
        WHEN 6 THEN 'PER_BOOKING_PERSON'
        WHEN 7 THEN 'PER_NIGHT'
        WHEN 8 THEN 'PER_NIGHT_PERSON'
        WHEN 9 THEN 'PER_BOAT'
        WHEN 10 THEN 'PER_HOUR'
        WHEN 11 THEN 'PER_PIECE'
        WHEN 12 THEN 'PER_LITRE'
        WHEN 13 THEN 'PER_MEAL'
        WHEN 14 THEN 'PER_NM'
        WHEN 15 THEN 'PER_PACK'
        WHEN 16 THEN 'PER_PET'
        WHEN 17 THEN 'PER_SET'
        WHEN 18 THEN 'PER_BED'
        WHEN 19 THEN 'PER_TANK'
        WHEN 20 THEN 'PER_TON'
        WHEN 21 THEN 'PER_TRIP'
        WHEN 22 THEN 'PER_GB'
        WHEN 23 THEN 'PER_BOTTLE'
        WHEN 24 THEN 'PER_CABIN'
        WHEN 25 THEN 'PER_LICENCE'
        ELSE 'UNKNOWN'
    END);

-- ExtraPaymentType: INCLUDED(0), WITH_BOOKING(1), ADVANCE_TO_OPERATOR(2), ON_SITE(3)
ALTER TABLE offer_extras ALTER COLUMN payment_type DROP DEFAULT;
ALTER TABLE offer_extras ALTER COLUMN payment_type TYPE VARCHAR(31) USING (
    CASE payment_type
        WHEN 0 THEN 'INCLUDED'
        WHEN 1 THEN 'WITH_BOOKING'
        WHEN 2 THEN 'ADVANCE_TO_OPERATOR'
        WHEN 3 THEN 'ON_SITE'
        ELSE NULL
    END);

-- ============================================================
-- 5. yacht_extras  (3: unit, type, payment_type)
-- ============================================================

ALTER TABLE yacht_extras ALTER COLUMN unit DROP DEFAULT;
ALTER TABLE yacht_extras ALTER COLUMN unit TYPE VARCHAR(31) USING (
    CASE unit
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'AMOUNT'
        WHEN 2 THEN 'PERCENTAGE'
        WHEN 3 THEN 'PER_WEEK'
        WHEN 4 THEN 'PER_WEEK_PERSON'
        WHEN 5 THEN 'PER_BOOKING'
        WHEN 6 THEN 'PER_BOOKING_PERSON'
        WHEN 7 THEN 'PER_NIGHT'
        WHEN 8 THEN 'PER_NIGHT_PERSON'
        WHEN 9 THEN 'PER_BOAT'
        WHEN 10 THEN 'PER_HOUR'
        WHEN 11 THEN 'PER_PIECE'
        WHEN 12 THEN 'PER_LITRE'
        WHEN 13 THEN 'PER_MEAL'
        WHEN 14 THEN 'PER_NM'
        WHEN 15 THEN 'PER_PACK'
        WHEN 16 THEN 'PER_PET'
        WHEN 17 THEN 'PER_SET'
        WHEN 18 THEN 'PER_BED'
        WHEN 19 THEN 'PER_TANK'
        WHEN 20 THEN 'PER_TON'
        WHEN 21 THEN 'PER_TRIP'
        WHEN 22 THEN 'PER_GB'
        WHEN 23 THEN 'PER_BOTTLE'
        WHEN 24 THEN 'PER_CABIN'
        WHEN 25 THEN 'PER_LICENCE'
        ELSE 'UNKNOWN'
    END);

-- ExtrasType: UNKNOWN(0), EXTRAS(1), EQUIPMENT(2)
ALTER TABLE yacht_extras ALTER COLUMN type DROP DEFAULT;
ALTER TABLE yacht_extras ALTER COLUMN type TYPE VARCHAR(31) USING (
    CASE type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'EXTRAS'
        WHEN 2 THEN 'EQUIPMENT'
        ELSE 'UNKNOWN'
    END);

ALTER TABLE yacht_extras ALTER COLUMN payment_type DROP DEFAULT;
ALTER TABLE yacht_extras ALTER COLUMN payment_type TYPE VARCHAR(31) USING (
    CASE payment_type
        WHEN 0 THEN 'INCLUDED'
        WHEN 1 THEN 'WITH_BOOKING'
        WHEN 2 THEN 'ADVANCE_TO_OPERATOR'
        WHEN 3 THEN 'ON_SITE'
        ELSE NULL
    END);

-- ============================================================
-- 6. reservation  (3: status, sys_status, product)
-- ============================================================

-- OfferStatus (same enum reused)
ALTER TABLE reservation ALTER COLUMN status DROP DEFAULT;
ALTER TABLE reservation ALTER COLUMN status TYPE VARCHAR(31) USING (
    CASE status
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'FREE'
        WHEN 2 THEN 'OPTION'
        WHEN 3 THEN 'OPTION_WAITING'
        WHEN 4 THEN 'UNAVAILABLE'
        WHEN 5 THEN 'RESERVED'
        WHEN 6 THEN 'CANCELLED'
        WHEN 7 THEN 'SERVICE'
        WHEN 8 THEN 'OPTION_EXPIRED'
        WHEN 9 THEN 'INFO'
        ELSE 'UNKNOWN'
    END);

-- ReservationStatus: UNKNOWN(0), OPTION(1), RESERVATION(2), CANCELLED(3), OPTION_WAITING(4)
ALTER TABLE reservation ALTER COLUMN sys_status DROP DEFAULT;
ALTER TABLE reservation ALTER COLUMN sys_status TYPE VARCHAR(31) USING (
    CASE sys_status
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'OPTION'
        WHEN 2 THEN 'RESERVATION'
        WHEN 3 THEN 'CANCELLED'
        WHEN 4 THEN 'OPTION_WAITING'
        ELSE 'UNKNOWN'
    END);

-- CharterType (same enum reused)
ALTER TABLE reservation ALTER COLUMN product DROP DEFAULT;
ALTER TABLE reservation ALTER COLUMN product TYPE VARCHAR(31) USING (
    CASE product
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'BAREBOAT'
        WHEN 2 THEN 'CREWED'
        WHEN 3 THEN 'CRUISE'
        WHEN 4 THEN 'ALL_INCLUSIVE'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 7. reservation_flow  (1: status)
-- ============================================================

-- ReservationFlowStatus: UNKNOWN(0), IN_PROGRESS(1), DONE(2), ABANDONED(3)
ALTER TABLE reservation_flow ALTER COLUMN status DROP DEFAULT;
ALTER TABLE reservation_flow ALTER COLUMN status TYPE VARCHAR(31) USING (
    CASE status
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'IN_PROGRESS'
        WHEN 2 THEN 'DONE'
        WHEN 3 THEN 'ABANDONED'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 8. reservation_extras  (1: unit) — ExtrasUnitType
-- ============================================================

ALTER TABLE reservation_extras ALTER COLUMN unit DROP DEFAULT;
ALTER TABLE reservation_extras ALTER COLUMN unit TYPE VARCHAR(31) USING (
    CASE unit
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'AMOUNT'
        WHEN 2 THEN 'PERCENTAGE'
        WHEN 3 THEN 'PER_WEEK'
        WHEN 4 THEN 'PER_WEEK_PERSON'
        WHEN 5 THEN 'PER_BOOKING'
        WHEN 6 THEN 'PER_BOOKING_PERSON'
        WHEN 7 THEN 'PER_NIGHT'
        WHEN 8 THEN 'PER_NIGHT_PERSON'
        WHEN 9 THEN 'PER_BOAT'
        WHEN 10 THEN 'PER_HOUR'
        WHEN 11 THEN 'PER_PIECE'
        WHEN 12 THEN 'PER_LITRE'
        WHEN 13 THEN 'PER_MEAL'
        WHEN 14 THEN 'PER_NM'
        WHEN 15 THEN 'PER_PACK'
        WHEN 16 THEN 'PER_PET'
        WHEN 17 THEN 'PER_SET'
        WHEN 18 THEN 'PER_BED'
        WHEN 19 THEN 'PER_TANK'
        WHEN 20 THEN 'PER_TON'
        WHEN 21 THEN 'PER_TRIP'
        WHEN 22 THEN 'PER_GB'
        WHEN 23 THEN 'PER_BOTTLE'
        WHEN 24 THEN 'PER_CABIN'
        WHEN 25 THEN 'PER_LICENCE'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 9. external_reservation_extras  (1: unit) — QuantityUnit
-- ============================================================

-- QuantityUnit: 22 values UNKNOWN..PER_NIGHT_PERSON
ALTER TABLE external_reservation_extras ALTER COLUMN unit DROP DEFAULT;
ALTER TABLE external_reservation_extras ALTER COLUMN unit TYPE VARCHAR(31) USING (
    CASE unit
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'EMPTY'
        WHEN 2 THEN 'PIECES'
        WHEN 3 THEN 'METER'
        WHEN 4 THEN 'CENTIMETER'
        WHEN 5 THEN 'MILIMETER'
        WHEN 6 THEN 'SQUARE_METER'
        WHEN 7 THEN 'SQUARE_CENTIMETER'
        WHEN 8 THEN 'SQUARE_MILIMETER'
        WHEN 9 THEN 'LITER'
        WHEN 10 THEN 'KILOGRAM'
        WHEN 11 THEN 'GRAM'
        WHEN 12 THEN 'HOURS'
        WHEN 13 THEN 'MINUTES'
        WHEN 14 THEN 'PERCENTAGE'
        WHEN 15 THEN 'PER_NIGHT'
        WHEN 16 THEN 'PER_BOOKING'
        WHEN 17 THEN 'PER_BOOKING_PERSON'
        WHEN 18 THEN 'PER_BOAT'
        WHEN 19 THEN 'PER_WEEK'
        WHEN 20 THEN 'PER_WEEK_PERSON'
        WHEN 21 THEN 'PER_NIGHT_PERSON'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 10. external_reservations  (1: status)
-- ============================================================

-- ExternalReservationStatus: UNKNOWN(0), OPTION(1), RESERVATION(2), SERVICE(3), FREE(4)
ALTER TABLE external_reservations ALTER COLUMN status DROP DEFAULT;
ALTER TABLE external_reservations ALTER COLUMN status TYPE VARCHAR(31) USING (
    CASE status
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'OPTION'
        WHEN 2 THEN 'RESERVATION'
        WHEN 3 THEN 'SERVICE'
        WHEN 4 THEN 'FREE'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 11. external_equipment  (1: type)
-- ============================================================

-- ExternalEquipmentType: UNKNOWN(0), EQUIPMENT(1), SERVICE(2)
ALTER TABLE external_equipment ALTER COLUMN type DROP DEFAULT;
ALTER TABLE external_equipment ALTER COLUMN type TYPE VARCHAR(31) USING (
    CASE type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'EQUIPMENT'
        WHEN 2 THEN 'SERVICE'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 12. equipment  (1: category)
-- ============================================================

-- CategoryEnum: 11 values; legacy 3-bucket prefix preserved at original
-- ordinals (see CategoryEnum.kt comment).
ALTER TABLE equipment ALTER COLUMN category DROP DEFAULT;
ALTER TABLE equipment ALTER COLUMN category TYPE VARCHAR(31) USING (
    CASE category
        WHEN 0 THEN 'SALOON_AND_CABINS'
        WHEN 1 THEN 'NAVIGATION_AND_SAFETY'
        WHEN 2 THEN 'ENTERTAINMENT'
        WHEN 3 THEN 'COMFORT'
        WHEN 4 THEN 'DECK'
        WHEN 5 THEN 'GALLEY'
        WHEN 6 THEN 'INTERIOR'
        WHEN 7 THEN 'NAVIGATION'
        WHEN 8 THEN 'SAFETY'
        WHEN 9 THEN 'SAILS'
        WHEN 10 THEN 'YACHT_ELECTRICS'
        ELSE 'SALOON_AND_CABINS'
    END);

-- ============================================================
-- 13. inquiry  (1: status)
-- ============================================================

-- InquiryStatus: NEW(0), ANSWERED(1), ARCHIVED(2)
ALTER TABLE inquiry ALTER COLUMN status DROP DEFAULT;
ALTER TABLE inquiry ALTER COLUMN status TYPE VARCHAR(31) USING (
    CASE status
        WHEN 0 THEN 'NEW'
        WHEN 1 THEN 'ANSWERED'
        WHEN 2 THEN 'ARCHIVED'
        ELSE 'NEW'
    END);

-- ============================================================
-- 14. yacht_translations  (1: type)
-- ============================================================

-- TranslationType: DESCRIPTION(0), HIGHLIGHTS(1)
ALTER TABLE yacht_translations ALTER COLUMN type DROP DEFAULT;
ALTER TABLE yacht_translations ALTER COLUMN type TYPE VARCHAR(31) USING (
    CASE type
        WHEN 0 THEN 'DESCRIPTION'
        WHEN 1 THEN 'HIGHLIGHTS'
        ELSE 'DESCRIPTION'
    END);

-- ============================================================
-- 15. yacht_charter_type  (1: type)
-- ============================================================

-- CharterType (same as offer.product / reservation.product)
ALTER TABLE yacht_charter_type ALTER COLUMN type DROP DEFAULT;
ALTER TABLE yacht_charter_type ALTER COLUMN type TYPE VARCHAR(31) USING (
    CASE type
        WHEN 0 THEN 'UNKNOWN'
        WHEN 1 THEN 'BAREBOAT'
        WHEN 2 THEN 'CREWED'
        WHEN 3 THEN 'CRUISE'
        WHEN 4 THEN 'ALL_INCLUSIVE'
        ELSE 'UNKNOWN'
    END);

-- ============================================================
-- 16. service_call_cache  (1: method)
-- ============================================================

-- MethodCacheEnum: OFFER(0)..SCHEDULED_MMK_CATALOGUE_SYNC(8)
ALTER TABLE service_call_cache ALTER COLUMN method DROP DEFAULT;
ALTER TABLE service_call_cache ALTER COLUMN method TYPE VARCHAR(63) USING (
    CASE method
        WHEN 0 THEN 'OFFER'
        WHEN 1 THEN 'YACHT_SEARCH'
        WHEN 2 THEN 'SCHEDULED_NAUSYS_YACHT_SYNC'
        WHEN 3 THEN 'SCHEDULED_NAUSYS_YACHT_OFFER'
        WHEN 4 THEN 'SCHEDULED_NAUSYS_CATALOGUE_SYNC'
        WHEN 5 THEN 'SCHEDULED_MMK_YACHT_SYNC'
        WHEN 6 THEN 'SCHEDULED_MMK_YACHT_LANG_SYNC'
        WHEN 7 THEN 'SCHEDULED_MMK_YACHT_OFFER'
        WHEN 8 THEN 'SCHEDULED_MMK_CATALOGUE_SYNC'
        ELSE 'OFFER'
    END);
