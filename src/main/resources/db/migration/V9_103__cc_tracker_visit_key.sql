-- Contact Center P1b (tracker ingest, 26.10.2026). visit_key = the UUID v4 the widget gets as "visitId"; the BIGSERIAL id
-- never leaves the server (contact-center-sacuvati BACKEND-29).
ALTER TABLE cc_visit ADD COLUMN IF NOT EXISTS visit_key VARCHAR(36);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cc_visit_visit_key ON cc_visit (visit_key);
-- Resume (same visitor + site, seen within 30 min) and the end-of-visit sweep read only open visits.
CREATE INDEX IF NOT EXISTS idx_cc_visit_open_visitor_site ON cc_visit (visitor_id, site_id) WHERE ended_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_cc_visit_open_last_seen ON cc_visit (last_seen_at) WHERE ended_at IS NULL;
-- Proactive delivery (Task 11) looks up the visitor's chat by visitor_id: P1a's idx_ai_chat_session_visitor (V9_102) covers it, nothing to add.
