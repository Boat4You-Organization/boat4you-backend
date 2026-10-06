-- Yacht capacity / rig partner data, stored AS SENT (capacity contract v1, 6.10.2026). The partner payloads carry
-- more than the yacht row kept: MMK berths / cabins / WC notes ("(12 pax + 1 Crew)"), NauSys berths in cabins / in the
-- saloon, showers, recommended persons, engines x power per engine, and both sides' sail labels. The public DTOs derive
-- the capacity / rig blocks from these columns at read time (YachtCapacityMapper), so a parser fix never needs a re-sync.
-- Columns only: NO trigger change here (critique B-7). The V9_71 yacht_content_modified_update trigger is extended by a
-- separate later migration once the frontends render the new fields (a change of these columns before that would bump
-- the sitemap <lastmod> of ~14k boats days before any page shows it).
--
-- ADD COLUMN (nullable, no default) is metadata-only but needs ACCESS EXCLUSIVE on yacht, and Postgres takes that lock
-- even for ADD COLUMN IF NOT EXISTS when every column already exists (critique B-3). So:
--   1. if all 14 columns exist (hand-applied before the restart) -> RETURN: no lock at all on the Flyway run;
--   2. otherwise each attempt waits at most 1 s for the lock - and while it waits EVERY query on yacht, reads included
--      (boat pages, the search page lookup), queues behind it - then 1 s with no lock; 30 attempts (~1 min), then it
--      gives up (API does not start). Anything holding any lock on yacht blocks it: a sync writing yacht, the 10-minute
--      matview REFRESH (180-423 s, holds ACCESS SHARE on yacht throughout), a pg_dump backup. Hand-apply right after a
--      matview refresh ends, outside the sync and backup slots (DEPLOY_NOTES).
-- The COMMENTs run inside the same guarded attempt (same lock, same subtransaction).
-- Hand-apply as boat4you_owner in a quiet moment before the cusma2 restart (same procedure as V9_71). V__ migrations
-- run only on cusma2 (FLYWAY_OUT_OF_ORDER=true, target latest); cusma3 is pinned and must get the new jar only AFTER
-- cusma2 applied this (its entity selects the new columns).
-- Table-level grants (V1_04: GRANT ... ON ALL TABLES + DEFAULT PRIVILEGES) cover new columns; no GRANT needed.
-- Matview R__1_03 is not touched (explicit column list), so no rebuild.

DO $$
DECLARE
    attempt INT := 0;
BEGIN
    IF (SELECT count(*)
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'yacht'
           AND column_name IN ('cabins_note', 'berths_note', 'wc_note', 'cabin_berths', 'salon_berths', 'showers',
                               'crew_showers', 'recommended_persons', 'mainsail_label', 'genoa_label', 'engine_label',
                               'engine_count', 'engine_power_each', 'internal_remark')) = 14 THEN
        RETURN;
    END IF;
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '1s', true);
            ALTER TABLE public.yacht
                ADD COLUMN IF NOT EXISTS cabins_note         TEXT,
                ADD COLUMN IF NOT EXISTS berths_note         TEXT,
                ADD COLUMN IF NOT EXISTS wc_note             TEXT,
                ADD COLUMN IF NOT EXISTS cabin_berths        SMALLINT,
                ADD COLUMN IF NOT EXISTS salon_berths        SMALLINT,
                ADD COLUMN IF NOT EXISTS showers             SMALLINT,
                ADD COLUMN IF NOT EXISTS crew_showers        SMALLINT,
                ADD COLUMN IF NOT EXISTS recommended_persons SMALLINT,
                ADD COLUMN IF NOT EXISTS mainsail_label      TEXT,
                ADD COLUMN IF NOT EXISTS genoa_label         TEXT,
                ADD COLUMN IF NOT EXISTS engine_label        TEXT,
                ADD COLUMN IF NOT EXISTS engine_count        SMALLINT,
                ADD COLUMN IF NOT EXISTS engine_power_each   NUMERIC(7, 2),
                ADD COLUMN IF NOT EXISTS internal_remark     TEXT;

            COMMENT ON COLUMN public.yacht.cabins_note IS
                'MMK cabinsNote as sent (trim, NBSP->space, collapsed blanks; NULL when blank). Shown after the cabins number only through PartnerTextSanitizer.capacityNote. NauSys: NULL.';
            COMMENT ON COLUMN public.yacht.berths_note IS
                'MMK berthsNote as sent (normalized as cabins_note), e.g. "(12 pax + 1 Crew)", "8+2". NauSys: NULL.';
            COMMENT ON COLUMN public.yacht.wc_note IS
                'MMK wcNote as sent (normalized as cabins_note), e.g. "(5+1 for the crew)". NauSys: NULL.';
            COMMENT ON COLUMN public.yacht.cabin_berths IS
                'NauSys berthsCabin (berths in cabins). berths = cabin_berths + salon_berths + crew_berths (held on 820/820). MMK: NULL.';
            COMMENT ON COLUMN public.yacht.salon_berths IS
                'NauSys berthsSalon (saloon berths). MMK: NULL.';
            COMMENT ON COLUMN public.yacht.showers IS
                'NauSys showers as sent (0 = partner did not fill it in; shown only when > 0). MMK: NULL.';
            COMMENT ON COLUMN public.yacht.crew_showers IS
                'NauSys showersCrew as sent (shown only when > 0). MMK: NULL.';
            COMMENT ON COLUMN public.yacht.recommended_persons IS
                'NauSys recommendedPersons as sent (0 / NULL = not given). Never a substitute for max_persons. MMK: NULL.';
            COMMENT ON COLUMN public.yacht.mainsail_label IS
                'Partner mainsail label: MMK mainsailType from the call WITHOUT ?language= ("Full batten", "Furling", "None"); NauSys sailTypes catalogue EN name for sailTypeId ("full batten"). Display kind is derived at read time; mainsail_type stays the filter enum.';
            COMMENT ON COLUMN public.yacht.genoa_label IS
                'Partner headsail label: MMK genoaType (no-language call) / NauSys catalogue EN name for genoaTypeId ("self tacking jib").';
            COMMENT ON COLUMN public.yacht.engine_label IS
                'MMK engine string as sent ("2x60HP"). Shown only when it carries a power unit (hp, h.p., bhp, ps, cv, kW) and passes the sanitizer. engine_power = parsed total hp (filter). NauSys: NULL.';
            COMMENT ON COLUMN public.yacht.engine_count IS
                'NauSys engines (count). MMK: NULL.';
            COMMENT ON COLUMN public.yacht.engine_power_each IS
                'NauSys enginePower per engine, not truncated (unit undocumented, treated as hp). engine_power = round(max(engine_count,1) * engine_power_each). MMK: NULL.';
            COMMENT ON COLUMN public.yacht.internal_remark IS
                'ADMIN ONLY. NauSys noteIntText.textEN (else note) / MMK comment, normalized. Never in a public DTO, the customer e-mail, the AI chat or any public page.';
            EXIT;
        EXCEPTION
            WHEN lock_not_available THEN
                attempt := attempt + 1;
                IF attempt >= 30 THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V9_72: yacht is locked (attempt % of 30), retrying in 1 s', attempt;
                PERFORM pg_sleep(1);
        END;
    END LOOP;
END $$;
