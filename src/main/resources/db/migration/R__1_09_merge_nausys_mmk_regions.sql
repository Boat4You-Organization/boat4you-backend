-- Merge NauSYS location_region mappings into MMK regions so that the frontend
-- region filter (which uses MMK region IDs) also covers NauSYS locations.
--
-- NauSYS regions were created without country_code and have their own IDs.
-- MMK regions are the ones shown in the UI (they have country_code set).
-- This migration copies location_region rows from NauSYS regions into the
-- corresponding MMK regions, so a search by MMK region returns ALL yachts.
--
-- IMPORTANT: First clean up any incorrect mappings from previous versions of
-- this migration before inserting correct ones.

-- ============================================================================
-- Step 1: Remove incorrect mappings added by previous version of this migration
-- ============================================================================

-- Remove Kornati (r-101) locations from Split region (r-5) - they belong in Zadar only
DELETE FROM location_region
WHERE region_id = 5
  AND location_id IN (
    SELECT lr.location_id FROM location_region lr WHERE lr.region_id = 101
);

-- Remove specific NauSYS r-100 locations that don't belong in Split region:
-- l-33  Marina Kremik (Primošten - Šibenik area, not Split)
-- l-29  ACI Marina Dubrovnik (obviously not Split)
DELETE FROM location_region
WHERE region_id = 5
  AND location_id IN (33, 29);

-- ============================================================================
-- Step 2: r-100 (NauSYS "Split") → r-5 (MMK "Split region")
-- Exclude Marina Kremik (l-33) and ACI Dubrovnik (l-29) which NauSYS wrongly
-- includes in their "Split" region.
-- ============================================================================
INSERT INTO location_region (region_id, location_id)
SELECT 5, lr.location_id
FROM location_region lr
WHERE lr.region_id = 100
  AND lr.location_id NOT IN (33, 29)  -- exclude Kremik and Dubrovnik
  AND NOT EXISTS (
    SELECT 1 FROM location_region lr2
    WHERE lr2.region_id = 5 AND lr2.location_id = lr.location_id
)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- Step 3: r-99 (NauSYS "Dubrovnik / Montenegro") → r-6 (MMK "Dubrovnik region")
-- ============================================================================
INSERT INTO location_region (region_id, location_id)
SELECT 6, lr.location_id
FROM location_region lr
WHERE lr.region_id = 99
  AND NOT EXISTS (
    SELECT 1 FROM location_region lr2
    WHERE lr2.region_id = 6 AND lr2.location_id = lr.location_id
)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- Step 4: r-103 (NauSYS "Zadar") → r-3 (MMK "Zadar region")
-- ============================================================================
INSERT INTO location_region (region_id, location_id)
SELECT 3, lr.location_id
FROM location_region lr
WHERE lr.region_id = 103
  AND NOT EXISTS (
    SELECT 1 FROM location_region lr2
    WHERE lr2.region_id = 3 AND lr2.location_id = lr.location_id
)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- Step 5: r-101 (NauSYS "Kornati") → r-3 (MMK "Zadar region") ONLY
-- Kornati marinas (Mandalina, D-Marin Dalmacija, Kremik, Pirovac, Zadar,
-- Frapa) are all in the Šibenik/Zadar area, NOT Split.
-- ============================================================================
INSERT INTO location_region (region_id, location_id)
SELECT 3, lr.location_id
FROM location_region lr
WHERE lr.region_id = 101
  AND NOT EXISTS (
    SELECT 1 FROM location_region lr2
    WHERE lr2.region_id = 3 AND lr2.location_id = lr.location_id
)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- Step 6: r-104 (NauSYS "Istria / Kvarner") → r-2 (MMK "Kvarner") AND r-1 (MMK "Istra")
-- ============================================================================
INSERT INTO location_region (region_id, location_id)
SELECT 2, lr.location_id
FROM location_region lr
WHERE lr.region_id = 104
  AND NOT EXISTS (
    SELECT 1 FROM location_region lr2
    WHERE lr2.region_id = 2 AND lr2.location_id = lr.location_id
)
ON CONFLICT DO NOTHING;

INSERT INTO location_region (region_id, location_id)
SELECT 1, lr.location_id
FROM location_region lr
WHERE lr.region_id = 104
  AND NOT EXISTS (
    SELECT 1 FROM location_region lr2
    WHERE lr2.region_id = 1 AND lr2.location_id = lr.location_id
)
ON CONFLICT DO NOTHING;
