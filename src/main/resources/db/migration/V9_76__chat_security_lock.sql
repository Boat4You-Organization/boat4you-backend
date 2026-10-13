-- AI chat (IRIS) security guard (11.10.2026, infra/docs/ai-chat-security-review-2026-10-11.md B1/B9): a session
-- whose visitor message, name or model reply hits the guard is flagged and LOCKED (status, fits VARCHAR(20)) - the
-- AI never answers it again and a live agent takes over. Flagged sessions are evidence and cannot be deleted;
-- broker delete becomes a soft delete (deleted_at).
ALTER TABLE ai_chat_session
    ADD COLUMN flagged            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN lock_reason        VARCHAR(120),
    ADD COLUMN locked_at          TIMESTAMP,
    ADD COLUMN security_soft_hits INT     NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at         TIMESTAMP;

-- Admin "suspicious only" filter + the per-IP repeat check at session creation.
CREATE INDEX IF NOT EXISTS idx_ai_chat_session_flagged
    ON ai_chat_session (last_activity_at DESC) WHERE flagged;
CREATE INDEX IF NOT EXISTS idx_ai_chat_session_ip_locked
    ON ai_chat_session (ip, locked_at DESC) WHERE flagged;

-- The 4.10.2026 prompt-injection / XSS probes (sessions 48, 50-53, all from this IP): flag + lock them so the
-- transcripts can no longer be deleted and a stored token gets no AI answer. No-op on databases without them.
UPDATE ai_chat_session
SET flagged     = TRUE,
    status      = 'LOCKED',
    lock_reason = 'BACKFILL:probes 4.10.2026',
    locked_at   = last_activity_at
WHERE ip = '31.22.91.227';
