-- V1_57: split overloaded `payable_in_base` boolean into a 4-state enum
-- on `offer_extras` and `yacht_extras`.
--
-- Background (Mario flagged 23.4.2026): frontend renders ALL items with
-- `payable_in_base=true` under a single header "Paid at marina". That label
-- is wrong for ~80% of those rows — APA, Skipper, Hostess, Cook, equipment
-- rentals are all BANK-TRANSFERRED to the operator BEFORE embarkation,
-- never paid at the marina. Only true on-site items (fuel, transit log,
-- mooring, tourist tax) belong under "Paid at marina".
--
-- New `payment_type` (smallint matching ExtraPaymentType enum):
--   0 = INCLUDED            (priceEur == 0 → free)
--   1 = WITH_BOOKING        (payable_in_base == false → settled with base)
--   2 = ADVANCE_TO_OPERATOR (default for payable_in_base=true non-onsite)
--   3 = ON_SITE             (name matches on-site regex below)
--
-- Backfill keeps the heuristic close to the Kotlin classifier in
-- ExtraPaymentType.classify() — keep both regexes in sync if we ever
-- expand on-site detection (add e.g. "harbour" UK spelling).
--
-- payable_in_base column is KEPT as-is — it remains the partner's raw
-- input + drives sync logic in services that haven't migrated yet. The
-- new column is the customer-facing source of truth.

ALTER TABLE offer_extras
    ADD COLUMN IF NOT EXISTS payment_type SMALLINT;

ALTER TABLE yacht_extras
    ADD COLUMN IF NOT EXISTS payment_type SMALLINT;

GRANT SELECT, UPDATE ON offer_extras TO boat4you_app;
GRANT SELECT, UPDATE ON yacht_extras TO boat4you_app;

-- Backfill offer_extras (3.3M rows expected). Uses the same priority order
-- as ExtraPaymentType.classify().
UPDATE offer_extras
SET payment_type =
    CASE
        WHEN price IS NULL OR price = 0 THEN 0  -- INCLUDED
        WHEN payable_in_base = FALSE THEN 1     -- WITH_BOOKING
        WHEN LOWER(COALESCE(name,'')) ~ '(tourist tax|transit log|fuel|gas|mooring|marina fee|port fee|harbor)' THEN 3  -- ON_SITE
        ELSE 2  -- ADVANCE_TO_OPERATOR
    END
WHERE payment_type IS NULL;

-- Same backfill for yacht_extras.
UPDATE yacht_extras
SET payment_type =
    CASE
        WHEN price IS NULL OR price = 0 THEN 0
        WHEN payable_in_base = FALSE THEN 1
        WHEN LOWER(COALESCE(name,'')) ~ '(tourist tax|transit log|fuel|gas|mooring|marina fee|port fee|harbor)' THEN 3
        ELSE 2
    END
WHERE payment_type IS NULL;
