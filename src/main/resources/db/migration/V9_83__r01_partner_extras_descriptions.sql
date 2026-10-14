-- V9_83 R01 (owner rule 14.10.2026, "Di pise NSS ili SUNSAIL stavi BOAT4YOU tu i gotovo"): partner yacht_extras
-- descriptions -> customer copy. SQL subset of PartnerCopySanitizer.description (N2 + N3 + N4 + N5 only):
--   * internal "update manually" notes dropped,
--   * "(to be (re)confirmed by NSS[ office])" -> "(to be confirmed by Boat4You)" (capital T kept),
--   * curated partner names (resources/partner/replace.v9_83.txt, a frozen snapshot of replace.txt) -> Boat4You.
-- Rows that need Kotlin-only rules - premises (P1, possessive and possessive-free), legal disclaimers (P2), "the Company",
-- contacts, HTML - are SKIPPED: N4 alone would turn "Athenian Yachts' Pier" into "Boat4You' Pier". Every sync rewrites
-- yacht_extras.description through PartnerCopySanitizer, and read time cleans the wire immediately.
-- EXTERNAL yachts only, like the read-time guard (a CUSTOM yacht's copy is ours; 0 such rows on the rehearsal copy).
-- Never touched: name columns (join keys, DESIGN D2), offer_extras (rewritten by every offer sync, cleaned at read time),
-- reservation_extras / external_reservation_extras (booking snapshots), extras (our catalogue), yacht_translations.
-- Idempotent: the backup keeps the FIRST original of each row, and a marker row makes a re-run (cusma2 out of order) a no-op.
-- The N3/N4 alternation below is generated from replace.v9_83.txt; ReplaceListContractTest asserts it is equal and a subset
-- of replace.txt, and R01MigrationTest asserts r01_partner_copy_v1(x) == PartnerCopySanitizer.description(x).
--
-- PRE-APPLY BY HAND before the Wave-1 deploy (gate window, as boat4you_owner on cusma4), as ONE transaction so that
-- SET LOCAL lock_timeout applies:   psql -1 -v ON_ERROR_STOP=1 -f V9_83__r01_partner_extras_descriptions.sql
-- then check: SELECT * FROM public._r01_v9_83_done;   (no row = it rolled back on lock_timeout: re-run, do NOT deploy)
-- Rollback (only together with the old jar - the new sync re-cleans):
--   UPDATE yacht_extras ye SET description = b.description FROM _r01_yacht_extras_backup b WHERE b.id = ye.id;
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS public._r01_v9_83_done (
    step    text PRIMARY KEY,
    rows    integer NOT NULL,
    done_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public._r01_yacht_extras_backup (
    id           bigint PRIMARY KEY,
    yacht_id     bigint,
    description  text,
    backed_up_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION public.r01_partner_copy_v1(t text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $f$
  SELECT NULLIF(btrim(
    regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(t,
      '[[:space:]]*\((number of guests )?to (be )?update(d)? manually\)', '', 'gi'),                    -- N2
      '[[:space:]]*[-–][[:space:]]*to be updated manually', '', 'gi'),                                  -- N2
      '([Tt])o be (re-?)?confirmed by (Dream Yacht Charter|Navigare Yachting|Ionian Catamarans|Athenian Yachts|Hermes Yachting|Rhodes Yachting|Active Cruises|Kavas Yachting|More Yachting|The Moorings|NSS Charter|Kiriacoulis|Navigare|Moorings(?=[[:space:]]*[0-9]|[''’]s\M)|Sunsail|Istion|Pitter|NSS)( office)?', '\1o be confirmed by Boat4You', 'g'),            -- N3 (replace.v9_83.txt)
      '\m(Dream Yacht Charter|Navigare Yachting|Ionian Catamarans|Athenian Yachts|Hermes Yachting|Rhodes Yachting|Active Cruises|Kavas Yachting|More Yachting|The Moorings|NSS Charter|Kiriacoulis|Navigare|Moorings(?=[[:space:]]*[0-9]|[''’]s\M)|Sunsail|Istion|Pitter|NSS)\M', 'Boat4You', 'g'),                                                                  -- N4 (replace.v9_83.txt)
      'Boat4You[[:space:]]*\(Boat4You\)', 'Boat4You', 'g'),                                             -- N5
      'Boat4You([[:space:]/]+Boat4You)+', 'Boat4You', 'g'),                                             -- N5
      '[ \t]{2,}', ' ', 'g'), E' \t\r\n'), '')                                                          -- N5
