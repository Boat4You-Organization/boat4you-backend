-- V1_93: Trigram + functional indexes for leading-wildcard ILIKE searches.
--
-- Phase E. Addresses the F2-023 / F2-024 / F2-033 / F2-034 / F2-040 cluster:
-- every paginated admin search and the public location autocomplete were
-- doing `LOWER(column) LIKE '%query%'` against B-tree-indexed (or
-- unindexed) columns. Postgres cannot use a B-tree index for leading-
-- wildcard `%query%` patterns, so each search degraded to a full table
-- scan. Inconsequential on dev seed data; on prod-sized tables it shows
-- up as admin-list latency.
--
-- pg_trgm is a built-in Postgres extension (no external dep) that
-- decomposes strings into 3-character grams; a GIN index over those
-- grams lets the planner satisfy `column LIKE '%query%'` in roughly the
-- time a B-tree handles equality. The indexes are FUNCTIONAL — they
-- index the same `LOWER(...)` expression the queries evaluate — so the
-- planner picks them up automatically (no query change needed).
--
-- Trade-off: GIN indexes are ~3-5× larger than B-tree, and each INSERT/
-- UPDATE on the indexed column does ~5-10% more work. Both costs are
-- justified by the search-side speedup on a search-heavy admin
-- workflow + public location autocomplete on every keystroke.
--
-- Deploy notes for Mario:
-- * If any of these tables has been growing for years on prod, the
--   `CREATE INDEX` statements can take a while. They take a table lock
--   (without CONCURRENTLY). Acceptable here because Flyway runs at
--   startup before traffic; on a hot-prod rebuild use
--   `CREATE INDEX CONCURRENTLY` manually. We are intentionally NOT
--   using CONCURRENTLY in Flyway because it cannot run inside a
--   transaction and Flyway wraps each migration in one by default.
-- * Verify `pg_extension` membership for boat4you_owner after deploy
--   if Mario sees a "permission denied for CREATE EXTENSION" — the
--   extension creation needs superuser one-off, or
--   `CREATE EXTENSION pg_trgm` pre-installed by the DBA.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- F2-023: admin Inquiry list search across email + name + surname.
-- Three trigram indexes, one per column the OR-LIKE touches; planner
-- picks the cheapest depending on selectivity.
CREATE INDEX IF NOT EXISTS idx_inquiry_email_trgm
    ON public.inquiry USING gin (LOWER(email) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_inquiry_name_trgm
    ON public.inquiry USING gin (LOWER(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_inquiry_surname_trgm
    ON public.inquiry USING gin (LOWER(surname) gin_trgm_ops);

-- F2-024: `countByEmailIgnoreCaseAndIdNot` is an equality check
-- (`LOWER(email) = LOWER(:email)`), not a wildcard scan — a regular
-- functional B-tree index is the right fit (much smaller than GIN and
-- the planner uses it for equality).
CREATE INDEX IF NOT EXISTS idx_inquiry_email_lower
    ON public.inquiry (LOWER(email));

-- F2-033: public `LocationView.findByNameAndIdsNotIn` autocomplete.
-- LocationView is a Postgres VIEW (location_view in R__1_07) that
-- inlines `name || ' ' || COALESCE(city, '')` as `search_filed`. We
-- index the same expression on the underlying `location` table so the
-- planner can use it when the view is inlined. Region + country
-- branches of the view fall back to plain `name` — single-column
-- trigram on each suffices.
CREATE INDEX IF NOT EXISTS idx_location_search_trgm
    ON public.location USING gin ((LOWER(name || ' ' || COALESCE(city, ''))) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_region_name_trgm
    ON public.region USING gin (LOWER(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_country_name_trgm
    ON public.country USING gin (LOWER(name) gin_trgm_ops);

-- F2-034: admin Manufacturer / Model / Agency name search + custom
-- yacht view name search. All single-column LOWER+LIKE on `name`.
CREATE INDEX IF NOT EXISTS idx_manufacturer_name_trgm
    ON public.manufacturer USING gin (LOWER(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_model_name_trgm
    ON public.model USING gin (LOWER(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_agency_name_trgm
    ON public.agency USING gin (LOWER(name) gin_trgm_ops);

-- F2-040: admin Reservation list searches across reservation_number +
-- reservation_flow.{name, surname, email} + agency.name + the
-- CONCAT(name, ' ', surname) full-name match. Per-column trigram
-- covers individual-field searches; the CONCAT case is handled by
-- including a `name || ' ' || surname` functional index on
-- reservation_flow so "John Smith" matches when typed as one string.
-- agency.name reuses the index from F2-034.
CREATE INDEX IF NOT EXISTS idx_reservation_number_trgm
    ON public.reservation USING gin (LOWER(reservation_number) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_reservation_flow_email_trgm
    ON public.reservation_flow USING gin (LOWER(email) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_reservation_flow_name_trgm
    ON public.reservation_flow USING gin (LOWER(name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_reservation_flow_surname_trgm
    ON public.reservation_flow USING gin (LOWER(surname) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_reservation_flow_fullname_trgm
    ON public.reservation_flow USING gin (LOWER(name || ' ' || surname) gin_trgm_ops);
