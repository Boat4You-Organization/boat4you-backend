-- audit B2(b): opt-out for the birthday / courtesy email.
--
-- The legitimate-interest model (Mario decision 1.5.2026, documented in
-- BirthdayEmailJob) is kept: we still send by default. This adds a proper
-- one-click unsubscribe so recipients can object (GDPR Art. 21 / ePrivacy
-- soft opt-in), and the promotional "/search" CTA is removed from the email
-- itself (separate change) so the message is a pure courtesy greeting.
--
-- marketing_opt_out: FALSE = still receives (default, legitimate interest);
--   the unsubscribe link flips it to TRUE and the birthday cron skips them.
-- unsubscribe_token: opaque per-user handle embedded in the one-click link.
--   Backfilled for existing rows here; generated in UserEntity @PrePersist
--   for new rows. Indexed for the unsubscribe lookup.
ALTER TABLE users ADD COLUMN marketing_opt_out BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN unsubscribe_token VARCHAR(36);

UPDATE users SET unsubscribe_token = gen_random_uuid()::text WHERE unsubscribe_token IS NULL;

CREATE INDEX idx_users_unsubscribe_token ON users (unsubscribe_token);
