-- Free-text amenities + toys for custom yachts.
--
-- Custom (admin-managed) yachts are now described with two free-form
-- copy/paste blocks instead of the predefined Equipment dropdown the
-- external yachts use. Owners send specs as a multi-line list ("Fridge",
-- "Bed sizes 1.60 x 2.00 m", "Sea bob (2)", …) — admin pastes it once,
-- the public detail page renders each line as a checkmark list item.
--
-- Single language: the source text is shared across all locales, so one
-- column per category is enough. TEXT type because individual entries
-- can run long ("Filtration system for drinking water (mineral & sparkling
-- water)") and the whole block is unbounded.
ALTER TABLE public.custom_yacht_details
    ADD COLUMN IF NOT EXISTS amenities_text TEXT,
    ADD COLUMN IF NOT EXISTS toys_text TEXT;
