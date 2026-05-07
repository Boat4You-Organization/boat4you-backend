-- Add Polish and Dutch to the language table.
--
-- Backend already knows about both via LanguageEnum (PL/NL entries), and
-- the public FE renders 9 locales — but the language table seeded in
-- V1_12 only contains the original 7 (en/de/fr/es/it/pt/hr). Without these
-- rows, custom-yacht descriptions in PL/NL submitted from the admin form
-- can't be persisted (FK to language.id fails).
--
-- IDs continue the V1_12 sequence (1..7 → 8..9). Use ON CONFLICT to keep
-- the migration idempotent if a hand-seed already added the rows.
--
-- Note: we don't bump language_id_seq here because the migration runs as
-- the boat4you_owner role which does not own the sequence (got "permission
-- denied for sequence" on the original setval). All inserts into `language`
-- so far (V1_12 + this migration) use explicit IDs, and the language set
-- is effectively closed (LanguageEnum has only these 9 entries), so the
-- sequence falling behind is harmless. If a future migration needs a fresh
-- auto-id, bump the sequence as a superuser-only one-off then.
INSERT INTO public.language (id, locale, name) VALUES (8, 'pl', 'Polish')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.language (id, locale, name) VALUES (9, 'nl', 'Dutch')
ON CONFLICT (id) DO NOTHING;
