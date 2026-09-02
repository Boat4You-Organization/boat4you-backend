-- Minimal schema for NausysOfferSyncRetryRepositoryTest: the full Flyway chain cannot run on an
-- empty database (V9_18 is a prod data-fix that assumes seeded regions), so only the FK target
-- of nausys_offer_sync_retry is created here; V9_54 itself is executed by the test.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boat4you_owner') THEN
    CREATE ROLE boat4you_owner LOGIN PASSWORD 'testpass';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boat4you_app') THEN
    CREATE ROLE boat4you_app LOGIN PASSWORD 'testpass';
  END IF;
END$$;

CREATE TABLE IF NOT EXISTS agency (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);
