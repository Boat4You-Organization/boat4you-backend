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
-- Match against name + city (region/location IDs renumber on reseed; some mainland ports
-- carry a generic name like "Kalympaki Marina" with the mainland place only in `city`
-- — e.g. Elefsina — so name-only matching missed them). Verified against the live
-- catalogue NOT to hit any real Cyclades marina. NOTE: we deliberately do NOT match a
-- bare 'astir' — that substring lives inside "Monastiri Bay" (Paros, a genuine Cyclades
-- marina); "Astir Marina Vouliagmeni" is removed via the 'vouliagmeni' pattern instead.
-- Idempotent; a no-op once the rows are gone.
DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND r.name = 'Cyclades'
  AND (l.name || ' ' || COALESCE(l.city, '')) ILIKE ANY (ARRAY[
          -- Athens / Attica mainland + Lavrion + Piraeus departure ports
          '%athen%', '%lavrio%', '%piraeus%', '%alimos%', '%vouliagmeni%', '%varkiza%',
          '%glyfada%', '%flisvos%', '%amfitheas%', '%agios kosmas%', '%zea%', '%anavyssos%',
          '%lagonisi%', '%kallithea%', '%eleusis%', '%elefsina%', '%nea peramos%',
          '%palaia fokaia%', '%palaio faliro%', '%porto rafti%', '%pachi%', '%megara%',
          '%olympic marina%', '%salamina%', '%salamis%',
          -- Crete
          '%crete%', '%chania%', '%heraklion%', '%kissamos%', '%kolymvari%', '%elounda%',
          '%schisma%', '%sitia%', '%sfakia%', '%sfakion%', '%agia galini%', '%linoperamata%',
          -- Evia
          '%karistos%', '%khalkis%', '%chalkis%', '%eretria%', '%chalkoutsi%',
          -- Peloponnese / Saronic
          '%astros%', '%ermioni%', '%nafplion%', '%korfos%', '%poros%',
          -- Other island groups: Ionian (Paxos), Dodecanese (Marathi, Nimborio/Symi)
          '%paxos%', '%marathi%', '%nimborio%'
        ]);
