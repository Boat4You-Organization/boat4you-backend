-- Profile-page enhancements:
--   * birthday — used for the annual birthday-greeting email (cron-driven,
--     opt-out via account delete only since we have no marketing toggle).
--     LocalDate (not Instant) because we only care about month + day; year
--     is informational ("user born 1985"), the cron fires by MM-DD match.
--   * last_login_at — surfaced on /my-profile as "Last login" + used by
--     the security audit story (next on the roadmap). Updated from
--     UserAuthService.login on every successful authentication.
ALTER TABLE users
    ADD COLUMN birthday DATE,
    ADD COLUMN last_login_at TIMESTAMP;

-- Birthday email cron scans by month + day, so an index on the tuple is
-- the right shape. Postgres `extract(MONTH FROM birthday)` expressions
-- aren't directly indexable without a functional index — easier to filter
-- in the cron with `WHERE birthday IS NOT NULL` then hash by month+day in
-- application code on the small daily batch. Keep it simple, no fancy
-- index here.
