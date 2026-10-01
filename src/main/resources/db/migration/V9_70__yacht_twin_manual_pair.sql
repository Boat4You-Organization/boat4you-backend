-- Hand-verified twin pairs for the one-card-per-boat rule (1.10.2026, SEO regression SM4).
--
-- yacht_listing_twin (V9_69) pairs two copies of a boat by name, base, vessel type, length and year. NauSys and MMK
-- spell some marinas so differently that the base never matches: Fountaine Pajot Saona 47 "Desafinado" is 481 (NauSys,
-- "Trogir, Yachtclub Seget (Marina Baotić)") and 13163 (MMK, "Marina Baotic"). Every listing and sitemap carried both,
-- while the boat page shows ONE copy for either URL - the twin-canonical manual group [481, 13163]
-- (application-prod.yml `twin-canonical.manual-groups`, coverage-first) - so the sitemap's -13163 declared -481 as its
-- canonical (and the winner moves: 13163 on 24.6., 481 on 1.10.).
--
-- yacht_twin_manual_pair holds such pairs. The matview pairs them like a name-rule pair and picks the copy shown by
-- the same stable rule (a week left to sell, then directly bookable, then the lower id), so the listings and the
-- sitemaps show one copy and the hidden one carries listingCanonicalSlug - the boat page canonical every site reads.
-- Keep it in step with `twin-canonical.manual-groups`: a group there should be a pair here.
--
-- No foreign key to yacht: a FK would take SHARE ROW EXCLUSIVE on yacht, which the syncs update all day, and a
-- lock timeout here fails the API start. A deleted yacht simply stops pairing (the matview joins yacht).
-- Yacht ids differ between databases (local vs production), so the seed is guarded by the production names and
-- build year verified on 1.10.2026 - a mismatch inserts nothing. The matview is rebuilt (DROP + CREATE WITH DATA, well
-- under a second, as V9_69); the short lock_timeout covers a concurrent REFRESH from YachtSearchViewRefresher.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS yacht_twin_manual_pair (
    yacht_id      BIGINT      NOT NULL,
    twin_yacht_id BIGINT      NOT NULL,
    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (yacht_id, twin_yacht_id),
    CONSTRAINT yacht_twin_manual_pair_not_self CHECK (yacht_id <> twin_yacht_id)
);

INSERT INTO yacht_twin_manual_pair (yacht_id, twin_yacht_id, note)
SELECT a.id, b.id, v.note
FROM (VALUES
      (481, 13163, 'desafinado', 'FP Saona 47 Desafinado, Marina Baotic: NauSys 481 / MMK 13163 (twin-canonical manual group)')
     ) AS v (a_id, b_id, name_key, note)
JOIN yacht a ON a.id = v.a_id AND lower(btrim(a.name)) = v.name_key
JOIN yacht b ON b.id = v.b_id AND lower(btrim(b.name)) = v.name_key AND b.build_year IS NOT DISTINCT FROM a.build_year
ON CONFLICT DO NOTHING;

DROP MATERIALIZED VIEW IF EXISTS public.yacht_listing_twin;

