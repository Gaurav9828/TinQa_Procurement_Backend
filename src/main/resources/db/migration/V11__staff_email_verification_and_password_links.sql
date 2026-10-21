-- ==========================================
-- V11: verified employee emails and emailed password links
-- ==========================================
-- A new employee's personal email is proved with a 5-digit code before the account is created, and the account gets
-- no shared default password: the employee chooses one through a single-use link emailed when the account is
-- enabled. Anyone who can sign in may ask for a reset link ("Forgot password?"). Links expire after 15 minutes.

-- One row per code sent: the code is kept only as a hash; a correct code returns a token (kept as its SHA-256) that
-- one employee creation consumes.
CREATE TABLE email_verification
(
    id               BIGSERIAL PRIMARY KEY,
    email            VARCHAR(150) NOT NULL,
    code_hash        VARCHAR(255) NOT NULL,
    code_expires_at  TIMESTAMP    NOT NULL,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    verified_at      TIMESTAMP,
    token_hash       VARCHAR(64) UNIQUE,
    token_expires_at TIMESTAMP,
    consumed_at      TIMESTAMP,
    requested_by     BIGINT       NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_email_verification_email ON email_verification (email, created_at DESC);

-- SETUP (new account) or RESET (forgot password); the token is kept only as its SHA-256
CREATE TABLE password_link
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    purpose    VARCHAR(10) NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    used_at    TIMESTAMP,
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT chk_password_link_purpose CHECK (purpose IN ('SETUP', 'RESET'))
);

CREATE INDEX idx_password_link_user ON password_link (user_id, created_at DESC);
