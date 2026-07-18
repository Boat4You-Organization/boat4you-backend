-- AI chat assistant on www.boat4you.com (Mario 19.7.2026): visitor talks to an
-- AI concierge that searches the live fleet and guides them to a booking; when
-- the visitor insists on a human the session is flagged and brokers take over
-- from the admin panel. PG is the source of truth for the transcript; the AI
-- call happens synchronously on the API node.

CREATE TABLE IF NOT EXISTS ai_chat_session (
    id               BIGSERIAL PRIMARY KEY,
    -- Anonymous session credential handed to the browser (localStorage).
    token            VARCHAR(64)  NOT NULL,
    locale           VARCHAR(8)   NOT NULL DEFAULT 'en',
    -- AI (assistant handles it) / HUMAN_REQUESTED (visitor asked for a person,
    -- waiting for a broker) / HUMAN (broker replied, AI stays out) / CLOSED.
    status           VARCHAR(20)  NOT NULL DEFAULT 'AI',
    visitor_name     VARCHAR(120),
    visitor_email    VARCHAR(255),
    -- Set on every visitor message while a human owns the session; cleared
    -- when a broker replies. Drives the admin inbox badge.
    admin_unread     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_chat_session_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_session_status
    ON ai_chat_session (status, last_activity_at DESC);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES ai_chat_session (id) ON DELETE CASCADE,
    -- USER / ASSISTANT / ADMIN / SYSTEM
    role       VARCHAR(20) NOT NULL,
    content    TEXT        NOT NULL,
    -- Structured extras the web widget renders (e.g. yacht result cards),
    -- serialized JSON. Text stays the canonical transcript.
    payload    TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session
    ON ai_chat_message (session_id, id);
