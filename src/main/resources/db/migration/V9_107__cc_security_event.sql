-- Contact Center security log (contact-center-dizajn 2.6 "Dnevnik", plan P1c): every IRIS lock, every AI circuit
-- breaker transition, and (P1b) dropped tracker surges and bot bursts. Evidence: no retention job deletes it.
CREATE TABLE IF NOT EXISTS cc_security_event (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(30) NOT NULL,
    site_id    VARCHAR(40),
    detail     TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_cc_security_event_type CHECK (type IN ('LOCK', 'BREAKER_OPEN', 'BREAKER_CLOSE', 'SURGE_DROPPED', 'BOT_BURST'))
);

CREATE INDEX IF NOT EXISTS idx_cc_security_event_created ON cc_security_event (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cc_security_event_breaker ON cc_security_event (site_id, id DESC) WHERE type IN ('BREAKER_OPEN', 'BREAKER_CLOSE');

GRANT SELECT, INSERT, UPDATE, DELETE ON public.cc_security_event TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE public.cc_security_event_id_seq TO boat4you_app;
