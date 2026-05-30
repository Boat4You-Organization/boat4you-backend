-- Remap already-synced yacht equipment rows that the partner data labelled as a
-- watermaker/desalinator but the fuzzy name matcher (Jaro-Winkler) bucketed into
-- the wrong canonical row. Observed live: partner "Water maker" -> "Waste tank"
-- (id 19, shared "wat..." prefix), and NauSys "Watermaker - desalinator" left
-- unmatched (equipment_id NULL). R__1_05 now carries stronger token-match/not:
-- keys so FUTURE syncs map correctly; this one-off fixes the existing rows so the
-- Watermaker amenity filter (canonical label_code = 'water-maker') returns them.
--
-- cusma4 runs BACKUP=NONE, so snapshot the rows we touch into a backup table
-- first (kept for manual rollback). Idempotent + defensive: only rows currently
-- on waste-tank or unmatched are moved, so a correctly-mapped row is never stolen.

DO $$
DECLARE
    wm_id  BIGINT;
    wt_id  BIGINT;
BEGIN
    SELECT id INTO wm_id FROM equipment WHERE label_code = 'water-maker';
    SELECT id INTO wt_id FROM equipment WHERE label_code = 'waste-tank';

    -- Nothing to do if the canonical row is missing (shouldn't happen — R__1_05).
    IF wm_id IS NULL THEN
        RAISE NOTICE 'water-maker equipment row not found; skipping remap';
        RETURN;
    END IF;

    -- Backup the affected rows (reversible: restore equipment_id from here).
    CREATE TABLE IF NOT EXISTS _watermaker_remap_backup AS
        SELECT ye.*, now() AS backed_up_at
        FROM yacht_equipment ye
        WHERE (
                  ye.name ILIKE '%water maker%'
               OR ye.name ILIKE '%watermaker%'
               OR ye.name ILIKE '%desalin%'
              )
          AND (ye.equipment_id = wt_id OR ye.equipment_id IS NULL);

    -- Remap onto the canonical water-maker row.
    UPDATE yacht_equipment ye
    SET equipment_id = wm_id
    WHERE (
              ye.name ILIKE '%water maker%'
           OR ye.name ILIKE '%watermaker%'
           OR ye.name ILIKE '%desalin%'
          )
      AND (ye.equipment_id = wt_id OR ye.equipment_id IS NULL);
END $$;
