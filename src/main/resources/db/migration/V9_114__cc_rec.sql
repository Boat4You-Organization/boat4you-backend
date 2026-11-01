-- Contact Center P6 R1 (1.11.2026): session recordings. The raw payloads live as files on cusma3 /mnt/data/rec (60 days);
-- these tables hold the switches, one row per recorded page view and the facts that stay for the whole test (heatmaps).
-- INTEGER, never SMALLINT (V9_94: Hibernate validate), and no JPA entity reads these tables (plain JDBC).

CREATE TABLE rec_setting (
    site_id VARCHAR(40) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sample_pct INTEGER NOT NULL DEFAULT 100 CHECK (sample_pct BETWEEN 0 AND 100),
    mobile_pct INTEGER NOT NULL DEFAULT 100 CHECK (mobile_pct BETWEEN 0 AND 100),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by BIGINT
);

-- One clarity page view (projectId, userId, sessionId, pageNum of the envelope). user_id is clarity's own id (b4yr_u), not ours.
CREATE TABLE rec_page_view (
    id BIGSERIAL PRIMARY KEY,
    site_id VARCHAR(40) NOT NULL,
    project_id VARCHAR(60) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    session_id VARCHAR(40) NOT NULL,
    page_num INTEGER NOT NULL,
    visit_id BIGINT REFERENCES cc_visit (id) ON DELETE SET NULL,
    url VARCHAR(1000),
    page_type VARCHAR(20),
    started_at TIMESTAMP NOT NULL,
    last_payload_at TIMESTAMP NOT NULL,
    duration_ms INTEGER,
    viewport_w INTEGER,
    viewport_h INTEGER,
    doc_w INTEGER,
    doc_h INTEGER,
    payloads INTEGER NOT NULL DEFAULT 0,
    bytes BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    file_path VARCHAR(300),
    file_purged BOOLEAN NOT NULL DEFAULT FALSE,
    metrics JSONB,
    CONSTRAINT uq_rec_page_view UNIQUE (project_id, user_id, session_id, page_num)
);
CREATE INDEX idx_rec_page_view_site ON rec_page_view (site_id, started_at DESC);
CREATE INDEX idx_rec_page_view_open ON rec_page_view (last_payload_at) WHERE status = 'OPEN';
CREATE INDEX idx_rec_page_view_visit ON rec_page_view (visit_id) WHERE visit_id IS NOT NULL;

-- A payload sequence already stored (a retried upload is stored once).
CREATE TABLE rec_payload (
    page_view_id BIGINT NOT NULL REFERENCES rec_page_view (id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (page_view_id, sequence)
);

-- Facts (clarity-decode 0.8.73 field names): kept after the raw file is purged, for heatmaps and insights.
CREATE TABLE rec_click (
    id BIGSERIAL PRIMARY KEY,
    page_view_id BIGINT NOT NULL REFERENCES rec_page_view (id) ON DELETE CASCADE,
    t_ms INTEGER NOT NULL,
    x INTEGER,
    y INTEGER,
    ex INTEGER,
    ey INTEGER,
    target BIGINT,
    hash VARCHAR(40),
    hash_beta VARCHAR(40),
    button INTEGER,
    reaction INTEGER,
    context INTEGER,
    trust INTEGER,
    w INTEGER,
    h INTEGER,
    tag VARCHAR(40),
    text VARCHAR(200),
    link VARCHAR(500)
);
CREATE INDEX idx_rec_click_page_view ON rec_click (page_view_id);
CREATE INDEX idx_rec_click_hash ON rec_click (hash);

CREATE TABLE rec_scroll (
    id BIGSERIAL PRIMARY KEY,
    page_view_id BIGINT NOT NULL REFERENCES rec_page_view (id) ON DELETE CASCADE,
    t_ms INTEGER NOT NULL,
    target BIGINT,
    x INTEGER,
    y INTEGER,
    top_el VARCHAR(80),
    bottom_el VARCHAR(80),
    trust INTEGER
);
CREATE INDEX idx_rec_scroll_page_view ON rec_scroll (page_view_id);

CREATE TABLE rec_error (
    id BIGSERIAL PRIMARY KEY,
    page_view_id BIGINT NOT NULL REFERENCES rec_page_view (id) ON DELETE CASCADE,
    t_ms INTEGER NOT NULL,
    message VARCHAR(500),
    line INTEGER,
    col INTEGER,
    source VARCHAR(500)
);
CREATE INDEX idx_rec_error_page_view ON rec_error (page_view_id);

CREATE TABLE rec_perf (
    page_view_id BIGINT PRIMARY KEY REFERENCES rec_page_view (id) ON DELETE CASCADE,
    nav JSONB NOT NULL
);

-- Who watched which recording (R2): every view of a raw recording is logged.
CREATE TABLE rec_view_audit (
    id BIGSERIAL PRIMARY KEY,
    page_view_id BIGINT NOT NULL REFERENCES rec_page_view (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    viewed_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_rec_view_audit_page_view ON rec_view_audit (page_view_id);

-- CSS and fonts of the sites, stored once by content (R1.5), so a recording replays as it looked on its day.
CREATE TABLE rec_asset (
    id BIGSERIAL PRIMARY KEY,
    host VARCHAR(100) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    path VARCHAR(300) NOT NULL,
    content_type VARCHAR(100),
    bytes INTEGER NOT NULL,
    fetched_at TIMESTAMP NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_rec_asset UNIQUE (url, sha256)
);
