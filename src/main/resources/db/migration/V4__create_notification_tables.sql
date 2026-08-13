-- Notification System
CREATE TABLE notification (
                              id BIGSERIAL PRIMARY KEY,
                              title VARCHAR(150) NOT NULL,
                              message TEXT NOT NULL,
                              broadcast BOOLEAN NOT NULL DEFAULT FALSE,
                              created_by BIGINT,
                              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              CONSTRAINT fk_notification_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE notification_recipient (
                                        id BIGSERIAL PRIMARY KEY,
                                        notification_id BIGINT NOT NULL,
                                        user_id BIGINT NOT NULL,
                                        read BOOLEAN NOT NULL DEFAULT FALSE,
                                        read_at TIMESTAMP,
                                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        CONSTRAINT fk_notification_recipient_notification FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE,
                                        CONSTRAINT fk_notification_recipient_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                                        CONSTRAINT uk_notification_recipient UNIQUE (notification_id, user_id)
);

CREATE INDEX idx_notification_recipient_user ON notification_recipient(user_id);
CREATE INDEX idx_notification_recipient_user_read ON notification_recipient(user_id, read);
CREATE INDEX idx_notification_created_at ON notification(created_at);