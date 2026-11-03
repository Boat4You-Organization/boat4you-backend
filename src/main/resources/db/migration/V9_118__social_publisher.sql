-- V9_118 Objave (social publisher S1, Mario 3.11.2026): one post published to many of our social channels, now or on a
-- schedule, through each network's official API with our own developer apps. Design and API contract:
-- boat4you-admin docs/superpowers/specs/2026-11-03-social-publisher-design.md.
-- Every site has its own Facebook page, Instagram and TikTok (social_account.site = cc site id, e.g. 'catamaran-croatia');
-- one YouTube channel serves all sites (site NULL). The AI writes one post a day per site (social_ai_setting) from our own
-- boat photos, optionally with a short video made by Higgsfield (social_generation).
-- New tables only; the previous jar never touches them. Network tokens and app passwords are AES-GCM encrypted by the
-- app (SOCIAL_TOKEN_KEY) and never leave the backend; automation keys are stored as SHA-256 only.

-- One place we can publish to: a Facebook page, an Instagram business account, a LinkedIn page, a Bluesky handle…
CREATE TABLE social_account (
    id                 BIGSERIAL PRIMARY KEY,
    platform           VARCHAR(20)  NOT NULL,
    site               VARCHAR(40),
    external_id        VARCHAR(200) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    handle             VARCHAR(300),
    avatar_url         VARCHAR(1000),
    status             VARCHAR(20)  NOT NULL,
    access_token_enc   TEXT,
    refresh_token_enc  TEXT,
    token_expires_at   TIMESTAMPTZ,
    meta               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    private_only       BOOLEAN      NOT NULL DEFAULT FALSE,
    last_error         VARCHAR(500),
    connected_by       BIGINT,
    connected_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Disconnected channels keep their row (their past posts point at it) with the tokens wiped.
    disconnected_at    TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_social_account UNIQUE (platform, external_id)
);

CREATE INDEX idx_social_account_site ON social_account (site);

