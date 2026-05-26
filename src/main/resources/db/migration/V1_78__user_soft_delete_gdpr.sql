-- GDPR right-to-erasure (Article 17) support: soft-delete s anonimizacijom.
--
-- Strategy: kad korisnik traži "delete my account", ne brišemo physical row
-- iz `users` tablice (FK constraints na reservation_flow / custom_offer /
-- role_assignments / tokens — neki su NOT NULL, klijent ima pravnu obvezu
-- zadržati booking trail za partner agency reconciliation + računovodstvene
-- propise). Umjesto toga:
--   1. anonimiziramo PII (name, surname → "Deleted User"; email → unique
--      `deleted-{id}@boat4you-deleted.invalid`; phone/address/city/country → null);
--   2. brišemo authentikacijske artefakte (password → random hash, tokens
--      tablica → DELETE all rows for user, password reset/verification codes
--      → null);
--   3. brišemo role_assignments (privilegije se gase odmah);
--   4. postavljamo `deleted_at = now()` kao tombstone marker.
--
-- Admin panel može filtrirat aktivne korisnike s `WHERE deleted_at IS NULL`,
-- a deleted users se prikazuju kao "[Deleted user]" u rezervacijskoj listi
-- preko Mappers koji čitaju `deleted_at`.
ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX users_deleted_at_idx ON users (deleted_at)
    WHERE deleted_at IS NOT NULL;

-- Update partial UNIQUE on email to ignore anonymized addresses — bez ovog,
-- ako se isti user 2x obriše (drugi račun s istim email-om kasnije + delete),
-- imali bismo collision na `deleted-{id}@boat4you-deleted.invalid` format.
-- Format je inherently unique (suffix is user.id), pa partial UNIQUE samo
-- štiti od duplicate **active** email-ova.
DROP INDEX IF EXISTS users_email_idx;
DROP INDEX IF EXISTS users_email_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

CREATE UNIQUE INDEX users_email_active_uk ON users (email)
    WHERE deleted_at IS NULL;
