-- Cyclades (region "Cyclades") must list ONLY Cyclades-island marinas. V9_15 already
-- did this but (a) its denylist was incomplete — it missed many Athens/Attica departure
-- ports (Alimos, Zea, Flisvos, Olympic Marina, Agios Kosmas, Vouliagmeni, Eleusis,
-- Kallithea, Porto Rafti, Pachi, …) plus Crete (Elounda, Sitia, Kissamos, Kolymvari),
-- Evia (Karistos, Eretria) and Peloponnese/Saronic (Astros, Ermioni, Nafplion, Korfos,
-- Poros) — and (b) the catalogue was re-polluted afterwards (an earlier R__1_09 version
-- copied merged-region rows back in). R__1_09 is delete-only now, so this versioned
-- cleanup is durable.
--
-- Owner ask (Mario, Jun-2026): "important that Athens and the Athens area do NOT show
-- when searching Cyclades — otherwise the island boats are hard to find." A Cyclades
-- search must return island offers only.
--
-- Denylist by NAME (region/location IDs renumber on reseed). Verified against the live
-- catalogue NOT to hit any real Cyclades marina. NOTE: we deliberately do NOT match a
-- bare 'astir' — that substring lives inside "Monastiri Bay" (Paros, a genuine Cyclades
-- marina); "Astir Marina Vouliagmeni" is removed via the 'vouliagmeni' pattern instead.
-- Idempotent; a no-op once the rows are gone.
DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND r.name = 'Cyclades'
  AND (
        -- Athens / Attica mainland + Lavrion + Piraeus departure ports
        l.name ILIKE '%athen%'        OR l.name ILIKE '%lavrio%'        OR l.name ILIKE '%piraeus%'
        OR l.name ILIKE '%alimos%'    OR l.name ILIKE '%vouliagmeni%'   OR l.name ILIKE '%varkiza%'
        OR l.name ILIKE '%glyfada%'   OR l.name ILIKE '%flisvos%'       OR l.name ILIKE '%amfitheas%'
        OR l.name ILIKE '%agios kosmas%' OR l.name ILIKE '%zea%'        OR l.name ILIKE '%anavyssos%'
        OR l.name ILIKE '%lagonisi%'  OR l.name ILIKE '%kallithea%'     OR l.name ILIKE '%eleusis%'
        OR l.name ILIKE '%elefsina%'  OR l.name ILIKE '%nea peramos%'   OR l.name ILIKE '%palaia fokaia%'
        OR l.name ILIKE '%palaio faliro%' OR l.name ILIKE '%porto rafti%' OR l.name ILIKE '%pachi%'
        OR l.name ILIKE '%megara%'    OR l.name ILIKE '%olympic marina%'
        -- Crete
        OR l.name ILIKE '%crete%'     OR l.name ILIKE '%chania%'        OR l.name ILIKE '%heraklion%'
        OR l.name ILIKE '%kissamos%'  OR l.name ILIKE '%kolymvari%'     OR l.name ILIKE '%elounda%'
        OR l.name ILIKE '%schisma%'   OR l.name ILIKE '%sitia%'         OR l.name ILIKE '%sfakia%'
        OR l.name ILIKE '%sfakion%'   OR l.name ILIKE '%agia galini%'   OR l.name ILIKE '%linoperamata%'
        -- Evia
        OR l.name ILIKE '%karistos%'  OR l.name ILIKE '%khalkis%'       OR l.name ILIKE '%chalkis%'
        OR l.name ILIKE '%eretria%'   OR l.name ILIKE '%chalkoutsi%'
        -- Peloponnese / Saronic
        OR l.name ILIKE '%astros%'    OR l.name ILIKE '%ermioni%'       OR l.name ILIKE '%nafplion%'
        OR l.name ILIKE '%korfos%'    OR l.name ILIKE '%poros%'
        -- Other island groups: Ionian (Paxos), Dodecanese (Marathi, Nimborio/Symi)
        OR l.name ILIKE '%paxos%'     OR l.name ILIKE '%marathi%'       OR l.name ILIKE '%nimborio%'
      );
