-- Cyclades (region 21) should list ONLY Cyclades-island marinas. The catalogue had it
-- polluted with mainland + other-island-group departure ports (Athens/Attica, Lavrion,
-- Piraeus, Crete, Evia, Peloponnese, plus an Ionian (Paxos), a Saronic (Poros) and a
-- Dodecanese (Marathi) entry). A "Cyclades" search should return island offers only.
--
-- Drop those location_region rows. Match by NAME (region/location IDs renumber). Marina names
-- carry the " | {city}" suffix (V9_13) so the place name is always present in l.name. Patterns
-- were verified NOT to hit any actual Cyclades marina. Idempotent; no-op once removed.
DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND r.name = 'Cyclades'
  AND (
        -- Athens / Attica mainland + Lavrion + Piraeus
        l.name ILIKE '%athen%'      OR l.name ILIKE '%lavrio%'   OR l.name ILIKE '%piraeus%'
        OR l.name ILIKE '%elefsina%' OR l.name ILIKE '%palaio faliro%' OR l.name ILIKE '%porto rafti%'
        OR l.name ILIKE '%nea peramos%' OR l.name ILIKE '%palaia fokaia%' OR l.name ILIKE '%megara%'
        OR l.name ILIKE '%varkiza%'  OR l.name ILIKE '%anavyssos%' OR l.name ILIKE '%lagonisi%'
        OR l.name ILIKE '%chalkoutsi%'
        -- Crete
        OR l.name ILIKE '%crete%'    OR l.name ILIKE '%chania%'   OR l.name ILIKE '%heraklion%'
        OR l.name ILIKE '%kissamos%' OR l.name ILIKE '%kolymvari%' OR l.name ILIKE '%elounda%'
        OR l.name ILIKE '%schisma%'  OR l.name ILIKE '%sitia%'    OR l.name ILIKE '%sfakion%'
        OR l.name ILIKE '%agia galini%' OR l.name ILIKE '%linoperamata%'
        -- Evia / Peloponnese
        OR l.name ILIKE '%karistos%' OR l.name ILIKE '%khalkis%'  OR l.name ILIKE '%eretria%'
        OR l.name ILIKE '%astros%'   OR l.name ILIKE '%ermioni%'  OR l.name ILIKE '%nafplion%'
        -- Other island groups (Ionian / Saronic / Dodecanese)
        OR l.name ILIKE '%paxos%'    OR l.name ILIKE '%poros%'    OR l.name ILIKE '%marathi%'
      );
