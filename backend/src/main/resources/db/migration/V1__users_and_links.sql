CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    google_sub    VARCHAR(64)  NOT NULL UNIQUE,   -- Google's stable user id (OIDC "sub")
    email         VARCHAR(320) NOT NULL,
    name          VARCHAR(255),
    avatar_url    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

-- Ids come from this sequence so generated codes are a pure function of the id (no collisions, no retry loop).
CREATE SEQUENCE links_id_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE links (
    id          BIGINT       PRIMARY KEY DEFAULT nextval('links_id_seq'),
    code        VARCHAR(32)  NOT NULL,
    long_url    VARCHAR(2048) NOT NULL,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    custom      BOOLEAN      NOT NULL DEFAULT FALSE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT links_code_key UNIQUE (code)       -- also serves as the redirect lookup index
);

CREATE INDEX idx_links_user_created ON links (user_id, created_at DESC);
