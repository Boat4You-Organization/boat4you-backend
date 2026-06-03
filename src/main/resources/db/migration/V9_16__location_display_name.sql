-- Compute-at-display for marina labels (Mario 3.6.2026). STOP storing "name | city" in
-- location.name. The catalogue syncs (NauSys + MMK) own location.name/city and rewrite the
-- whole catalogue on every nightly run, so a " | city" baked into name kept getting reverted
-- overnight (chased twice: first only NauSys was guarded, then MMK). Fix the class, not the
-- symptom — keep name bare (sync-owned) and DERIVE the label in ONE place the sync can never
-- touch: a STORED generated column. Postgres recomputes it from name+city on every write, so it
-- always tracks the synced data, never needs a manual re-apply, and new marinas get it for free.
--
-- Read paths all reference location.display_name: matview yacht_search_view (R__1_03),
-- all_location_view (R__1_00), the replacement-search native query (YachtRepository), and the
-- Location entity via @Formula. name stays bare for matching/dedup (findMarinasByFoldedName).

-- 1) Undo the baked-in suffix so name is bare again. The " | " separator was introduced by us
--    (V9_13); partner names never contain it, so splitting on the first " | " is a safe inverse.
UPDATE location
SET name = btrim(split_part(name, ' | ', 1))
WHERE position(' | ' in name) > 0;

-- 2) The single source of truth for the marina label. STORED so the matview/view/native queries
--    can reference it as a plain column and JPA can read it. All functions used are immutable.
ALTER TABLE location
    ADD COLUMN IF NOT EXISTS display_name text
    GENERATED ALWAYS AS (
        name || CASE WHEN NULLIF(btrim(city), '') IS NOT NULL THEN ' | ' || btrim(city) ELSE '' END
    ) STORED;
