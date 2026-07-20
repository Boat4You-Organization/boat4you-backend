-- Chat visitor presence (Mario 20.7.2026, JivoChat parity): the widget heartbeats the
-- visitor's current page while a session exists, so the broker inbox can show who is
-- live on the site right now, what they are looking at and where they came from.
ALTER TABLE ai_chat_session ADD COLUMN last_seen_at TIMESTAMP;
ALTER TABLE ai_chat_session ADD COLUMN current_page VARCHAR(500);
ALTER TABLE ai_chat_session ADD COLUMN referrer VARCHAR(500);
-- JSON array of the visitor's last pages (capped in code), newest last.
ALTER TABLE ai_chat_session ADD COLUMN page_trail TEXT;
