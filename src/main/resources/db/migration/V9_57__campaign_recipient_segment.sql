-- GUEST (sailed with us before — "let's go again") vs PROSPECT (inquired) —
-- the campaign email speaks differently to each (Mario, 8.9.2026).
ALTER TABLE campaign_recipient
    ADD COLUMN segment VARCHAR(31) NOT NULL DEFAULT 'PROSPECT';
