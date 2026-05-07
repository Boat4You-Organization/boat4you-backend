-- Backfill yacht.location_id for existing custom yachts.
--
-- Custom yachts (entry_type = 2) created before YachtMutationService's
-- mergeYacht learned to set yacht.location were saved with location_id
-- NULL. yacht_search_view branch 2 reads `y.location_id AS location_from`,
-- so those yachts emit a NULL location_from and never match the
-- `?did=c-{N}` search filter — they stay invisible to /search results
-- even though they have a country selected in custom_yacht_details.
--
-- Source the country from custom_yacht_details.country_id (already
-- populated correctly by the existing mergeCustomYachtDetails path).
-- Country and Location share numeric IDs (Greece = 86 in both tables),
-- so a direct copy works.
--
-- Guarded with `IS NULL` so a future re-run (or a hand-saved yacht that
-- already has location set) is left alone.
UPDATE public.yacht y
SET    location_id = cyd.country_id
FROM   public.custom_yacht_details cyd
WHERE  y.id = cyd.yacht_id
  AND  y.entry_type = 2
  AND  y.location_id IS NULL
  AND  cyd.country_id IS NOT NULL;
