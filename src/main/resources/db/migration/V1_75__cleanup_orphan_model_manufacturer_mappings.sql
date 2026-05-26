-- Cleanup orphan external_mapping rows that point at Model/Manufacturer
-- ids deleted by the 27.4.2026 dedup pass (project_manufacturer_dedupe,
-- project_agency_yacht_dedup). The dedup script consolidated duplicate
-- Manufacturer/Model rows but did NOT rewrite mapping pointers, so:
--
--   external_mapping(type='Model', external_system_id=2)        — 866 orphans
--   external_mapping(type='Model', external_system_id=1)        — 180 orphans
--   external_mapping(type='Manufacturer', external_system_id=2) — 486 orphans
--   external_mapping(type='Manufacturer', external_system_id=1) — 436 orphans
--
-- These orphans cause NauSysYachtSyncService.getModel and
-- MmkYachtSyncService.findOrCreateModel to return null (post-30.4 fix) and
-- skip the entire yacht — yacht 13175 (Ninoa) and ~half the NauSys fleet
-- were affected. After this delete the next NauSys catalogue sync
-- (`/admin/dev/sync-catalogue` POST, dev-only, or scheduled run) repopulates Model rows
-- from the partner catalogue and yacht sync resolves them again.

DELETE FROM external_mapping
WHERE type = 'Model'
  AND NOT EXISTS (SELECT 1 FROM model m WHERE m.id = external_mapping.system_id);

DELETE FROM external_mapping
WHERE type = 'Manufacturer'
  AND NOT EXISTS (SELECT 1 FROM manufacturer m WHERE m.id = external_mapping.system_id);
