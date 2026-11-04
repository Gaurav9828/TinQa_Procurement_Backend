-- ==========================================
-- V19: custom roles
-- ==========================================
-- A custom role (e.g. "Marketing Executive") is a designation kept in the database instead of the Designation enum:
-- a code generated from its label, a department and a set of permission groups. An employee holds exactly one
-- designation: a built-in one (employee.designation) or a custom role (employee.custom_role_id). Access is worked out
-- the same way for both (DesignationAccess): SELF_SERVICE is always added and ADMIN_L1 drops the approval groups.

CREATE TABLE custom_role
(
    id          BIGSERIAL PRIMARY KEY,
    -- Generated from the label, never changes; unique across built-in designations too (checked by the service)
    code        VARCHAR(60)              NOT NULL,
    label       VARCHAR(100)             NOT NULL,
    department  VARCHAR(100)             NOT NULL,
    description VARCHAR(500),
    -- Inactive roles keep their employees but are not offered for new ones
    active      BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_by  BIGINT REFERENCES users (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by  BIGINT REFERENCES users (id),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_custom_role_code UNIQUE (code),
    CONSTRAINT chk_custom_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,59}$')
);

-- The permission groups a custom role carries (SELF_SERVICE is added on top, never stored). Dealer-portal and system
-- administration groups can never be part of a custom role
CREATE TABLE custom_role_group
(
    role_id          BIGINT      NOT NULL REFERENCES custom_role (id) ON DELETE CASCADE,
    permission_group VARCHAR(60) NOT NULL,
    PRIMARY KEY (role_id, permission_group),
    CONSTRAINT chk_custom_role_group_allowed CHECK (permission_group NOT IN ('DEALER_PORTAL', 'SYSTEM_ADMINISTRATION',
                                                                            'SELF_SERVICE'))
);

ALTER TABLE employee
    ALTER COLUMN designation DROP NOT NULL;
ALTER TABLE employee
    ADD COLUMN custom_role_id BIGINT REFERENCES custom_role (id);
-- Exactly one designation: built-in or custom
ALTER TABLE employee
    ADD CONSTRAINT chk_employee_one_designation CHECK ((designation IS NULL) <> (custom_role_id IS NULL));

-- Role list employee counts, "role in use" checks and department follow-ups of a custom role's employees
CREATE INDEX idx_employee_custom_role ON employee (custom_role_id) WHERE custom_role_id IS NOT NULL;
-- Built-in role employee counts (GROUP BY designation)
CREATE INDEX idx_employee_designation ON employee (designation) WHERE designation IS NOT NULL;
