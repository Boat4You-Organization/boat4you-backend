-- Partner fleet mirror (Mario 25.10.2026: "mi prikazujemo sta na MMK i nausys daje"): the first moment the partner's
-- catalogue answered with NO yachts for the agency (HTTP 200, empty list); NULL = the partner lists yachts.
-- Written by MmkYachtSyncService / NauSysYachtSyncService through PartnerFleetMirror: an empty answer that lasts
-- PartnerFleetMirror.GRACE (two nights) switches every yacht of the agency off, a listed yacht switches itself on again.
ALTER TABLE agency ADD COLUMN IF NOT EXISTS partner_fleet_empty_since TIMESTAMPTZ;

-- Multihull Yachting (agency 1605, 17 boats, 52 free weeks): MMK has answered [] since 22.10.2026 06:10 UTC (cusma3
-- "MMK take-back aborted for agency 1605: empty partner response" on every run; availability for its company still
-- answers). Mario 25.10.: "MMK je maknuo prikaz plovila od Multihull yachtinga za sada, tako treba biti i kod nas" -
-- mirrored now, not after the grace. The sync switches the boats on again the moment MMK lists them.
UPDATE agency SET partner_fleet_empty_since = '2026-10-22 06:10:00+00' WHERE id = 1605 AND partner_fleet_empty_since IS NULL;
UPDATE yacht SET sys_active = false WHERE agency_id = 1605 AND sys_active = true;
