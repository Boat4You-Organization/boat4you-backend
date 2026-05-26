-- Country reference data moved to versioned migration V1_76_5__seed_countries.sql
-- so it is seeded BEFORE the region migrations (V1_77+) that FK-reference it.
-- Retained as a no-op repeatable for environments that previously recorded
-- R__1_07; Flyway re-runs it harmlessly whenever this checksum changes.
SELECT 1;
