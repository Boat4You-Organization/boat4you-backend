-- Phase 1 security. Device-aware sessions: tag each token row with the device
-- (user_agent/ip), when it was last seen, and a session_group UUID shared by the
-- access+refresh pair minted in one login (so the UI shows one entry per device).
-- All nullable -- legacy tokens stay null and age out within the 3-day refresh TTL.
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS user_agent VARCHAR(512);
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS ip_address VARCHAR(64);
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP;
ALTER TABLE tokens ADD COLUMN IF NOT EXISTS session_group VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_tokens_session_group ON tokens (session_group);

-- "Has the user chosen their own password?" Existing email accounts have; social
-- accounts created by the Google-login launch got a random one they never set.
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_set BOOLEAN NOT NULL DEFAULT true;
UPDATE users SET password_set = (provider IS NULL);
