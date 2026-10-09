-- Notifications with no title or message carry no information (created by status changes that had no text)
DELETE FROM notification
WHERE btrim(title) = ''
   OR btrim(message) = '';

ALTER TABLE notification
    ADD CONSTRAINT chk_notification_title_not_blank CHECK (btrim(title) <> ''),
    ADD CONSTRAINT chk_notification_message_not_blank CHECK (btrim(message) <> '');
