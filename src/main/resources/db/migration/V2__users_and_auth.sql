-- Sign-in accounts, their audit trail, email codes and set-password links.
-- Tables: users, auth_audit_events, email_verification, password_link.

-- users ---------------------------------------------------------------------------------------------------------------

CREATE TABLE users (
    id bigserial NOT NULL,
    username character varying(100) NOT NULL,
    email character varying(150) NOT NULL,
    password character varying(255) NOT NULL,
    role character varying(20) NOT NULL,
    auth_client character varying(20) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    account_non_locked boolean DEFAULT true NOT NULL,
    is_first_login boolean DEFAULT true NOT NULL,
    password_changed_at timestamp with time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_username_key UNIQUE (username)
);

CREATE INDEX idx_users_role ON users USING btree (role);
CREATE UNIQUE INDEX uq_users_email_lower ON users USING btree (lower((email)::text));
CREATE UNIQUE INDEX uq_users_username_lower ON users USING btree (lower((username)::text));

-- auth_audit_events ---------------------------------------------------------------------------------------------------

CREATE TABLE auth_audit_events (
    id bigserial NOT NULL,
    user_id bigint,
    username character varying(100) NOT NULL,
    event_type character varying(50) NOT NULL,
    ip_address character varying(64),
    user_agent character varying(512),
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT auth_audit_events_pkey PRIMARY KEY (id),
    CONSTRAINT auth_audit_events_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_auth_audit_events_occurred_at ON auth_audit_events USING btree (occurred_at);
CREATE INDEX idx_auth_audit_events_user_id ON auth_audit_events USING btree (user_id);

-- email_verification --------------------------------------------------------------------------------------------------

CREATE TABLE email_verification (
    id bigserial NOT NULL,
    email character varying(150) NOT NULL,
    code_hash character varying(255) NOT NULL,
    code_expires_at timestamp without time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    verified_at timestamp without time zone,
    token_hash character varying(64),
    token_expires_at timestamp without time zone,
    consumed_at timestamp without time zone,
    requested_by bigint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    CONSTRAINT email_verification_pkey PRIMARY KEY (id),
    CONSTRAINT email_verification_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX idx_email_verification_email ON email_verification USING btree (email, created_at DESC);

-- password_link -------------------------------------------------------------------------------------------------------

CREATE TABLE password_link (
    id bigserial NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    purpose character varying(10) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    used_at timestamp without time zone,
    created_at timestamp without time zone NOT NULL,
    CONSTRAINT chk_password_link_purpose CHECK ((purpose IN ('SETUP', 'RESET'))),
    CONSTRAINT password_link_pkey PRIMARY KEY (id),
    CONSTRAINT password_link_token_hash_key UNIQUE (token_hash),
    CONSTRAINT password_link_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_password_link_user ON password_link USING btree (user_id, created_at DESC);
