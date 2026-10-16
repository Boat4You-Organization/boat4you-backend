-- V9_88 (16.10.2026) - capacity / rig changes move a boat's <lastmod>. Owner decision 16.10.2026 (Mario): extend the trigger,
-- NO mass bump ("lastmod is not the lever for visibility; sitemaps + IndexNow are" - every boat is in the sitemaps and
-- IndexNow gets every new / changed URL). Was docs/pending-migrations/B8 (6.10.) minus its one-shot stamp.
--
-- V9_71's AFTER UPDATE trigger on yacht lists the columns the public boat page rendered on 1.10.; the capacity release
-- (V9_72, 6.-8.10.) added rendered columns it does not watch, so a changed note, crew split, sail / engine label or
-- draught leaves yacht_content_modified (and the sitemap <lastmod>) untouched. This re-creates the trigger with the full
-- list the page renders from yacht - NOT internal_remark (admin only), NOT prices / availability / child tables (V9_71
-- rationale). Only a real change moves the row (IS DISTINCT FROM).
-- Guard = the trigger DEFINITION: when yacht_content_modified_update already mentions cabins_note (hand pre-applied
-- before the restart) nothing runs; V9_71's "both triggers exist" guard would have skipped this for ever.
-- CREATE OR REPLACE TRIGGER takes SHARE ROW EXCLUSIVE on yacht: a sync transaction still writing yacht holds it off and
-- Flyway runs before the API serves anything on cusma2, so pre-apply by hand as boat4you_owner outside the sync windows
-- (DEPLOY_NOTES); under Flyway each attempt waits at most 3 s, 10 attempts 3 s apart, then it gives up (API does not start).
-- Rollback: re-run the V9_71 CREATE TRIGGER body (shorter column list); the table and function are untouched.
DO $$
DECLARE
    attempt INT := 0;
BEGIN
    IF EXISTS (SELECT 1
                 FROM pg_trigger
                WHERE tgrelid = 'public.yacht'::regclass
                  AND tgname = 'yacht_content_modified_update'
                  AND pg_get_triggerdef(oid) LIKE '%cabins_note%') THEN
        RETURN;
    END IF;
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'CREATE OR REPLACE TRIGGER yacht_content_modified_update AFTER UPDATE ON public.yacht FOR EACH ROW WHEN ('
                        '(OLD.name, OLD.model_id, OLD.location_id, OLD.build_year, OLD.length, OLD.beam, OLD.cabins, '
                        'OLD.wc, OLD.berths, OLD.max_persons, OLD.engine_power, OLD.fuel_tank, OLD.water_tank, '
                        'OLD.mainsail_type, OLD.deposit, OLD.insured_deposit, OLD.deposit_currency, OLD.crew_number, '
                        'OLD.default_checkin, OLD.default_checkout, OLD.vessel_type, OLD.entry_type, OLD.sys_active, '
                        'OLD.main_image_id, OLD.option_approval, '
                        'OLD.crew_cabins, OLD.crew_wc, OLD.crew_berths, OLD.draught, OLD.cabins_note, OLD.berths_note, '
                        'OLD.wc_note, OLD.cabin_berths, OLD.salon_berths, OLD.showers, OLD.crew_showers, '
                        'OLD.recommended_persons, OLD.mainsail_label, OLD.genoa_label, OLD.engine_label, '
                        'OLD.engine_count, OLD.engine_power_each) IS DISTINCT FROM '
                        '(NEW.name, NEW.model_id, NEW.location_id, NEW.build_year, NEW.length, NEW.beam, NEW.cabins, '
                        'NEW.wc, NEW.berths, NEW.max_persons, NEW.engine_power, NEW.fuel_tank, NEW.water_tank, '
                        'NEW.mainsail_type, NEW.deposit, NEW.insured_deposit, NEW.deposit_currency, NEW.crew_number, '
                        'NEW.default_checkin, NEW.default_checkout, NEW.vessel_type, NEW.entry_type, NEW.sys_active, '
                        'NEW.main_image_id, NEW.option_approval, '
                        'NEW.crew_cabins, NEW.crew_wc, NEW.crew_berths, NEW.draught, NEW.cabins_note, NEW.berths_note, '
                        'NEW.wc_note, NEW.cabin_berths, NEW.salon_berths, NEW.showers, NEW.crew_showers, '
                        'NEW.recommended_persons, NEW.mainsail_label, NEW.genoa_label, NEW.engine_label, '
                        'NEW.engine_count, NEW.engine_power_each)) '
                        'EXECUTE FUNCTION public.yacht_content_modified_touch()';
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_88: yacht is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
END $$;
