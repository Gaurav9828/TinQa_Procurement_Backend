-- A notification can point at the admin UI page it is about (e.g. /approvals); older rows have no link.
ALTER TABLE notification
    ADD COLUMN link VARCHAR(255);
