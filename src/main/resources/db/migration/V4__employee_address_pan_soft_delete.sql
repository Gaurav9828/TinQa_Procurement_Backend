-- Employees get a PAN and a residential address (required for new employees by the API; older rows stay NULL),
-- and deleting an employee now keeps the row with status DELETED and who deleted it, instead of removing it.
ALTER TABLE employee
    ADD COLUMN pan_number      VARCHAR(10),
    ADD COLUMN house_number    VARCHAR(100),
    ADD COLUMN street          VARCHAR(500),
    ADD COLUMN landmark        VARCHAR(255),
    ADD COLUMN district        VARCHAR(100),
    ADD COLUMN state           VARCHAR(100),
    ADD COLUMN pincode         VARCHAR(10),
    ADD COLUMN google_maps_url TEXT,
    ADD COLUMN deleted_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN deleted_at      TIMESTAMP;

-- A PAN identifies one person
CREATE UNIQUE INDEX uq_employee_pan_number ON employee (pan_number) WHERE pan_number IS NOT NULL;
