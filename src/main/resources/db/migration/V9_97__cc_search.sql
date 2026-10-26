CREATE TABLE cc_search (
    id BIGSERIAL PRIMARY KEY, source VARCHAR(20) NOT NULL, site_id VARCHAR(40) NOT NULL,
    chat_session_id BIGINT REFERENCES ai_chat_session (id) ON DELETE SET NULL,
    visit_id BIGINT REFERENCES cc_visit (id) ON DELETE SET NULL,
    country_code CHAR(2), location VARCHAR(80), did BIGINT, start_date DATE, end_date DATE,
    min_persons INT, min_cabins INT, min_price_eur INT, max_price_eur INT, vessel_type VARCHAR(30), equipment TEXT,
    result_count INT, total_available INT, searched_area VARCHAR(120),
    flagged BOOLEAN NOT NULL DEFAULT FALSE, estimated BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT now());
CREATE INDEX idx_cc_search_site ON cc_search (site_id, created_at);
CREATE INDEX idx_cc_search_session ON cc_search (chat_session_id) WHERE chat_session_id IS NOT NULL;
CREATE INDEX idx_cc_search_visit ON cc_search (visit_id) WHERE visit_id IS NOT NULL;
CREATE INDEX idx_cc_search_created ON cc_search (created_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON cc_search TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE cc_search_id_seq TO boat4you_app;
