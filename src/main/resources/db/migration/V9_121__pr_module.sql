-- V9_121 PR module (digital-PR outreach, Mario 4.11.2026): media outlets, journalist contacts, our own-data studies, 1:1
-- plain-text pitch emails, placements with link checks, RSS mentions (Google Alerts). Spec: DIGITAL-PR-PROGRAM-2026-11.md,
-- plan: infra docs/superpowers/plans/2026-11-04-pr-module.md.
-- New tables only; the previous jar never touches them. Enum columns are VARCHAR (the app's @Enumerated STRING values).
-- Markets are ISO-3166 alpha-2 (GB, not UK). Contact emails are stored normalised (trim + lowercase) by the app; the
-- indexes compare lower(email) as well.

CREATE TABLE pr_outlet (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    market        CHAR(2)      NOT NULL,
    tier          SMALLINT     NOT NULL CHECK (tier BETWEEN 1 AND 3),
    section_url   VARCHAR(500),
    desk_contact  VARCHAR(320),
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_pr_outlet_name_market ON pr_outlet (lower(name), market);

-- The same journalist may write for two outlets (two rows, one address); an opt-out or bounce blocks every row with it.
CREATE TABLE pr_contact (
    id                  BIGSERIAL PRIMARY KEY,
    outlet_id           BIGINT REFERENCES pr_outlet (id) ON DELETE SET NULL,
    name                VARCHAR(200),
    role                VARCHAR(200),
    email               VARCHAR(320) NOT NULL,
    market              CHAR(2),
    -- Where the address was published and when we found it; without both the contact cannot be mailed.
    email_source_url    VARCHAR(1000),
    email_found_on      DATE,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    recent_article_url  VARCHAR(1000),
    notes               TEXT,
    last_contacted_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_pr_contact_email_outlet ON pr_contact (lower(email), coalesce(outlet_id, 0));
CREATE INDEX idx_pr_contact_email ON pr_contact (lower(email));

CREATE TABLE pr_study (
    id            BIGSERIAL PRIMARY KEY,
    number        INT           NOT NULL UNIQUE,
    site_id       VARCHAR(40)   NOT NULL,
    title         VARCHAR(300)  NOT NULL,
    page_url      VARCHAR(1000),
    status        VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    published_on  DATE,
    notes         TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- One email to one contact. DRAFT -> SENDING is a single UPDATE ... WHERE status = 'DRAFT', so a double click sends once.
-- A follow-up points at its main pitch; deleting a contact (24-month retention) deletes its pitches.
CREATE TABLE pr_pitch (
    id               BIGSERIAL PRIMARY KEY,
    study_id         BIGINT REFERENCES pr_study (id) ON DELETE SET NULL,
    contact_id       BIGINT       NOT NULL REFERENCES pr_contact (id) ON DELETE CASCADE,
    parent_pitch_id  BIGINT REFERENCES pr_pitch (id),
    template         VARCHAR(20)  NOT NULL,
    subject          VARCHAR(300) NOT NULL,
    body             TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    message_id       VARCHAR(300),
    sent_at          TIMESTAMPTZ,
    error            TEXT,
    reply_note       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most one main pitch per contact and study, at most one follow-up per pitch.
CREATE UNIQUE INDEX uq_pr_pitch_main ON pr_pitch (study_id, contact_id) WHERE parent_pitch_id IS NULL;
CREATE UNIQUE INDEX uq_pr_pitch_follow_up ON pr_pitch (parent_pitch_id) WHERE parent_pitch_id IS NOT NULL;

-- An article that used a study; link_state is the result of the last link check (BLOCKED_UNKNOWN = the site refused the bot).
CREATE TABLE pr_placement (
    id                BIGSERIAL PRIMARY KEY,
    study_id          BIGINT REFERENCES pr_study (id) ON DELETE SET NULL,
    outlet_id         BIGINT REFERENCES pr_outlet (id) ON DELETE SET NULL,
    pitch_id          BIGINT REFERENCES pr_pitch (id) ON DELETE SET NULL,
    article_url       VARCHAR(1000) NOT NULL UNIQUE,
    published_on      DATE,
    target_url        VARCHAR(1000),
    anchor_text       VARCHAR(500),
    link_state        VARCHAR(20)   NOT NULL DEFAULT 'NOT_CHECKED',
    -- Last OK_FOLLOW / OK_NOFOLLOW seen (or the loss acknowledged by clear-alert); alerts compare against it, so a
    -- BLOCKED_UNKNOWN check in between cannot hide a drop. NULL until the first good check.
    last_good_state   VARCHAR(20),
    last_http_status  INT,
    last_checked_at   TIMESTAMPTZ,
    alert             BOOLEAN       NOT NULL DEFAULT false,
    alert_reason      VARCHAR(200),
    domain_rank       NUMERIC(5,2),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- RSS feeds we read for mentions (Google Alerts and others); created before pr_mention, which points at it.
CREATE TABLE pr_alert_feed (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200)  NOT NULL,
    url              VARCHAR(1000) NOT NULL UNIQUE,
    enabled          BOOLEAN       NOT NULL DEFAULT true,
    last_fetched_at  TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- A page that names us; url is stored normalised by the app (redirect unwrapped, utm_ dropped), so one article is one row.
CREATE TABLE pr_mention (
    id           BIGSERIAL PRIMARY KEY,
    url          VARCHAR(1000) NOT NULL UNIQUE,
    title        VARCHAR(500),
    source       VARCHAR(20)   NOT NULL,
    feed_id      BIGINT REFERENCES pr_alert_feed (id) ON DELETE SET NULL,
    links_to_us  BOOLEAN,
    status       VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    found_at     TIMESTAMPTZ   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON pr_outlet, pr_contact, pr_study, pr_pitch, pr_placement, pr_alert_feed, pr_mention
    TO boat4you_app;
GRANT USAGE, SELECT
    ON SEQUENCE pr_outlet_id_seq, pr_contact_id_seq, pr_study_id_seq, pr_pitch_id_seq, pr_placement_id_seq,
       pr_alert_feed_id_seq, pr_mention_id_seq
    TO boat4you_app;
