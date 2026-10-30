-- V9_113 AI call center: one row per call attempt (inquiry_call) and the raw ElevenLabs webhook events (call_webhook_event).
-- New tables only; the previous jar never touches them. inquiry_call rows die with their inquiry (24-month inquiry retention,
-- FK ON DELETE CASCADE). call_webhook_event is unique per (conversation_id, type) so a redelivered webhook is a no-op; kept
-- 90 days (CallRetentionJob). No FK to or from cc_* (Contact Center stays separate).
CREATE TABLE inquiry_call (
    id                          BIGSERIAL PRIMARY KEY,
    inquiry_id                  BIGINT       NOT NULL REFERENCES inquiry (id) ON DELETE CASCADE,
    kind                        VARCHAR(20)  NOT NULL,
    attempt                     INTEGER      NOT NULL DEFAULT 1,
    status                      VARCHAR(20)  NOT NULL,
    skip_reason                 VARCHAR(30),
    scheduled_at                TIMESTAMPTZ  NOT NULL,
    started_at                  TIMESTAMPTZ,
    ended_at                    TIMESTAMPTZ,
    duration_secs               INTEGER,
    language                    VARCHAR(5),
    to_number                   VARCHAR(20)  NOT NULL,
    from_number                 VARCHAR(20),
    elevenlabs_conversation_id  VARCHAR(100) UNIQUE,
    twilio_call_sid             VARCHAR(64),
    outcome                     VARCHAR(30),
    summary                     TEXT,
    data                        JSONB,
    transcript                  JSONB,
    quality                     JSONB,
    audio_path                  VARCHAR(255),
    cost_credits                INTEGER,
    termination_reason          VARCHAR(200),
    error                       VARCHAR(500),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_inquiry_call_due ON inquiry_call (status, scheduled_at);
CREATE INDEX idx_inquiry_call_inquiry ON inquiry_call (inquiry_id);
CREATE INDEX idx_inquiry_call_number ON inquiry_call (to_number, kind, status);

CREATE TABLE call_webhook_event (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  VARCHAR(100) NOT NULL,
    type             VARCHAR(40)  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    payload          JSONB        NOT NULL,
    processed_at     TIMESTAMPTZ,
    error            VARCHAR(500),
    CONSTRAINT uq_call_webhook_event UNIQUE (conversation_id, type)
);

CREATE INDEX idx_call_webhook_event_unprocessed ON call_webhook_event (received_at) WHERE processed_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON inquiry_call, call_webhook_event TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE inquiry_call_id_seq, call_webhook_event_id_seq TO boat4you_app;
