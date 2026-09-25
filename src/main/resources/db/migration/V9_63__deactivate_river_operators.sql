-- 25.9.2026: boat4you and the six sister sites sell SEA charter only - river, canal and lake cruisers never belong on
--   any site (Mario, standing rule; Le Boat "Caprice Comfort 51" was live on www.boat4you.com). Since 5.7.2026 the MMK
--   agency mirror auto-creates every unknown MMK company as an ACTIVE agency, and 13 river operators came in that way:
--   Le Boat 1883 (901 boats), Riverly 1773 (324), Anjou Navigation 1734, Canal Evasion 1674, Houseboat Holidays Italia
--   2038, SBS Fleesensee 1657, Revier Charter 1747, Yachtcharter De Drait 1219, Jachtwerf Oost 688, Hibo Yachtcharter
--   1795, Blue Wave Yachting by Delos 1908, Aqua Libra 1472, 3Lacs Yacht Charter 937. Their cruisers are listed as
--   MOTORBOAT / MOTOR_YACHT, so the VesselType skip never caught them.
-- This records the data fix already applied by hand in production at 16:51 UTC (backup table
-- ops_river_agency_backup_20260925 there). sync_deactivated_by = NULL makes it a manual OFF: the MMK and NauSys mirrors
-- only re-activate agencies they deactivated themselves, so these stay off. The listing and the yacht / offer sync
-- skip inactive agencies; yachts and offers are not touched (no delete).
-- From now on the code keeps it that way: InlandVesselRules - new river-operator companies are created inactive, and
-- the MMK / NauSys yacht sync skip (and switch off) yachts from inland-only builders.
-- Idempotent: an agency already off (as in production today) matches nothing. No other data changes. Agency ids
-- differ between databases (locally 1657 is a Greek sea company), so the production names guard every id.
-- Flyway's role has no lock_timeout of its own. The agency mirrors write these rows, so give up fast instead of
-- waiting on a sync transaction: the migration is one transaction, a timeout rolls it back cleanly and the API's
-- systemd restart simply tries again a few seconds later (deploy inside a quiet window, DEPLOY_NOTES).
SET LOCAL lock_timeout = '5s';

UPDATE public.agency
   SET active = false,
       sync_deactivated_by = NULL
 WHERE id IN (1883, 1773, 1734, 1674, 2038, 1657, 1747, 1219, 688, 1795, 1908, 1472, 937)
   AND lower(trim(name)) IN ('le boat', 'riverly', 'anjou navigation', 'canal evasion', 'houseboat holidays italia',
                             'sbs fleesensee', 'revier charter', 'yachtcharter de drait', 'jachtwerf oost',
                             'hibo yachtcharter', 'blue wave yachting by delos', 'aqua libra', '3lacs yacht charter')
   AND active;
