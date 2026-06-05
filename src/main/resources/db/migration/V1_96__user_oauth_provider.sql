-- Social login (Google first). Mark accounts linked to an external identity
-- provider so we can (a) recognise a "connected with Google" account and
-- (b) re-match by the provider's stable subject id if the user ever changes
-- their email at the provider. Both columns nullable — existing email/password
-- accounts keep provider = NULL.
--
-- NOTE: `password` deliberately stays NOT NULL. A social-only account is created
-- with a random, unguessable BCrypt hash (the user never learns it); they can
-- run "forgot password" later to additionally enable email login. This avoids a
-- nullable-password change to a core column that is read in many places.
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider VARCHAR(31);
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_id VARCHAR(255);

-- Look up an account by its provider identity (provider + stable subject).
CREATE INDEX IF NOT EXISTS idx_users_provider_identity ON users (provider, provider_id);
