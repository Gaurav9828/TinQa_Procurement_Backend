-- Notifications and who received them.
-- Tables: notification, notification_recipient.

-- notification --------------------------------------------------------------------------------------------------------

CREATE TABLE notification (
    id bigserial NOT NULL,
    title character varying(150) NOT NULL,
    message text NOT NULL,
    broadcast boolean DEFAULT false NOT NULL,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    link character varying(255),
    CONSTRAINT chk_notification_message_not_blank CHECK ((btrim(message) <> ''::text)),
    CONSTRAINT chk_notification_title_not_blank CHECK ((btrim((title)::text) <> ''::text)),
    CONSTRAINT notification_pkey PRIMARY KEY (id),
    CONSTRAINT notification_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE INDEX idx_notification_created_at ON notification USING btree (created_at);

-- notification_recipient ----------------------------------------------------------------------------------------------

CREATE TABLE notification_recipient (
    id bigserial NOT NULL,
    notification_id bigint NOT NULL,
    user_id bigint NOT NULL,
    read boolean DEFAULT false NOT NULL,
    read_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT notification_recipient_pkey PRIMARY KEY (id),
    CONSTRAINT uk_notification_recipient UNIQUE (notification_id, user_id),
    CONSTRAINT notification_recipient_notification_id_fkey FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE,
    CONSTRAINT notification_recipient_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_notification_recipient_user_read ON notification_recipient USING btree (user_id, read);
