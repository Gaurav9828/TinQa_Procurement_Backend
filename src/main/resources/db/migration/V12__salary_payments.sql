-- ==========================================
-- V12: monthly salary payments and payslips
-- ==========================================
-- Salaries are transferred from the bank outside the system; each month someone records that an employee was paid
-- (the employee's monthly salary plus any extra earnings / deductions for that month) and a different Level 2
-- approver confirms it. Only an approved payment is posted to the company account (read by the e-commerce service),
-- emailed to the employee as a payslip, and downloadable. The payslip itself is never stored: it is rendered from
-- this row whenever it is asked for.

CREATE TABLE salary_payment
(
    id                            BIGSERIAL PRIMARY KEY,
    employee_id                   BIGINT                   NOT NULL REFERENCES employee (id),
    pay_year                      INTEGER                  NOT NULL,
    pay_month                     INTEGER                  NOT NULL,
    status                        VARCHAR(20)              NOT NULL,
    currency                      VARCHAR(10)              NOT NULL,
    -- The employee's monthly salary when the payment was recorded (later raises don't change old payslips)
    monthly_salary                NUMERIC(12, 2)           NOT NULL,
    total_earnings                NUMERIC(14, 2)           NOT NULL,
    total_deductions              NUMERIC(14, 2)           NOT NULL,
    net_pay                       NUMERIC(14, 2)           NOT NULL,
    -- As they were in the month paid, for the payslip
    designation                   VARCHAR(100)             NOT NULL,
    department                    VARCHAR(100)             NOT NULL,
    transfer_date                 DATE                     NOT NULL,
    bank_reference                VARCHAR(100),
    note                          VARCHAR(500),
    recorded_by                   BIGINT                   NOT NULL,
    recorded_at                   TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by                    BIGINT,
    decided_at                    TIMESTAMP WITH TIME ZONE,
    rejection_reason              VARCHAR(500),
    -- Payslip email: sent by a background job after approval, retried a few times
    payslip_email_to              VARCHAR(255),
    payslip_emailed_at            TIMESTAMP WITH TIME ZONE,
    payslip_email_attempts        INTEGER                  NOT NULL DEFAULT 0,
    payslip_email_last_attempt_at TIMESTAMP WITH TIME ZONE,
    payslip_email_error           VARCHAR(500),
    updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_salary_payment_month CHECK (pay_month BETWEEN 1 AND 12),
    CONSTRAINT chk_salary_payment_year CHECK (pay_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_salary_payment_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_salary_payment_net CHECK (net_pay > 0),
    CONSTRAINT chk_salary_payment_totals CHECK (net_pay = total_earnings - total_deductions)
);

-- One live payment per employee and month; rejected and withdrawn ones stay as history
CREATE UNIQUE INDEX uq_salary_payment_live ON salary_payment (employee_id, pay_year, pay_month)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED');
CREATE INDEX idx_salary_payment_period ON salary_payment (pay_year, pay_month);
-- The company account's feed reads approved payments in approval order
CREATE INDEX idx_salary_payment_decided ON salary_payment (decided_at, id) WHERE status = 'APPROVED';
CREATE INDEX idx_salary_payment_email_due ON salary_payment (id) WHERE status = 'APPROVED' AND payslip_emailed_at IS NULL;

-- Extra earnings (bonus, overtime …) and deductions (unpaid leave, TDS, PF …) of one month
CREATE TABLE salary_payment_line
(
    id                BIGSERIAL PRIMARY KEY,
    salary_payment_id BIGINT         NOT NULL REFERENCES salary_payment (id) ON DELETE CASCADE,
    kind              VARCHAR(10)    NOT NULL,
    label             VARCHAR(100)   NOT NULL,
    amount            NUMERIC(12, 2) NOT NULL,
    position          INTEGER        NOT NULL,
    CONSTRAINT chk_salary_payment_line_kind CHECK (kind IN ('EARNING', 'DEDUCTION')),
    CONSTRAINT chk_salary_payment_line_amount CHECK (amount > 0)
);

CREATE INDEX idx_salary_payment_line_payment ON salary_payment_line (salary_payment_id, position);
