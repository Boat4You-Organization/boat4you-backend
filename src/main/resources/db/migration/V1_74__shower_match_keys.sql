-- Tighten shower vs outside-shower disambiguation.
--
-- After V1_73 introduced the 9-category split, "Cockpit/stern, outside
-- shower" and "Bow shower" partner rows were still snapping to the
-- generic shower row (Equipment id 15, INTERIOR) because token-match:shower
-- triggers on the bare "shower" token regardless of what comes alongside.
-- That dropped them under "Interior" instead of the more accurate "Deck"
-- (where outside-shower lives).
--
-- Fix: shower carves out the outside variants explicitly via not:* rules,
-- and outside-shower picks them up with its own token-match list.

UPDATE equipment
SET match_keys = 'token-match:shower, not:no inside shower, not:shore connection, not:shore power, not:outside shower, not:deck shower, not:cockpit shower, not:stern shower, not:bow shower'
WHERE label_code = 'shower';

UPDATE equipment
SET match_keys = 'token-match:outside shower, token-match:deck shower, token-match:cockpit shower, token-match:stern shower, token-match:bow shower'
WHERE label_code = 'outside-shower';

-- ===== Fix already-stored yacht_equipment rows that snapped to the wrong
-- pre-V1_73 mapping. The sync layer skips equipment_id reassignment when
-- the row already has *some* equipment_id set, so these stay broken until
-- we rewire them in SQL.

-- "Water hose" historically matched water-toys (id 56, ENTERTAINMENT) via
-- the "water" token. V1_73 added a dedicated water-hose row — point all
-- partner-named "Water hose" rows there.
UPDATE yacht_equipment
SET equipment_id = (SELECT id FROM equipment WHERE label_code = 'water-hose')
WHERE name ILIKE 'water hose%' OR name ILIKE '%water hose';

-- All "outside shower" / "deck shower" / "cockpit shower" / "bow shower"
-- variants → outside-shower (DECK) instead of the generic shower (INTERIOR).
UPDATE yacht_equipment
SET equipment_id = (SELECT id FROM equipment WHERE label_code = 'outside-shower')
WHERE name ILIKE '%outside shower%'
   OR name ILIKE '%cockpit%shower%'
   OR name ILIKE '%stern%shower%'
   OR name ILIKE '%bow shower%'
   OR name ILIKE '%deck shower%';

-- "Refrigerator" rows that grabbed the freezer Equipment (rare but seen)
-- → refrigerator if it exists.
UPDATE yacht_equipment
SET equipment_id = (SELECT id FROM equipment WHERE label_code = 'refrigerator')
WHERE name ILIKE 'refrigerator%' AND equipment_id IS NULL;

-- Battery charger rows that were unmatched (equipment_id NULL) — rewire
-- to the dedicated row introduced in V1_73.
UPDATE yacht_equipment
SET equipment_id = (SELECT id FROM equipment WHERE label_code = 'battery-charger')
WHERE name ILIKE 'battery charger%' AND equipment_id IS NULL;
