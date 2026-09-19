-- 19.9.2026 booking incident: partner-owned extra text is longer than our columns.
--   * external_reservation_extras.name varchar(200): MMK's obligatory "Charter pack (Includes: …)" is 222 chars.
--     Bean validation threw at flush AFTER the partner option existed -> 502 on every retry. 37,485 offer extras
--     on 35,142 offers are over 200 chars. (87a674b cut the name as a stop-gap; this keeps the full wording.)
--   * reservation_extras.yacht_extras_key varchar(255): extrasKey() falls back to the partner NAME when the extra
--     has no catalogue mapping. 17 obligatory extras of 264 chars on MMK yacht 7828 made 33 free offers unbookable,
--     failing in createReservationFlow before the partner was even called.
-- Dropping a varchar length limit is a catalogue-only change in PostgreSQL (no table rewrite, no index or view on
-- either column - checked on production 19.9.2026). Same unbounded varchar that reservation_extras.name already is.
-- Flyway's role has no lock_timeout of its own. A waiting ACCESS EXCLUSIVE request queues every later reader behind
-- it, so give up fast instead: the migration is one transaction, a timeout rolls it back cleanly and the API's
-- systemd restart simply tries again a few seconds later.
SET LOCAL lock_timeout = '5s';

ALTER TABLE public.external_reservation_extras ALTER COLUMN name TYPE varchar;
ALTER TABLE public.reservation_extras ALTER COLUMN yacht_extras_key TYPE varchar;
