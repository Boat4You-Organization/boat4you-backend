-- Contact center P1a: the IRIS chat session joins the contact center (visitor, visit, contact) and carries the response
-- metrics. The constant DEFAULT fills site_id = 'boat4you' on every existing session without a table rewrite (PG 11+),
-- and every IRIS session so far came from boat4you.com. All new columns are nullable or defaulted, and the security columns
-- of V9_76 are not touched, so the previous jar keeps running against this schema. author_user_id has no FK on purpose:
-- an admin user can be deleted without blocking.
ALTER TABLE ai_chat_session
    ADD COLUMN site_id VARCHAR(40) NOT NULL DEFAULT 'boat4you',
    ADD COLUMN visitor_id BIGINT REFERENCES cc_visitor (id) ON DELETE SET NULL,
    ADD COLUMN visit_id BIGINT REFERENCES cc_visit (id) ON DELETE SET NULL,
    ADD COLUMN contact_id BIGINT REFERENCES cc_contact (id) ON DELETE SET NULL,
    ADD COLUMN phone VARCHAR(40), ADD COLUMN first_response_at TIMESTAMP, ADD COLUMN first_response_by VARCHAR(10),
    ADD COLUMN first_human_response_at TIMESTAMP, ADD COLUMN handoff_requested_at TIMESTAMP, ADD COLUMN taken_over_at TIMESTAMP,
    ADD COLUMN ai_return_after_minutes INT, ADD COLUMN missed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_ai_chat_session_site ON ai_chat_session (site_id, last_activity_at DESC);
CREATE INDEX idx_ai_chat_session_visitor ON ai_chat_session (visitor_id) WHERE visitor_id IS NOT NULL;
CREATE INDEX idx_ai_chat_session_visit ON ai_chat_session (visit_id) WHERE visit_id IS NOT NULL;
CREATE INDEX idx_ai_chat_session_contact ON ai_chat_session (contact_id) WHERE contact_id IS NOT NULL;
CREATE INDEX idx_ai_chat_session_handoff_open ON ai_chat_session (handoff_requested_at) WHERE handoff_requested_at IS NOT NULL AND NOT missed;
CREATE INDEX idx_ai_chat_session_activity_unflagged ON ai_chat_session (last_activity_at) WHERE NOT flagged;
CREATE INDEX idx_ai_chat_session_ip_age ON ai_chat_session (created_at) WHERE ip IS NOT NULL AND NOT flagged;
ALTER TABLE ai_chat_message
    ADD COLUMN author_user_id BIGINT, ADD COLUMN client_message_id VARCHAR(64),
    ADD CONSTRAINT uq_ai_chat_message_client_id UNIQUE (session_id, client_message_id);
