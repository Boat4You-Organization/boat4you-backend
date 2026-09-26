-- Region names are the landing key and must not follow partner renames (26.9.2026 audit B01, critical).
--
-- The web's region landings are addressed by the region NAME (/search?destinations=zadar), and the URL, canonical,
-- sitemap and index gate all derive from it. Both catalogue syncs overwrote region.name on every run
-- (MmkCatalogueSyncService: region.name = sailing area name; NauSysCatalogueSyncService: region.name = textEN) and four
-- Croatian regions are mapped by BOTH partners, so their name flipped with whichever sync ran last: r-3 "Zadar" /
-- "Zadar region", r-4 "Šibenik" / "Šibenik region", r-5 "Split" / "Split region", r-193 "Istria / Kvarner" /
-- "Kvarner". On 26.9. 144 sitemap URLs turned noindex at 09:06 and back by 09:19; the unresolvable name rendered the
-- whole 12,100-boat catalogue.
--
-- From now on region.name is canonical: a sync sets it only when it CREATES a region, and records every partner
-- spelling here instead. The location list (location_view, R__1_07) exposes the aliases so the web can resolve an old
-- or partner spelling and 301 it to the canonical URL.
--
-- Canonical names chosen: the names that were live on 25.9. (evening sitemap, internal links, GSC) and that every
-- sister-site config lists as a member ("Zadar", "Šibenik", "Split", "Istria / Kvarner"). Each pin is guarded by the
-- region's country and its two known spellings, so a different database (local ids) changes nothing. Idempotent.
-- A new small table plus an UPDATE of at most 4 region rows: short lock_timeout, a timeout rolls back cleanly and the
-- restart retries (deploy inside a quiet window, like V9_63/V9_64).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS region_alias (
    id           BIGSERIAL    PRIMARY KEY,
    region_id    INT          NOT NULL REFERENCES region (id) ON DELETE CASCADE,
    alias        VARCHAR(100) NOT NULL,
    -- MMK / NAUSYS = the partner's current name for the area; HISTORIC = a name the region carried before V9_68
    source       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS region_alias_region_alias_uidx ON region_alias (region_id, lower(alias));
CREATE INDEX IF NOT EXISTS region_alias_alias_idx ON region_alias (lower(alias));

-- Both spellings of the pinned regions stay resolvable (as aliases) before the name is fixed.
INSERT INTO region_alias (region_id, alias, source)
SELECT r.id, x.alias, 'HISTORIC'
FROM (VALUES
      (3, 'Zadar', 'Zadar region', 'HR'),
      (4, 'Šibenik', 'Šibenik region', 'HR'),
      (5, 'Split', 'Split region', 'HR'),
      (193, 'Istria / Kvarner', 'Kvarner', 'HR')
     ) AS pin (id, canonical, other, country)
JOIN region r ON r.id = pin.id AND r.country_code = pin.country AND lower(r.name) IN (lower(pin.canonical), lower(pin.other))
CROSS JOIN LATERAL (VALUES (pin.canonical), (pin.other)) AS x (alias)
ON CONFLICT DO NOTHING;

UPDATE region r
SET name = pin.canonical
FROM (VALUES
      (3, 'Zadar', 'Zadar region', 'HR'),
      (4, 'Šibenik', 'Šibenik region', 'HR'),
      (5, 'Split', 'Split region', 'HR'),
      (193, 'Istria / Kvarner', 'Kvarner', 'HR')
     ) AS pin (id, canonical, other, country)
WHERE r.id = pin.id
  AND r.country_code = pin.country
  AND lower(r.name) IN (lower(pin.canonical), lower(pin.other))
  AND r.name IS DISTINCT FROM pin.canonical;

-- Every other region's current name is where its history starts.
INSERT INTO region_alias (region_id, alias, source)
SELECT r.id, btrim(r.name), 'HISTORIC'
FROM region r
WHERE NULLIF(btrim(r.name), '') IS NOT NULL
ON CONFLICT DO NOTHING;
