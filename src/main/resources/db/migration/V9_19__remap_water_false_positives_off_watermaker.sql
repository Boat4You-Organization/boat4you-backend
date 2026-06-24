-- Un-tag the false positives that the fuzzy equipment matcher wrongly bucketed
-- onto canonical #20 "Water maker" (label_code = 'water-maker').
--
-- Root cause: Matchers.extrasNameMatch (Jaro-Winkler, threshold 0.9) treats a
-- token-match key token as "present" when ANY partner name token scores >= 0.9.
-- The key `token-match:watermaker` carried a single token "watermaker", and
-- JW("watermaker","water") = 0.9000 EXACTLY → so every partner equipment whose
-- name contained the bare word "water" was tagged as a watermaker. Observed live
-- on water-maker (#20): "Black Water Tank" (1810), "Water heater" (382),
-- "Water pump" (320), "Pressurized water system" (215), "Canister for water"
-- (208), "Extra water tank" (183), "Water skis"/"Water toys", + stray singles.
-- R__1_05 now uses precise keys (token-match:water maker / full-match:watermaker
-- / token-match:desalinator / token-match:desalination) so FUTURE syncs are
-- correct; this one-off fixes the already-synced rows on ALL boats.
--
-- A genuine watermaker name always contains "water maker", "watermaker", or a
-- "desalin*" stem. Everything else currently on the water-maker row is a false
-- positive → set equipment_id = NULL. The raw partner amenity name still renders
-- (an un-mapped amenity is shown as-is, e.g. "LED outdoor ambiance lights"); it
-- simply no longer claims to be a water maker and drops out of that filter. The
-- nightly sync (now with the fixed matcher) re-maps the legit ones (e.g. "Water
-- skis" -> #55, "Water toys" -> #56) to their correct equipment.
--
-- cusma4 runs BACKUP=NONE, so snapshot the touched rows first (manual rollback:
-- UPDATE yacht_equipment ye SET equipment_id = b.equipment_id FROM
-- _watermaker_falsepos_backup b WHERE b.id = ye.id). Idempotent + defensive.

DO $$
DECLARE
    wm_id    BIGINT;
    n_rows   BIGINT;
BEGIN
    SELECT id INTO wm_id FROM equipment WHERE label_code = 'water-maker';

    IF wm_id IS NULL THEN
        RAISE NOTICE 'water-maker equipment row not found; skipping remap';
        RETURN;
    END IF;

    -- Snapshot the false-positive rows (reversible).
    CREATE TABLE IF NOT EXISTS _watermaker_falsepos_backup AS
        SELECT ye.*, now() AS backed_up_at
        FROM yacht_equipment ye
        WHERE ye.equipment_id = wm_id
          AND ye.name IS NOT NULL
          AND NOT (
                  ye.name ILIKE '%water maker%'
               OR ye.name ILIKE '%watermaker%'
               OR ye.name ILIKE '%desalin%'
              );

    -- Un-tag them.
    UPDATE yacht_equipment ye
    SET equipment_id = NULL
    WHERE ye.equipment_id = wm_id
      AND ye.name IS NOT NULL
      AND NOT (
              ye.name ILIKE '%water maker%'
           OR ye.name ILIKE '%watermaker%'
           OR ye.name ILIKE '%desalin%'
          );

    GET DIAGNOSTICS n_rows = ROW_COUNT;
    RAISE NOTICE 'water-maker false-positive remap: % rows un-tagged', n_rows;
END $$;
