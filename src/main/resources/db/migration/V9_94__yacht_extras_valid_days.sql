-- 22.10.2026: MMK limits a yacht-level extra to charter lengths (`validDaysFrom` / `validDaysTo`, in days): Sailing
-- Sicily's Bali 5.4 fleet (Therasia 11907, Osteodes 11903, Stronghyle 11919) has "Luxury Pack Bali 5.4" 0-7 days,
-- "Luxury Pack 2weeks" 8-14 days and every APA season variant twice the same way. The sync dropped the bounds, so a
-- one-week booking listed and summed both packs (10,400 + 5,200 €). NULL = partner gave no bound (never excludes).
ALTER TABLE yacht_extras
    ADD COLUMN IF NOT EXISTS valid_days_from SMALLINT,
    ADD COLUMN IF NOT EXISTS valid_days_to   SMALLINT;
