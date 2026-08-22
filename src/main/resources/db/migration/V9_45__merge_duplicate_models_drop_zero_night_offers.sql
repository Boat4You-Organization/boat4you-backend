-- Scheduler error sweep 22.8.2026 (Mario): two data faults behind ~50% of the
-- daily ERROR lines.
--
-- 1) Duplicate yacht models: 51 pairs share (lower(name), manufacturer_id)
--    — e.g. "Bali 4.4" ids 336 and 4043 — left over from the cabin-config
--    name normaliser. NauSysCatalogueSyncService.modelsSync looked them up
--    expecting ONE row and died with IncorrectResultSizeDataAccessException
--    4x a day (01/06/10/15h), so NauSys models/equipment/bases stopped
--    refreshing. The only FK to model is yacht.model_id; external_mapping
--    rows (type='Model') reference model.id via system_id. Keep the OLDEST
--    id of each group, repoint yachts + mappings, drop the rest.
CREATE TEMP TABLE model_dups AS
SELECT id AS dup_id,
       first_value(id) OVER (PARTITION BY lower(name), manufacturer_id ORDER BY id) AS keeper
FROM model;
DELETE FROM model_dups WHERE dup_id = keeper;

UPDATE yacht y SET model_id = d.keeper
FROM model_dups d WHERE y.model_id = d.dup_id;

-- Repoint partner mappings unless the keeper already owns that external id
-- (unique external_system_uk1 on external_id, external_system_id, system_id, type).
UPDATE external_mapping em SET system_id = d.keeper
FROM model_dups d
WHERE em.type = 'Model' AND em.system_id = d.dup_id
  AND NOT EXISTS (
      SELECT 1 FROM external_mapping x
      WHERE x.type = 'Model' AND x.external_id = em.external_id
        AND x.external_system_id = em.external_system_id AND x.system_id = d.keeper
  );
DELETE FROM external_mapping em USING model_dups d
WHERE em.type = 'Model' AND em.system_id = d.dup_id;

DELETE FROM model m USING model_dups d WHERE m.id = d.dup_id;

-- 2) 0-night offers (date_from = date_to): synthesized from same-day MMK
--    OPTION blocks; unbookable and colliding on uq_offer_yacht_week_product_route,
--    which failed whole agency-year availability syncs "after retries". The
--    sync code now skips such blocks; drop the 16 existing rows (none is
--    referenced by a reservation flow — guarded anyway).
DELETE FROM offer o
WHERE o.date_from = o.date_to
  AND NOT EXISTS (SELECT 1 FROM reservation_flow rf WHERE rf.offer_id = o.id);
