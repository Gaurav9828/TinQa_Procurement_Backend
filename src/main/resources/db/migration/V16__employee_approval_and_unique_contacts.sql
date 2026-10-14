-- 1. Who created an employee, and the Admin L2 decision on employees that need approval
ALTER TABLE employee
    ADD COLUMN created_by                BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN approval_decided_by       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN approval_decided_at       TIMESTAMP,
    ADD COLUMN approval_rejection_reason VARCHAR(500);

-- 2. Contact details identify one person: no two employees share a phone or personal email
--    (the service also checks alternate phones and different spellings of the same number)
CREATE UNIQUE INDEX uq_employee_phone ON employee (phone);
CREATE UNIQUE INDEX uq_employee_personal_email ON employee (lower(personal_email)) WHERE personal_email IS NOT NULL;

-- 3. Usernames and emails are unique regardless of letter case
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
