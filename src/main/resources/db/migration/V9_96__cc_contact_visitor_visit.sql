-- Contact center P1a (design doc contact-center-dizajn-2026-10-25 §2.1 / §2.3 / §7): contact, visitor, visit, page
-- view and event tables. Every FK carries an explicit ON DELETE rule because the retention purge is bulk SQL, not JPA
-- cascades (BACKEND-32). No FK points at inquiry. Nothing here touches an existing table, so the previous jar keeps
-- running against the migrated schema.
CREATE TABLE cc_contact (
    id BIGSERIAL PRIMARY KEY, name VARCHAR(120), email VARCHAR(254), phone VARCHAR(40),
    status VARCHAR(20) NOT NULL DEFAULT 'NEW', note TEXT, email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now());
CREATE INDEX idx_cc_contact_email ON cc_contact (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX idx_cc_contact_updated ON cc_contact (updated_at);
CREATE TABLE cc_contact_tag (
    contact_id BIGINT NOT NULL REFERENCES cc_contact (id) ON DELETE CASCADE, tag VARCHAR(40) NOT NULL,
    PRIMARY KEY (contact_id, tag));
CREATE TABLE cc_visitor (
    id BIGSERIAL PRIMARY KEY, visitor_key VARCHAR(64) NOT NULL, first_site_id VARCHAR(40) NOT NULL,
    contact_id BIGINT REFERENCES cc_contact (id) ON DELETE SET NULL, is_staff BOOLEAN NOT NULL DEFAULT FALSE,
    visit_count INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT now(), last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_cc_visitor_key UNIQUE (visitor_key));
CREATE INDEX idx_cc_visitor_contact ON cc_visitor (contact_id) WHERE contact_id IS NOT NULL;
CREATE INDEX idx_cc_visitor_last_seen ON cc_visitor (last_seen_at);
CREATE TABLE cc_visit (
    id BIGSERIAL PRIMARY KEY, visitor_id BIGINT NOT NULL REFERENCES cc_visitor (id) ON DELETE CASCADE,
    site_id VARCHAR(40) NOT NULL, started_at TIMESTAMP NOT NULL DEFAULT now(), last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    ended_at TIMESTAMP, landing_url VARCHAR(1000), referrer VARCHAR(1000),
    utm_source VARCHAR(200), utm_medium VARCHAR(200), utm_campaign VARCHAR(200), utm_term VARCHAR(200), utm_content VARCHAR(200),
    gclid VARCHAR(200), fbclid VARCHAR(200), channel VARCHAR(20) NOT NULL DEFAULT 'OTHER', from_site_id VARCHAR(40),
    ip VARCHAR(45), country_code CHAR(2), locale VARCHAR(10), device_class VARCHAR(10), browser VARCHAR(40), os VARCHAR(40),
    user_agent VARCHAR(400), is_bot BOOLEAN NOT NULL DEFAULT FALSE, bot_reason VARCHAR(40), page_count INT NOT NULL DEFAULT 0);
CREATE INDEX idx_cc_visit_visitor ON cc_visit (visitor_id, started_at DESC);
CREATE INDEX idx_cc_visit_site ON cc_visit (site_id, started_at DESC);
CREATE INDEX idx_cc_visit_last_seen ON cc_visit (last_seen_at);
CREATE INDEX idx_cc_visit_ip_age ON cc_visit (started_at) WHERE ip IS NOT NULL;
CREATE TABLE cc_page_view (
    id BIGSERIAL PRIMARY KEY, visit_id BIGINT NOT NULL REFERENCES cc_visit (id) ON DELETE CASCADE,
    url VARCHAR(1000), path VARCHAR(500) NOT NULL, title VARCHAR(300), page_type VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    boat_slug VARCHAR(200), viewed_at TIMESTAMP NOT NULL DEFAULT now(), duration_ms INT);
CREATE INDEX idx_cc_page_view_visit ON cc_page_view (visit_id, viewed_at);
CREATE INDEX idx_cc_page_view_viewed ON cc_page_view (viewed_at);
CREATE TABLE cc_event (
    id BIGSERIAL PRIMARY KEY, visit_id BIGINT NOT NULL REFERENCES cc_visit (id) ON DELETE CASCADE,
    page_view_id BIGINT REFERENCES cc_page_view (id) ON DELETE SET NULL, type VARCHAR(30) NOT NULL,
    label VARCHAR(200), target VARCHAR(300), payload JSONB, created_at TIMESTAMP NOT NULL DEFAULT now());
CREATE INDEX idx_cc_event_visit ON cc_event (visit_id, created_at);
CREATE INDEX idx_cc_event_page_view ON cc_event (page_view_id) WHERE page_view_id IS NOT NULL;
CREATE INDEX idx_cc_event_created ON cc_event (created_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON cc_contact, cc_contact_tag, cc_visitor, cc_visit, cc_page_view, cc_event TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE cc_contact_id_seq, cc_visitor_id_seq, cc_visit_id_seq, cc_page_view_id_seq, cc_event_id_seq TO boat4you_app;
