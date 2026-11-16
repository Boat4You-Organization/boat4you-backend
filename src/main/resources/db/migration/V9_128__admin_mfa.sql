-- V9_128 Two-step sign-in for the admin (Mario 16.11.2026: Face ID / Touch ID passkeys, Google Authenticator as the
-- fallback, one-time recovery codes, "remember this device" for 30 days). New tables only, so the previous jar keeps
-- working. Rows go with the user (ON DELETE CASCADE) and are also removed when an account is anonymised (GDPR delete).
--   user_passkey        - WebAuthn credentials: id (base64url), the attested credential data (public key), sign counter
--   user_totp           - the authenticator-app secret (base32); pending until the first code confirms it
--   user_recovery_code  - one-time codes, SHA-256 only
--   user_trusted_device - "remember this device" tokens, SHA-256 only, 30 days
SET lock_timeout = '5s';

CREATE TABLE user_passkey (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credential_id            VARCHAR(512) NOT NULL UNIQUE,
    attested_credential_data BYTEA        NOT NULL,
    sign_count               BIGINT       NOT NULL DEFAULT 0,
    label                    VARCHAR(100),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at             TIMESTAMPTZ
);
CREATE INDEX user_passkey_user_idx ON user_passkey (user_id);

CREATE TABLE user_totp (
    user_id        BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret         VARCHAR(64),
    pending_secret VARCHAR(64),
    enabled_at     TIMESTAMPTZ,
    last_step      BIGINT
);

CREATE TABLE user_recovery_code (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at    TIMESTAMPTZ
);
CREATE INDEX user_recovery_code_user_idx ON user_recovery_code (user_id);

CREATE TABLE user_trusted_device (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX user_trusted_device_user_idx ON user_trusted_device (user_id);
