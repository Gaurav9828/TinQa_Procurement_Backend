ALTER TABLE users
    ADD COLUMN IF NOT EXISTS is_first_login BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE users
SET is_first_login = TRUE
WHERE is_first_login IS NULL;