$f$;

DO $$
DECLARE
  backed_up integer;
  n integer;
  -- rows needing Kotlin-only rules: premises (P1, possessive and possessive-free), disclaimers (P2), "the Company", contacts, HTML
  skip_re constant text := '[''’]s?[[:space:]]+(pier|pontoon|base|bases|office|premises|dock)\M'
                        -- possessive-free premises exactly where Kotlin P1 rewrites them ("at the NSS base", "The Moorings
                        -- base"), NOT "confirmed by NSS office" (that is N3, done here)
                        || '|\m(at|from|in|to)[[:space:]]+(the[[:space:]]+)?(Dream Yacht Charter|Ionian Catamarans|Navigare Yachting|Athenian Yachts|Hermes Yachting|Rhodes Yachting|Active Cruises|Kavas Yachting|More Yachting|The Moorings|NSS Charter|Kiriacoulis|Athenian|Moorings|Navigare|Sunsail|Hermes|Istion|Pitter|NSS)[[:space:]]+(pier|pontoon|base|bases|office|premises|dock)\M'
                        || '|\mThe Moorings[[:space:]]+(pier|pontoon|base|bases|office|premises|dock)\M'
                        || '|\m(solely|only) provides?\M|\m(shall|will) not be (held )?liable\M|\m[Tt]he Company\M'
                        || '|@|https?://|www\.|<[a-zA-Z/]|\m(tel|phone|mob|mobile|call)\M|\+[0-9]';
  hit_re  constant text := 'manually|\m(NSS|Sunsail|Moorings|Dream Yacht Charter|Navigare|Athenian Yachts|Hermes Yachting|More Yachting|Ionian Catamarans|Kiriacoulis|Istion|Pitter|Kavas Yachting|Active Cruises|Rhodes Yachting)\M';
BEGIN
  IF EXISTS (SELECT 1 FROM public._r01_v9_83_done WHERE step = 'yacht_extras') THEN
    RAISE NOTICE 'V9_83 yacht_extras: marker present, nothing to do';
    RETURN;
  END IF;

  INSERT INTO public._r01_yacht_extras_backup (id, yacht_id, description)
  SELECT ye.id, ye.yacht_id, ye.description FROM public.yacht_extras ye
   WHERE ye.description ~ hit_re AND ye.description !~* skip_re
     AND EXISTS (SELECT 1 FROM public.yacht y WHERE y.id = ye.yacht_id AND y.entry_type = 'EXTERNAL')
     AND public.r01_partner_copy_v1(ye.description) IS DISTINCT FROM ye.description
  ON CONFLICT (id) DO NOTHING;
  GET DIAGNOSTICS backed_up = ROW_COUNT;

  UPDATE public.yacht_extras ye SET description = public.r01_partner_copy_v1(ye.description)
   WHERE ye.description ~ hit_re AND ye.description !~* skip_re
     AND EXISTS (SELECT 1 FROM public.yacht y WHERE y.id = ye.yacht_id AND y.entry_type = 'EXTERNAL')
     AND public.r01_partner_copy_v1(ye.description) IS DISTINCT FROM ye.description;
  GET DIAGNOSTICS n = ROW_COUNT;

  RAISE NOTICE 'V9_83 yacht_extras descriptions: % backed up (first original), % cleaned', backed_up, n;
  INSERT INTO public._r01_v9_83_done (step, rows) VALUES ('yacht_extras', n);
END $$;
