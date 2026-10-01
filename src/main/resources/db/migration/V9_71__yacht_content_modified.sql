-- When a boat's own public record last changed (1.10.2026, Codex audit N7: the yacht sitemaps of all 7 sites carry no
-- <lastmod>, and a reliable one would speed up the recrawl of boats whose head changed).
--
-- Nothing in the schema said when a boat changed: yacht, offer, yacht_image and yacht_translations have no timestamp
-- column, and synced_entity is never written. yacht_content_modified keeps one row per boat with the time a field the
-- public pages render last changed - the title (model, name, year, home base), the meta description (cabins, berths,
-- persons), the canonical slug (name, model), the share image (main image), the spec table and the inquiry-only state.
-- An AFTER trigger on yacht writes it, so every writer is covered (MMK and NauSys syncs, the admin, data-fix migrations,
-- RetentionReaperJob), and only a real change moves it: the syncs re-save every boat, and an UPDATE that rewrites
-- unchanged values is not a change (IS DISTINCT FROM). Production 1.10.2026, last write of each yacht row (xmin against
-- service_call_cache.created_at): 10,855 of 13,072 active boats unwritten for 45 days, 4-99 written a day (385 on 29.9.).
--
-- NOT counted, on purpose:
--   - prices and availability: offer rewrites millions of rows, every boat would "change" every day;
--   - gallery, descriptions, equipment, extras (child tables): a trigger there would update yacht from a second writer
--     (ImageDownloadJob) and add lock-order risk to the syncs, for body content the head does not show;
--   - model / manufacturer renames: they change the slug, so the sitemap lists a new URL anyway;
--   - twin canonicals (yacht_listing_twin) and agency flags.
-- A boat without a row has no recorded change since this migration: the sitemaps leave its <lastmod> out, never guess.
-- The time is clock_timestamp() of the change, not the transaction start, so a long agency sync stamps close to its
-- commit; a row only moves forward.
--
-- Read by YachtQueryingService.searchYachts (one primary-key lookup per listing page) into
-- YachtSearchResponseDto.updatedAt. Keep the column list of the UPDATE trigger in step with what the public boat page
-- renders (YachtMapper.toDto / toDetailsDto, Yacht.isInquireOnly).
--
-- No foreign key (as V9_70): a deleted yacht leaves a harmless row, yacht ids are never reused.
-- CREATE TRIGGER takes SHARE ROW EXCLUSIVE on yacht. Readers never wait for it (the API keeps serving), but a sync
-- transaction still writing yacht holds it off, and a lock timeout here would fail the API start. So each attempt waits
-- at most 3 s (writers queue behind it meanwhile, readers do not) and it tries 10 times, 3 s apart, before giving up
-- (about a minute). Deploy outside the sync windows anyway (DEPLOY_NOTES).

CREATE TABLE IF NOT EXISTS public.yacht_content_modified (
    yacht_id    BIGINT      PRIMARY KEY,
    modified_at TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON public.yacht_content_modified TO boat4you_app;

CREATE OR REPLACE FUNCTION public.yacht_content_modified_touch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.yacht_content_modified AS m (yacht_id, modified_at)
    VALUES (NEW.id, clock_timestamp())
    ON CONFLICT (yacht_id) DO UPDATE SET modified_at = EXCLUDED.modified_at
        WHERE m.modified_at < EXCLUDED.modified_at;
    RETURN NULL;
END $$;

DO $$
DECLARE
    attempt INT := 0;
BEGIN
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '3s', true);
            EXECUTE 'CREATE TRIGGER yacht_content_modified_insert AFTER INSERT ON public.yacht '
                        'FOR EACH ROW EXECUTE FUNCTION public.yacht_content_modified_touch()';
            EXECUTE 'CREATE TRIGGER yacht_content_modified_update AFTER UPDATE ON public.yacht FOR EACH ROW WHEN ('
                        '(OLD.name, OLD.model_id, OLD.location_id, OLD.build_year, OLD.length, OLD.beam, OLD.cabins, '
                        'OLD.wc, OLD.berths, OLD.max_persons, OLD.engine_power, OLD.fuel_tank, OLD.water_tank, '
                        'OLD.mainsail_type, OLD.deposit, OLD.insured_deposit, OLD.deposit_currency, OLD.crew_number, '
                        'OLD.default_checkin, OLD.default_checkout, OLD.vessel_type, OLD.entry_type, OLD.sys_active, '
                        'OLD.main_image_id, OLD.option_approval) IS DISTINCT FROM '
                        '(NEW.name, NEW.model_id, NEW.location_id, NEW.build_year, NEW.length, NEW.beam, NEW.cabins, '
                        'NEW.wc, NEW.berths, NEW.max_persons, NEW.engine_power, NEW.fuel_tank, NEW.water_tank, '
                        'NEW.mainsail_type, NEW.deposit, NEW.insured_deposit, NEW.deposit_currency, NEW.crew_number, '
                        'NEW.default_checkin, NEW.default_checkout, NEW.vessel_type, NEW.entry_type, NEW.sys_active, '
                        'NEW.main_image_id, NEW.option_approval)) '
                        'EXECUTE FUNCTION public.yacht_content_modified_touch()';
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 10 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_71: yacht is being written (attempt % of 10), retrying in 3 s', attempt;
                PERFORM pg_sleep(3);
        END;
    END LOOP;
END $$;
