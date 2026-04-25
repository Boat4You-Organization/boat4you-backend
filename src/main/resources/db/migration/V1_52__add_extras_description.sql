-- V1_52: per-extras description column on both `yacht_extras` (yacht-level
-- catalogue entries, e.g. "FUN PACK A [Jokerboat Coaster 470 + 70HP; deposit
-- €1000; Croatian waters only]") and `offer_extras` (obligatory items Booking
-- Manager sends inside each offer, e.g. "Croatian Tourist Tax (1.33 € per
-- person/night)").
--
-- Motivation (23.4.2026): partner extras pages carry a free-form description
-- in brackets/parens that explains what's included, deposits, eligibility.
-- Today we store only `name` + `price`, so the broker HTML offer + the /boat
-- page show a bare line that hides the real product. MMK `ObligatoryExtra.
-- description` already exists in the client DTO but the sync mapper ignored
-- it; Nausys description arrives from the yacht-services catalogue endpoint
-- and likewise has nowhere to land today.
--
-- Nullable — backfilled by the next partner sync run. No default so we can
-- tell "never synced" apart from "partner sent empty string".

ALTER TABLE yacht_extras
    ADD COLUMN IF NOT EXISTS description TEXT;

ALTER TABLE offer_extras
    ADD COLUMN IF NOT EXISTS description TEXT;

GRANT SELECT, UPDATE ON yacht_extras TO boat4you_app;
GRANT SELECT, UPDATE ON offer_extras TO boat4you_app;
