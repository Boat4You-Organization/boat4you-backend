-- Stronger backfill for custom yacht.location_id.
--
-- V1_69 used custom_yacht_details.country_id but that column is nullable
-- and historically wasn't populated for older custom yachts (only the
-- mandatory `country_key` text column was). This migration falls back to
-- parsing country_key ("c-86" → 86) so any custom yacht that escaped V1_69
-- still gets its location_id set, and yacht_search_view can finally surface
-- it under the matching `?did=c-{N}` filter.
--
-- country_key is NOT NULL on the column so the WHERE clause can rely on
-- the LIKE match without an extra null guard. Case-insensitive prefix
-- handling because the wrapper convention has wandered between c- and C-
-- in different code paths.
UPDATE public.yacht y
SET    location_id = REPLACE(LOWER(cyd.country_key), 'c-', '')::bigint
FROM   public.custom_yacht_details cyd
WHERE  y.id = cyd.yacht_id
  AND  y.entry_type = 2
  AND  y.location_id IS NULL
  AND  LOWER(cyd.country_key) LIKE 'c-%'
  AND  REPLACE(LOWER(cyd.country_key), 'c-', '') ~ '^[0-9]+$';
