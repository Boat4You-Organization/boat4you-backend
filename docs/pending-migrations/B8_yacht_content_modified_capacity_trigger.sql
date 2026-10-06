-- B8 - NOT TO BE COMMITTED NOW. Becomes boat4you-ws-main/src/main/resources/db/migration/V9_<next>__yacht_content_modified_capacity.sql
-- only AFTER b4y + the 6 sisters render the capacity / rig blocks in production (critique B-7).
-- (git pull --ff-only + ls | sort -V | tail -3 immediately before writing; check the jar for duplicate V9_ versions.)
--
-- Why later: if the V9_71 trigger counted the new columns from the start, the first syncs on the new jar would stamp
-- yacht_content_modified for ~14k boats days BEFORE any page shows the new data, and nothing would move when the pages
-- really change. So:
--   1. extend the UPDATE trigger's column list with everything the boat page now renders from yacht
--      (NOT internal_remark - admin only);
--   2. one-shot: stamp every active boat whose page gained rendered capacity / rig content with the deploy time.
--      This writes only yacht_content_modified (small table, no lock on yacht beyond ACCESS SHARE for the SELECT).
--
-- Guard (critique B-3): V9_71's "both triggers exist -> RETURN" guard would skip this forever, so the guard here is the
-- trigger DEFINITION: when yacht_content_modified_update already mentions cabins_note (hand-applied before the
-- restart), nothing runs - neither the trigger re-creation nor the bump.
-- CREATE OR REPLACE TRIGGER takes SHARE ROW EXCLUSIVE on yacht; same 3 s x 10 retry loop as V9_71. Deploy outside the
-- sync windows; hand-apply as boat4you_owner before the cusma2 restart (DEPLOY_NOTES).

DO $$
DECLARE
    attempt INT := 0;
    bumped  BIGINT;
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
                RAISE NOTICE 'B8: yacht is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;

    -- One-shot lastmod bump: active boats whose page gained capacity / rig content with the frontend deploy
    -- (a note, a crew / saloon / shower figure, a recommended number, a sail or headsail row, an engine row, a draught
    -- row). Only moves forward (same rule as yacht_content_modified_touch).
    INSERT INTO public.yacht_content_modified AS m (yacht_id, modified_at)
    SELECT y.id, clock_timestamp()
      FROM public.yacht y
     WHERE y.sys_active
       AND (y.cabins_note IS NOT NULL OR y.berths_note IS NOT NULL OR y.wc_note IS NOT NULL
            OR y.crew_cabins > 0 OR y.crew_wc > 0 OR y.crew_berths > 0 OR y.salon_berths > 0
            OR y.showers > 0 OR y.crew_showers > 0 OR y.recommended_persons > 0
            OR (y.mainsail_label IS NOT NULL AND lower(y.mainsail_label) <> 'none')
            OR (y.genoa_label IS NOT NULL AND lower(y.genoa_label) <> 'none')
            OR y.engine_label IS NOT NULL OR y.engine_power_each > 0
            OR y.draught > 0)
    ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at
        WHERE m.modified_at < EXCLUDED.modified_at;
    GET DIAGNOSTICS bumped = ROW_COUNT;
    RAISE NOTICE 'B8: yacht_content_modified stamped for % active boats', bumped;
END $$;
