-- Evidence store for MmkFreeOfferReverifyService (22.9.2026): a FREE weekly offer MMK no longer quotes.
-- Why a table: (1) the second confirmation must come from a DIFFERENT day, not 150 ms later (a 200-[] window at
-- MMK looks exactly like a withdrawal to two back-to-back calls); (2) every hidden row must be traceable and
-- revertible (the 21.9. one-off left ops_offer_phantom_backup_20260921 - this is the permanent version).
-- One row per (yacht, week). first_empty_on = the day the exact-date call first came back empty; hidden_on = the
-- day the second daily run agreed and the 7-night rows were flipped to UNAVAILABLE. A row is deleted the moment
-- MMK quotes the week again (probe or the 09:25 UNAVAILABLE->FREE reverifier), so its presence means "not sold".
CREATE TABLE IF NOT EXISTS mmk_free_week_strike (
    yacht_id        BIGINT  NOT NULL REFERENCES yacht (id) ON DELETE CASCADE,
    date_from       DATE    NOT NULL,
    date_to         DATE    NOT NULL,
    first_empty_on  DATE    NOT NULL,
    hidden_on       DATE,
    hidden_rows     INT     NOT NULL DEFAULT 0,
    PRIMARY KEY (yacht_id, date_from, date_to)
);
CREATE INDEX IF NOT EXISTS idx_mmk_free_week_strike_hidden ON mmk_free_week_strike (hidden_on);
