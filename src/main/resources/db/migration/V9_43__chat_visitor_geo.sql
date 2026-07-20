-- Pre-chat name + IP geolocation for the broker inbox (Mario 20.7.2026):
-- the widget asks the visitor's name before the conversation starts, and the
-- backend resolves the client IP to a country (DB-IP Country Lite, CC BY 4.0).
ALTER TABLE ai_chat_session ADD COLUMN ip VARCHAR(45);
ALTER TABLE ai_chat_session ADD COLUMN country_code VARCHAR(2);
ALTER TABLE ai_chat_session ADD COLUMN country VARCHAR(80);
