-- NauSys ships per-yacht equipment with three fields we never persisted:
--   highlight (boolean) — partner-flagged premium item, rendered with the
--                         yellow row treatment in the partner UI (e.g. Generator,
--                         Electric winch, Honda outboard).
--   quantity (numeric)  — count when more than one unit is on board, e.g.
--                         "4 x Electric toilet" or "2 x Refrigerator".
--   comment (i18n text) — free-text qualifier per item, e.g. "Honda 20hp",
--                         "Air conditioning in all cabins and lounge", "Dolce
--                         Gusto", "automatic" (life jackets), "at the flybridge"
--                         (Hard Top Bimini), "2 x inside, 1 x outside".
--
-- MMK doesn't have a true highlight flag. It does have a free-text `value`
-- field per equipment item ("130 L", "7,6 kw", "only in cabins") which we
-- store in the same `comment` column for parity. quantity stays NULL for
-- MMK rows.

ALTER TABLE yacht_equipment
    ADD COLUMN highlight BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN quantity NUMERIC(10, 2),
    ADD COLUMN comment TEXT;
