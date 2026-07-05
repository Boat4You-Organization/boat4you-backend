-- Boat4You Trip phase 2: ACI Marina Split (location 69) is the only marina
-- referenced by reservations with NULL coordinates (checked in prod
-- 5.7.2026), which hides the trip-hub weather card and the weather line in
-- day-of pushes. Partner sync never writes location.lat/lon, so a one-time
-- backfill sticks. Coordinates: 43°30'8.6"N 16°25'46.3"E (ACI official).
-- The other ~2k coordinate-less locations have no reservations — the hub
-- degrades gracefully there, no blanket geocoding by design.
UPDATE location
SET lat = 43.5024, lon = 16.4295
WHERE id = 69
  AND name = 'ACI Marina Split'
  AND lat IS NULL
  AND lon IS NULL;
