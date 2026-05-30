-- Remap already-synced yacht equipment rows that the partner data labelled as a
-- watermaker/desalinator but the fuzzy name matcher (Jaro-Winkler) bucketed into
-- the wrong canonical row — overwhelmingly "Waste tank" (id 19) because of the
-- shared "wat..." prefix, or left unmatched. R__1_05 now carries stronger
-- token-match/not: keys so FUTURE syncs map correctly; this one-off fixes the
-- existing rows so the Watermaker filter (canonical id = water-maker) returns
-- them immediately. Idempotent: rows already on water-maker are skipped.
UPDATE yacht_equipment ye
SET equipment_id = (SELECT id FROM equipment WHERE label_code = 'water-maker')
WHERE (
           ye.name ILIKE '%water maker%'
        OR ye.name ILIKE '%watermaker%'
        OR ye.name ILIKE '%desalin%'
      )
  AND ye.equipment_id IS DISTINCT FROM (SELECT id FROM equipment WHERE label_code = 'water-maker');
