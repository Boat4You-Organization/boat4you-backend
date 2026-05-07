-- Password-reset token TTL (OWASP Forgot Password cheatsheet):
--   reset tokens must be time-limited so a leaked / forgotten code can't
--   be replayed weeks later. Without a timestamp, `passwordResetCode` lived
--   forever until either a successful reset (clears it) or the user
--   requested another reset (overwrites it).
--
-- We keep it nullable; rows where it's null are treated as having no active
-- reset request. UserAuthService rejects codes older than the configured
-- TTL (default 60 minutes) inside `checkPasswordResetValidity`.
ALTER TABLE users
    ADD COLUMN password_reset_code_issued_at TIMESTAMP;
