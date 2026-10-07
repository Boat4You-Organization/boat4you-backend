-- The listing that replaced a retired boat page (7.10.2026, Mario).
--
-- A partner boat that is switched off (yacht.sys_active = false) keeps its old URL in search engines and on other
-- sites, while the SAME physical boat is usually live under another id: the partner or the agency imported it again
-- (Bing, 7.10.: /boat/lagoon-bnteau-lagoon-42-4-2-cab-masterpiece-4066 -> 404, the boat is live as 11681). Production
-- 7.10.: 7,061 retired partner boats, 2,768 of them with exactly one live listing of the same boat.
--
-- yacht_successor names, for a retired boat, the live listing of the same boat: GET /public/yachts/{idOrSlug} keeps
-- answering 1502 "Yacht is not active" for the old id and adds successorSlug / successorId, so the web sites can
-- redirect permanently. The rule, the set-based SQL and why a table (not a matview): YachtSuccessorSql. Written only by
-- YachtSuccessorJob on the scheduler node (cusma3, after the nightly partner syncs, and once at startup when empty),
-- which replaces every row in one transaction; the API node only reads one row by old_id on the 1502 path.
--
-- No foreign key (as V9_70 / V9_71): a FK would take SHARE ROW EXCLUSIVE on yacht, which the syncs write all day. Yacht
-- ids are never reused, and the lookup re-checks that the successor is still served. A new, empty table: no lock on any
-- existing relation. Idempotent. Flyway runs it on cusma2; deploy cusma2 before cusma3 (the job needs the table).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS public.yacht_successor (
    old_id      BIGINT      PRIMARY KEY,
    new_id      BIGINT      NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT yacht_successor_not_self CHECK (old_id <> new_id)
);

CREATE INDEX IF NOT EXISTS yacht_successor_new_id_idx ON public.yacht_successor (new_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.yacht_successor TO boat4you_app;
