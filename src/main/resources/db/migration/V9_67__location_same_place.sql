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
SET LOCAL lock_timeout = '5s';

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
