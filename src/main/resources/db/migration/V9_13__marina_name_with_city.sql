-- Marina display names: append " / {city}" so clients see WHERE each marina is
-- ("D-Marin Dalmacija Marina / Sukošan", "ACI Marina Split / Split", "Marina Frapa / Rogoznica").
--
-- Safe because location.name is display-only (never a slug/URL — verified) and the search-view
-- parser (parseYachtSearchViewLocationName, Utils.kt) takes the name as everything BETWEEN the
-- first and last dash of "<id>-<name>-<cc>", so an embedded " / " (and dashes inside the city)
-- survive intact.
--
-- Marinas without a city (~489, mostly NauSys — it doesn't return city) stay bare; populating
-- those needs a separate city-enrichment pass. Idempotent: skips names already carrying " / ".
-- On a fresh DB this is a no-op (locations are created by the sync AFTER migrations run); it
-- formats the existing prod catalogue. (New marinas: a future sync change should format on create.)

-- Undo the manual "Marina Frapa, Rogoznica" rename (V9_10) so it picks up the uniform " / " form.
UPDATE location
SET name = 'Marina Frapa'
WHERE name = 'Marina Frapa, Rogoznica';

UPDATE location
SET name = name || ' / ' || trim(city)
WHERE NULLIF(trim(city), '') IS NOT NULL
  AND position(' / ' IN name) = 0;
