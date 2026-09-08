-- Email marketing plumbing (Mario, 8.9.2026 — Early Booking 2027 campaign).
--
-- `email_suppression` is the permanent do-not-mail list shared by every
-- future campaign: one row per address, fed by the public unsubscribe
-- endpoint, GDPR erasures and manual imports. Checked at send time, so a
-- late unsubscribe still stops the remaining batches.
--
-- `campaign_recipient` is one row per (campaign, address): the send queue
-- with a per-recipient unsubscribe token and the send outcome. The mail job
-- drains PENDING rows in small hourly batches (deliverability — never blast
-- the whole list at once).
CREATE SEQUENCE email_suppression_id_seq;

CREATE TABLE email_suppression (
    id          BIGINT       PRIMARY KEY DEFAULT nextval('email_suppression_id_seq'),
    email       VARCHAR(320) NOT NULL UNIQUE,
    reason      VARCHAR(63)  NOT NULL,               -- UNSUBSCRIBE_LINK / GDPR_ERASURE / MANUAL / BOUNCE
    created     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE SEQUENCE campaign_recipient_id_seq;

CREATE TABLE campaign_recipient (
    id              BIGINT       PRIMARY KEY DEFAULT nextval('campaign_recipient_id_seq'),
    campaign        VARCHAR(63)  NOT NULL,
    email           VARCHAR(320) NOT NULL,
    recipient_name  VARCHAR(255),
    token           VARCHAR(63)  NOT NULL UNIQUE,    -- per-recipient unsubscribe token
    status          VARCHAR(31)  NOT NULL DEFAULT 'PENDING',  -- PENDING / SENT / SUPPRESSED / FAILED
    error           TEXT,
    sent_at         TIMESTAMP,
    created         TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT campaign_recipient_campaign_email_uq UNIQUE (campaign, email)
);

CREATE INDEX campaign_recipient_campaign_status_idx ON campaign_recipient (campaign, status, id);
CREATE INDEX campaign_recipient_email_idx ON campaign_recipient (email);
