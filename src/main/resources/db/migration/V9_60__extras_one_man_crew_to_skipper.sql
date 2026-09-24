-- 24.9.2026: partner extras called "One man crew" are the skipper - show them as "Skipper".
--   Two partner agencies name the skipper that way: NSS Charter (agency 1526, "One man crew (Caribbean)", 36
--   yacht_extras rows) and Marina Yacht Charter (1041, "One man crew (+ boarding)" 15 rows and "One man crew
--   (+ boarding) : Compensation due directly to the Skipper, ..." 27 rows) -> expected 36 + 42 = 78 yacht_extras rows,
--   0 offer_extras rows today. It did not read as a skipper on the customer extras tab or in e-mails, and the admin
--   offer builder (finds the skipper row by the keyword "skipper") did not find it at all.
-- The sync writes the name only when it INSERTS a row (updates match on externalId and leave the name alone), so new
-- rows go through ExtraNameNormalizer and this migration renames the rows that already exist. Same rule as the
-- Kotlin object: whole words, case-insensitive, "one man crew" / "one-man crew" / "one  man crew" / "oneman crew" ->
-- "Skipper", prefix and suffix kept; only a renamed name gets its whitespace runs collapsed and its ends trimmed.
-- Idempotent: a renamed row no longer matches the WHERE. Not touched on purpose: `extras` (our own catalogue labels,
-- seeded by R__1_04 - no such value), reservation_extras / external_reservation_extras (booking-time snapshots).
-- Neither table has an index on name (checked in the migrations), so each UPDATE is one sequential scan - offer_extras
-- (~4M live rows) takes seconds at startup and changes nothing today; no row lock is taken on a row that does not match.
SET LOCAL lock_timeout = '5s';

UPDATE public.yacht_extras
   SET name = regexp_replace(
                regexp_replace(
                  regexp_replace(name, '\mone[[:space:]-]*man[[:space:]-]*crew\M', 'Skipper', 'gi'),
                  '[[:space:]]{2,}', ' ', 'g'),
                '^[[:space:]]+|[[:space:]]+$', '', 'g')
 WHERE name ~* '\mone[[:space:]-]*man[[:space:]-]*crew\M';

UPDATE public.offer_extras
   SET name = regexp_replace(
                regexp_replace(
                  regexp_replace(name, '\mone[[:space:]-]*man[[:space:]-]*crew\M', 'Skipper', 'gi'),
                  '[[:space:]]{2,}', ' ', 'g'),
                '^[[:space:]]+|[[:space:]]+$', '', 'g')
 WHERE name ~* '\mone[[:space:]-]*man[[:space:]-]*crew\M';