-- OAuth round trip: single use, 10 minutes, bound to the admin who started it.
CREATE TABLE social_oauth_state (
    state          VARCHAR(64)  PRIMARY KEY,
    platform       VARCHAR(20)  NOT NULL,
    site           VARCHAR(40),
    admin_user_id  BIGINT       NOT NULL,
    code_verifier  VARCHAR(128),
    return_url     VARCHAR(500) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A file the admin, the automation or the AI added; served at /public/social/media/{token}.{jpg|mp4|mov} so the networks
-- that fetch by URL (Instagram, Threads) can read it. The token is random; the URL is unguessable, not secret. Photos are
-- stored as JPEG (Instagram takes JPEG only), longest side at most 2160 px.
CREATE TABLE social_media (
    id             BIGSERIAL PRIMARY KEY,
    token          VARCHAR(64)  NOT NULL UNIQUE,
    kind           VARCHAR(10)  NOT NULL,
    origin         VARCHAR(20)  NOT NULL DEFAULT 'UPLOAD',
    origin_ref     VARCHAR(200),
    storage_path   VARCHAR(500) NOT NULL,
    thumbnail_path VARCHAR(500),
    file_name      VARCHAR(200) NOT NULL,
    mime_type      VARCHAR(50)  NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    width          INTEGER,
    height         INTEGER,
    duration_sec   INTEGER,
    sha256         VARCHAR(64)  NOT NULL,
    uploaded_by    BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Automation key for /api/social/v1 (header X-Api-Key): only the hash and a short prefix are stored.
CREATE TABLE social_api_key (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(80)  NOT NULL,
    prefix         VARCHAR(16)  NOT NULL,
    secret_sha256  VARCHAR(64)  NOT NULL UNIQUE,
    drafts_only    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by     BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ
);

CREATE TABLE social_post (
    id             BIGSERIAL PRIMARY KEY,
    text           TEXT         NOT NULL DEFAULT '',
    link           VARCHAR(1000),
    status         VARCHAR(20)  NOT NULL,
    site           VARCHAR(40),
    scheduled_at   TIMESTAMPTZ,
    published_at   TIMESTAMPTZ,
    source         VARCHAR(10)  NOT NULL,
    note           VARCHAR(500),
    created_by     BIGINT,
    created_by_name VARCHAR(200),
    api_key_id     BIGINT REFERENCES social_api_key (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_social_post_when ON social_post (COALESCE(scheduled_at, published_at, created_at));

CREATE TABLE social_post_media (
    post_id   BIGINT  NOT NULL REFERENCES social_post (id) ON DELETE CASCADE,
    media_id  BIGINT  NOT NULL REFERENCES social_media (id),
    position  INTEGER NOT NULL,
    PRIMARY KEY (post_id, position)
);

-- One channel of a post. due_at = when the publisher may take it (the schedule, or the next retry).
CREATE TABLE social_post_target (
    id            BIGSERIAL PRIMARY KEY,
    post_id       BIGINT       NOT NULL REFERENCES social_post (id) ON DELETE CASCADE,
    account_id    BIGINT       NOT NULL REFERENCES social_account (id),
    text          TEXT,
    title         VARCHAR(200),
    status        VARCHAR(20)  NOT NULL,
    due_at        TIMESTAMPTZ,
    claimed_at    TIMESTAMPTZ,
    external_id   VARCHAR(200),
    external_url  VARCHAR(1000),
    error         VARCHAR(1000),
    attempts      INTEGER      NOT NULL DEFAULT 0,
    published_at  TIMESTAMPTZ,
    CONSTRAINT uq_social_post_target UNIQUE (post_id, account_id)
);

CREATE INDEX idx_social_post_target_due ON social_post_target (due_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_social_post_target_post ON social_post_target (post_id);
CREATE INDEX idx_social_post_target_account ON social_post_target (account_id);

-- The AI's daily post, per site: when, on which of the site's channels, in which language, approved by a person or not.
CREATE TABLE social_ai_setting (
    site              VARCHAR(40)  PRIMARY KEY,
    enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    publish_time      VARCHAR(5)   NOT NULL DEFAULT '18:00',
    language          VARCHAR(5)   NOT NULL DEFAULT 'en',
    account_ids       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    require_approval  BOOLEAN      NOT NULL DEFAULT TRUE,
    use_video         BOOLEAN      NOT NULL DEFAULT FALSE,
    guidance          TEXT,
    updated_by        BIGINT,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One AI run per site and day (unique), so two nodes or a restart never make a second post.
CREATE TABLE social_ai_run (
    id          BIGSERIAL PRIMARY KEY,
    site        VARCHAR(40)  NOT NULL,
    run_date    DATE         NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    topic       VARCHAR(300),
    post_id     BIGINT REFERENCES social_post (id) ON DELETE SET NULL,
    error       VARCHAR(1000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_social_ai_run UNIQUE (site, run_date)
);

-- A video (or image) made by an outside generator (Higgsfield) from one of our photos; the post waits for it.
CREATE TABLE social_generation (
    id                   BIGSERIAL PRIMARY KEY,
    post_id              BIGINT REFERENCES social_post (id) ON DELETE CASCADE,
    provider             VARCHAR(20)  NOT NULL,
    kind                 VARCHAR(10)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    prompt               TEXT,
    -- What to post once the video is there: channels and per-network texts (AI run).
    payload              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    source_media_id      BIGINT REFERENCES social_media (id),
    result_media_id      BIGINT REFERENCES social_media (id),
    provider_request_id  VARCHAR(200),
    error                VARCHAR(1000),
    attempts             INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_social_generation_open ON social_generation (status) WHERE status IN ('QUEUED', 'RUNNING');

GRANT SELECT, INSERT, UPDATE, DELETE
    ON social_account, social_oauth_state, social_media, social_api_key, social_post, social_post_media, social_post_target,
       social_ai_setting, social_ai_run, social_generation
    TO boat4you_app;
GRANT USAGE, SELECT
    ON SEQUENCE social_account_id_seq, social_media_id_seq, social_api_key_id_seq, social_post_id_seq, social_post_target_id_seq,
       social_ai_run_id_seq, social_generation_id_seq
    TO boat4you_app;
