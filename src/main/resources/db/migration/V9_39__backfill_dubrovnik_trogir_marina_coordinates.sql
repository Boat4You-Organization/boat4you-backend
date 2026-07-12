-- Boat4You Trip: two more marinas referenced by ACTIVE trips carry NULL
-- coordinates, which hides the trip-hub weather card and the weather line in
-- day-of pushes (same symptom V9_31 fixed for ACI Split). Partner sync never
-- writes location.lat/lon, so a one-time backfill sticks. Found in prod
-- 12.7.2026 (Mario: "trip weather missing") via:
--   SELECT l.id,l.name FROM location l JOIN reservation r ON r.location_from = l.id
--   WHERE (l.lat IS NULL OR l.lon IS NULL) AND r.trip_token IS NOT NULL;
-- Coordinates from marine sources (harbourmaps / SailingClick):
--   611 ACI Marina Dubrovnik (Komolac):   42°40'13.6"N 18°07'28.6"E
--    60 Trogir, Marina Trogir (ex.SCT), Čiovo: 43°30'40"N 16°14'40"E
-- The other ~2k coordinate-less locations have no reservations — the hub
-- degrades gracefully there, no blanket geocoding by design (see V9_31).
UPDATE location
SET lat = 42.6709, lon = 18.1260
WHERE id = 611
  AND name = 'ACI Marina Dubrovnik'
  AND lat IS NULL
  AND lon IS NULL;

UPDATE location
SET lat = 43.5111, lon = 16.2444
WHERE id = 60
  AND name = 'Trogir, Marina Trogir (ex.SCT)'
  AND lat IS NULL
  AND lon IS NULL;
