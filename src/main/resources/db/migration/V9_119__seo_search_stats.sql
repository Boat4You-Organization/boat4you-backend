-- Contact Center statistics, search engines (F1, Mario 3.11.2026): what Google Search Console and Bing Webmaster Tools
-- report for the seven sites, imported every hour on the scheduler node (SearchImportJob). Aggregates only: neither engine
-- gives anything per visitor. Each table is replaced per (engine, site_id, day) on import, so no unique key on the text.
--   seo_site_day  the site's totals (Google by property, per country and device; Bing per day, no country or device);
--   seo_page_day  per landing page, `path` without host and query;
--   seo_query_day per search query: path '' = the whole site (Google by property, as its Queries report; Bing only so),
--                 and Google also per query and landing page.
-- engine is GOOGLE or BING; country is ISO alpha-2 and device DESKTOP / MOBILE / TABLET, '' when the engine gives none.
-- position_sum = average position × impressions, so a range averages by impression: sum(position_sum) / sum(impressions).
-- Bing dates its query and page rows by week. INTEGER, never SMALLINT (V9_94); no JPA entity reads these tables.

CREATE TABLE seo_site_day (
    engine VARCHAR(10) NOT NULL,
    site_id VARCHAR(40) NOT NULL,
    day DATE NOT NULL,
    country VARCHAR(2) NOT NULL DEFAULT '',
    device VARCHAR(10) NOT NULL DEFAULT '',
    clicks INTEGER NOT NULL,
    impressions INTEGER NOT NULL,
    position_sum REAL NOT NULL
);
CREATE INDEX idx_seo_site_day ON seo_site_day (day, site_id);

CREATE TABLE seo_page_day (
    engine VARCHAR(10) NOT NULL,
    site_id VARCHAR(40) NOT NULL,
    day DATE NOT NULL,
    path VARCHAR(1000) NOT NULL,
    country VARCHAR(2) NOT NULL DEFAULT '',
    device VARCHAR(10) NOT NULL DEFAULT '',
    clicks INTEGER NOT NULL,
    impressions INTEGER NOT NULL,
    position_sum REAL NOT NULL
);
CREATE INDEX idx_seo_page_day ON seo_page_day (day, site_id);

CREATE TABLE seo_query_day (
    engine VARCHAR(10) NOT NULL,
    site_id VARCHAR(40) NOT NULL,
    day DATE NOT NULL,
    query VARCHAR(500) NOT NULL,
    path VARCHAR(1000) NOT NULL DEFAULT '',
    country VARCHAR(2) NOT NULL DEFAULT '',
    device VARCHAR(10) NOT NULL DEFAULT '',
    clicks INTEGER NOT NULL,
    impressions INTEGER NOT NULL,
    position_sum REAL NOT NULL
);
CREATE INDEX idx_seo_query_day ON seo_query_day (day, site_id);

-- A day counts as done once it was imported after the engine's data settled (Google: 4 days after the day).
CREATE TABLE seo_import_day (
    engine VARCHAR(10) NOT NULL,
    site_id VARCHAR(40) NOT NULL,
    day DATE NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (engine, site_id, day)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON seo_site_day, seo_page_day, seo_query_day, seo_import_day TO boat4you_app;
