-- Same physical marina imported twice under names no rule can pair (26.9.2026 audit B13 / B14).
--
-- The partner catalogues import one marina once per provider. Most pairs are found by name: same spelling up to
-- diacritics ("Marina Kastela" / "Marina Kaštela") or one name inside the other (MMK "Marina Baotić" / NauSys "Trogir,
-- Yachtclub Seget (Marina Baotić)") - see MarinaPlaces. A few read differently in each catalogue, so the Greece facts
-- block listed "D-Marin Marina Lefkas 260" and "Lefkas, D-Marin 154" as two main bases (and "D-Marin Marina Gouvia" /
-- "Corfu, Gouvia Marina"), and each landing showed half of the marina's fleet. This table holds such pairs; the
-- location autocomplete merge (compound did "l-1865,l-259"), the search's l- resolution and the charter facts read it.
-- The syncs never write it, so a nightly run cannot undo a pair.
--
-- Location ids differ between databases (local vs production), so every seeded pair is guarded by the production
-- names verified on 26.9.2026 - a mismatch inserts nothing. Idempotent. A new small table: no lock on existing ones
-- beyond the FK's SHARE ROW EXCLUSIVE on location, hence the short lock_timeout (a timeout rolls back cleanly and the
-- restart retries).
--
-- The two IMMUTABLE functions are the SQL side of MarinaPlaces.sameArea (the search's l- resolution uses them): name
-- rules pair two rows, the data may veto the pair - both rows with coordinates further apart than max_km, or (without
-- coordinates) two known cities that differ. "Marina Frapa" (Rogoznica) sits inside "Marina Frapa Dubrovnik" by name,
-- 170 km away, and the merged row put Rogoznica boats on the Dubrovnik landing.
SET LOCAL lock_timeout = '5s';

-- lower case, common diacritics stripped, letters and digits only ("Kaštel Gomilica" -> "kastelgomilica")
CREATE OR REPLACE FUNCTION location_fold_text(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT regexp_replace(translate(lower(COALESCE(t, '')), 'šžčćđáàâäãåéèêëíìîïóòôöõúùûüçñ', 'szccdaaaaaaeeeeiiiiooooouuuucn'),
                      '[^a-z0-9]', '', 'g')
$$;

-- whether a row carries any location data (usable coordinates or a city)
CREATE OR REPLACE FUNCTION location_has_area(lat numeric, lon numeric, city text) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT (lat IS NOT NULL AND lon IS NOT NULL AND NOT (lat = 0 AND lon = 0)) OR location_fold_text(city) <> ''
$$;

-- whether the data allows two name-paired rows to be one place
CREATE OR REPLACE FUNCTION location_same_area(lat1 numeric, lon1 numeric, city1 text,
                                              lat2 numeric, lon2 numeric, city2 text, max_km numeric) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT CASE
           WHEN lat1 IS NOT NULL AND lon1 IS NOT NULL AND lat2 IS NOT NULL AND lon2 IS NOT NULL
               AND NOT (lat1 = 0 AND lon1 = 0) AND NOT (lat2 = 0 AND lon2 = 0)
               THEN 2 * 6371 * asin(least(1.0, sqrt(power(sin(radians(lat2 - lat1) / 2), 2)
                   + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2)))) <= max_km
           WHEN location_fold_text(city1) <> '' AND location_fold_text(city2) <> ''
               THEN position(location_fold_text(city1) IN location_fold_text(city2)) > 0
                   OR position(location_fold_text(city2) IN location_fold_text(city1)) > 0
           ELSE true
       END
$$;

CREATE TABLE IF NOT EXISTS location_same_place (
    location_id         BIGINT      NOT NULL REFERENCES location (id) ON DELETE CASCADE,
    same_as_location_id BIGINT      NOT NULL REFERENCES location (id) ON DELETE CASCADE,
    note                TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (location_id, same_as_location_id),
    CONSTRAINT location_same_place_not_self CHECK (location_id <> same_as_location_id)
);

INSERT INTO location_same_place (location_id, same_as_location_id, note)
SELECT a.id, b.id, v.note
FROM (VALUES
      (1865, 'D-Marin Marina Lefkas', 259, 'Lefkas, D-Marin', 'D-Marin Lefkas (Lefkada town): MMK and NauSys rows'),
      (1676, 'D-Marin Marina Gouvia', 154, 'Corfu, Gouvia Marina', 'D-Marin Gouvia (Corfu): MMK and NauSys rows')
     ) AS v (a_id, a_name, b_id, b_name, note)
JOIN location a ON a.id = v.a_id AND btrim(split_part(a.name, ' | ', 1)) = v.a_name
JOIN location b ON b.id = v.b_id AND btrim(split_part(b.name, ' | ', 1)) = v.b_name
ON CONFLICT DO NOTHING;
