-- "AI nikad ne šuti" (contact-center-dizajn 2.2, plan P1c): when the holding message went out after N minutes of
-- HUMAN_REQUESTED, and when the AI took a HUMAN conversation back after M minutes. NULL = never happened.
ALTER TABLE ai_chat_session
    ADD COLUMN IF NOT EXISTS handoff_holding_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS ai_returned_at     TIMESTAMP;

-- CcAiWatchdog: recent conversations by status; flagged and deleted ones are never touched.
CREATE INDEX IF NOT EXISTS idx_ai_chat_session_watchdog
    ON ai_chat_session (status, last_activity_at DESC) WHERE NOT flagged AND deleted_at IS NULL;
