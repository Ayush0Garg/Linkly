-- Accounts can now be created with email + password as well as Google.
ALTER TABLE users ALTER COLUMN google_sub DROP NOT NULL;
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE users ADD CONSTRAINT users_has_credential CHECK (google_sub IS NOT NULL OR password_hash IS NOT NULL);
-- Emails are stored lower-cased by the application, so a plain unique constraint is enough.
ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);

-- Opaque, rotating refresh tokens. Only a SHA-256 hash is stored, never the token itself.
-- All tokens descended from one login share a family_id so reuse of a rotated token can revoke the whole chain.
CREATE TABLE refresh_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id  UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
