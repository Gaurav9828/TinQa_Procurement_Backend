-- Salary payments and their lines (maker-checker).
-- Tables: salary_payment, salary_payment_line.

-- salary_payment ------------------------------------------------------------------------------------------------------

CREATE TABLE salary_payment (
    id bigserial NOT NULL,
    employee_id bigint NOT NULL,
    pay_year integer NOT NULL,
    pay_month integer NOT NULL,
    status character varying(20) NOT NULL,
    currency character varying(10) NOT NULL,
    monthly_salary numeric(12,2) NOT NULL,
    total_earnings numeric(14,2) NOT NULL,
    total_deductions numeric(14,2) NOT NULL,
    net_pay numeric(14,2) NOT NULL,
    designation character varying(100) NOT NULL,
    department character varying(100) NOT NULL,
    transfer_date date NOT NULL,
    bank_reference character varying(100),
    note character varying(500),
    recorded_by bigint NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    rejection_reason character varying(500),
    payslip_email_to character varying(255),
    payslip_emailed_at timestamp with time zone,
    payslip_email_attempts integer DEFAULT 0 NOT NULL,
    payslip_email_last_attempt_at timestamp with time zone,
    payslip_email_error character varying(500),
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_salary_payment_month CHECK (((pay_month >= 1) AND (pay_month <= 12))),
    CONSTRAINT chk_salary_payment_net CHECK ((net_pay > (0)::numeric)),
    CONSTRAINT chk_salary_payment_status CHECK ((status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN'))),
    CONSTRAINT chk_salary_payment_totals CHECK ((net_pay = (total_earnings - total_deductions))),
    CONSTRAINT chk_salary_payment_year CHECK (((pay_year >= 2000) AND (pay_year <= 2100))),
    CONSTRAINT salary_payment_pkey PRIMARY KEY (id),
    CONSTRAINT salary_payment_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES employee(id)
);

CREATE INDEX idx_salary_payment_decided ON salary_payment USING btree (decided_at, id) WHERE ((status)::text = 'APPROVED'::text);
CREATE INDEX idx_salary_payment_email_due ON salary_payment USING btree (id) WHERE (((status)::text = 'APPROVED'::text) AND (payslip_emailed_at IS NULL));
CREATE INDEX idx_salary_payment_period ON salary_payment USING btree (pay_year, pay_month);
CREATE UNIQUE INDEX uq_salary_payment_live ON salary_payment USING btree (employee_id, pay_year, pay_month) WHERE (status IN ('PENDING_APPROVAL', 'APPROVED'));

-- salary_payment_line -------------------------------------------------------------------------------------------------

CREATE TABLE salary_payment_line (
    id bigserial NOT NULL,
    salary_payment_id bigint NOT NULL,
    kind character varying(10) NOT NULL,
    label character varying(100) NOT NULL,
    amount numeric(12,2) NOT NULL,
    "position" integer NOT NULL,
    CONSTRAINT chk_salary_payment_line_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT chk_salary_payment_line_kind CHECK ((kind IN ('EARNING', 'DEDUCTION'))),
    CONSTRAINT salary_payment_line_pkey PRIMARY KEY (id),
    CONSTRAINT salary_payment_line_salary_payment_id_fkey FOREIGN KEY (salary_payment_id) REFERENCES salary_payment(id) ON DELETE CASCADE
);

CREATE INDEX idx_salary_payment_line_payment ON salary_payment_line USING btree (salary_payment_id, "position");