CREATE MATERIALIZED VIEW public.yacht_listing_twin AS
WITH future AS (
    -- what the undated listing can show (offer from today, not UNAVAILABLE) and whether a week is left to sell
    SELECT o.yacht_id,
           bool_or(o.status IN ('FREE', 'OPTION', 'OPTION_WAITING')) AS sellable
    FROM offer o
    WHERE o.date_from >= CURRENT_DATE
      AND o.status <> 'UNAVAILABLE'
    GROUP BY o.yacht_id
),
source AS (
    SELECT em.system_id AS yacht_id, min(em.external_system_id) AS system
    FROM external_mapping em
    WHERE em.type = 'Yacht'
    GROUP BY em.system_id
),
named AS (
    SELECT y.*,
           regexp_replace(lower(btrim(y.name)), '[^[:alnum:]]', '', 'g') AS name_key,
           regexp_replace(lower(COALESCE(m.name, '')), '[^[:alnum:]]', '', 'g') AS model_key,
           regexp_replace(lower(COALESCE(mf.name, '') || COALESCE(m.name, '')), '[^[:alnum:]]', '', 'g') AS full_model_key
    FROM yacht y
    LEFT JOIN model m         ON m.id = y.model_id
    LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
),
listed AS (
    SELECT y.id,
           y.agency_id,
           s.system,
           y.vessel_type,
           y.length,
           y.build_year,
           y.name_key,
           l.country_code || ':' || translate(lower(btrim(split_part(l.name, ' | ', 1))), 'šžčćđ', 'szccd') AS base_key,
           f.yacht_id IS NOT NULL AS listable,
           COALESCE(f.sellable, false) AS sellable,
           NOT (COALESCE(y.option_approval, false) OR COALESCE(a.inquiry_only, false)) AS bookable
    FROM named y
    JOIN agency a      ON a.id = y.agency_id AND a.active AND NOT a.availability_blocked
    JOIN location l    ON l.id = y.location_id
    LEFT JOIN future f ON f.yacht_id = y.id
    LEFT JOIN source s ON s.yacht_id = y.id
    WHERE y.entry_type = 'EXTERNAL'
      AND y.sys_active
      -- a real boat name: letters, 3+ characters, not the model, not a placeholder
      AND length(y.name_key) >= 3
      AND y.name_key ~ '[a-z]'
      AND y.name_key NOT IN (y.model_key, y.full_model_key, 'noname', 'unnamed', 'tba', 'tbd', 'new', 'yacht', 'boat')
),
pair AS (
    -- the name rule: same name, same base, same vessel type, length within 0.5 m and build year within 1, and a
    -- different channel - another agency, or the same agency through the other partner system
    SELECT y.id AS yacht_id, c.id AS twin_id
    FROM listed y
    JOIN listed c
      ON c.name_key = y.name_key
     AND c.base_key = y.base_key
     AND (c.agency_id <> y.agency_id OR c.system IS DISTINCT FROM y.system)
     AND c.vessel_type = y.vessel_type
     AND c.id <> y.id
     AND (c.length IS NULL) = (y.length IS NULL)
     AND (c.length IS NULL OR abs(c.length - y.length) <= 0.5)
     AND (c.build_year IS NULL) = (y.build_year IS NULL)
     AND (c.build_year IS NULL OR abs(c.build_year - y.build_year) <= 1)
    UNION
    -- hand-verified pairs the rule cannot match (yacht_twin_manual_pair), both ways
    SELECT m.yacht_id, m.twin_yacht_id FROM yacht_twin_manual_pair m WHERE m.yacht_id <> m.twin_yacht_id
    UNION
    SELECT m.twin_yacht_id, m.yacht_id FROM yacht_twin_manual_pair m WHERE m.yacht_id <> m.twin_yacht_id
),
better AS (
    SELECT y.id AS yacht_id,
           c.id AS canonical_yacht_id,
           row_number() OVER (PARTITION BY y.id ORDER BY c.sellable DESC, c.bookable DESC, c.id) AS rn
    FROM pair p
    JOIN listed y ON y.id = p.yacht_id
    JOIN listed c ON c.id = p.twin_id
    -- the copy shown: listable, and first by the stable rule above (false < true; the lower id wins a tie)
    WHERE c.listable
      AND (c.sellable, c.bookable, -c.id) > (y.sellable, y.bookable, -y.id)
),
pick AS (
    SELECT yacht_id, canonical_yacht_id
    FROM better
    WHERE rn = 1
)
-- Pairing is not transitive (length within 0.5 m, year within 1): when A~B and B~C but not A~C, C's best copy B is
-- itself hidden behind A. One more hop names the copy that is shown.
SELECT p.yacht_id, COALESCE(q.canonical_yacht_id, p.canonical_yacht_id) AS canonical_yacht_id
FROM pick p
LEFT JOIN pick q ON q.yacht_id = p.canonical_yacht_id;

CREATE UNIQUE INDEX IF NOT EXISTS yacht_listing_twin_yacht_uidx ON public.yacht_listing_twin (yacht_id);

GRANT SELECT ON public.yacht_listing_twin TO boat4you_app;
GRANT SELECT ON public.yacht_twin_manual_pair TO boat4you_app;
