-- ==========================================
-- V14: client demos and meetings
-- ==========================================
-- Staff visit clients (hotels, offices, hospitals …) for a demo appointment or a promotion. A visit is planned as an
-- internal cost estimate (the quotation: proposed budget, days, attending staff, an optional day-by-day stay plan),
-- approved by a Level 2 approver, then the actual hotel, food, travel and other bills are recorded while it runs.
-- Completion is approved too, and only then is the total spent booked to the company account (read by the e-commerce
-- service from client_demo_posting). A completed demo is changed only by a Super Admin, who reopens it with a reason;
-- the corrected total is approved again and just the difference is booked.

CREATE TABLE client_demo
(
    id                      BIGSERIAL PRIMARY KEY,
    demo_type               VARCHAR(20)              NOT NULL,
    status                  VARCHAR(30)              NOT NULL,
    client_category         VARCHAR(30)              NOT NULL,
    -- What kind of place it is when the category is OTHER
    client_category_other   VARCHAR(100),
    client_name             VARCHAR(150)             NOT NULL,
    contact_person          VARCHAR(100),
    contact_phone           VARCHAR(20)              NOT NULL,
    contact_email           VARCHAR(255),
    address_line            VARCHAR(300)             NOT NULL,
    city                    VARCHAR(100)             NOT NULL,
    state                   VARCHAR(100),
    pincode                 VARCHAR(10),
    map_link                VARCHAR(500),
    start_date              DATE                     NOT NULL,
    start_time              TIME                     NOT NULL,
    number_of_days          INTEGER                  NOT NULL,
    proposed_budget         NUMERIC(12, 2)           NOT NULL,
    purpose                 VARCHAR(1000),
    -- What came of the visit, written when completion is submitted
    outcome                 VARCHAR(1000),
    created_by              BIGINT                   NOT NULL REFERENCES users (id),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Who sent the quotation / completion for approval last, and who decided it
    submitted_by            BIGINT REFERENCES users (id),
    submitted_at            TIMESTAMP WITH TIME ZONE,
    decided_by              BIGINT REFERENCES users (id),
    decided_at              TIMESTAMP WITH TIME ZONE,
    rejection_reason        VARCHAR(500),
    cancel_reason           VARCHAR(500),
    -- The Super Admin's reason for reopening a completed demo
    reopen_reason           VARCHAR(500),
    -- What the company account holds for this demo: the sum of its postings
    booked_total            NUMERIC(14, 2)           NOT NULL DEFAULT 0,
    completed_at            TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    version                 BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT chk_client_demo_type CHECK (demo_type IN ('APPOINTMENT', 'PROMOTION')),
    CONSTRAINT chk_client_demo_status CHECK (status IN ('DRAFT', 'QUOTATION_PENDING', 'APPROVED', 'COMPLETION_PENDING',
                                                        'COMPLETED', 'REOPENED', 'CANCELLED')),
    CONSTRAINT chk_client_demo_category CHECK (client_category IN ('HOTEL', 'HOME_STAY', 'CORPORATE_OFFICE', 'HOSPITAL',
                                                                    'CLINIC', 'HOME_VISIT', 'SHOPPING_MALL', 'RESTAURANT',
                                                                    'PUBLIC_PLACE', 'OTHER')),
    CONSTRAINT chk_client_demo_days CHECK (number_of_days BETWEEN 1 AND 60),
    CONSTRAINT chk_client_demo_budget CHECK (proposed_budget >= 0)
);

CREATE INDEX idx_client_demo_start ON client_demo (start_date DESC, id DESC);
CREATE INDEX idx_client_demo_status ON client_demo (status);

-- The staff tagged as attending; they see the demo under "My demos" and record their own bills on it
CREATE TABLE client_demo_staff
(
    demo_id     BIGINT NOT NULL REFERENCES client_demo (id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employee (id),
    PRIMARY KEY (demo_id, employee_id)
);

CREATE INDEX idx_client_demo_staff_employee ON client_demo_staff (employee_id);

-- Every cost of a visit: a night's stay, a meal, a leg of travel, or anything else (which needs a reason). Kind decides
-- which columns are used; vendor is the hotel, restaurant, transport operator or payee
CREATE TABLE client_demo_expense
(
    id               BIGSERIAL PRIMARY KEY,
    demo_id          BIGINT                   NOT NULL REFERENCES client_demo (id) ON DELETE CASCADE,
    kind             VARCHAR(10)              NOT NULL,
    day_number       INTEGER,
    vendor_name      VARCHAR(150),
    vendor_address   VARCHAR(300),
    vendor_map_link  VARCHAR(500),
    vendor_phone     VARCHAR(20),
    vendor_gstin     VARCHAR(15),
    meal_type        VARCHAR(10),
    from_place       VARCHAR(150),
    to_place         VARCHAR(150),
    travel_mode      VARCHAR(20),
    -- Invoice number, or the ticket / PNR number for travel
    reference_number VARCHAR(60),
    amount           NUMERIC(12, 2)           NOT NULL,
    -- The GST included in amount, when the invoice shows it
    gst_amount       NUMERIC(12, 2),
    description      VARCHAR(300),
    reason           VARCHAR(500),
    document_id      BIGINT REFERENCES document (id),
    added_by         BIGINT                   NOT NULL REFERENCES users (id),
    added_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by       BIGINT REFERENCES users (id),
    updated_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_client_demo_expense_kind CHECK (kind IN ('STAY', 'FOOD', 'TRAVEL', 'OTHER')),
    CONSTRAINT chk_client_demo_expense_meal CHECK (meal_type IS NULL OR meal_type IN ('BREAKFAST', 'LUNCH', 'SNACKS', 'DINNER')),
    CONSTRAINT chk_client_demo_expense_amount CHECK (amount >= 0),
    CONSTRAINT chk_client_demo_expense_gst CHECK (gst_amount IS NULL OR (gst_amount >= 0 AND gst_amount <= amount)),
    CONSTRAINT chk_client_demo_expense_day CHECK (day_number IS NULL OR day_number BETWEEN 1 AND 60)
);

CREATE INDEX idx_client_demo_expense_demo ON client_demo_expense (demo_id, day_number, id);

-- Everything that happened to a demo, with the reasons given: the timeline shown on it
CREATE TABLE client_demo_event
(
    id       BIGSERIAL PRIMARY KEY,
    demo_id  BIGINT                   NOT NULL REFERENCES client_demo (id) ON DELETE CASCADE,
    type     VARCHAR(30)              NOT NULL,
    actor_id BIGINT                   NOT NULL REFERENCES users (id),
    at       TIMESTAMP WITH TIME ZONE NOT NULL,
    detail   VARCHAR(1000),
    reason   VARCHAR(500)
);

CREATE INDEX idx_client_demo_event_demo ON client_demo_event (demo_id, id);

-- What is booked to the company account: the approved total at completion, then the difference of each approved
-- correction. Append-only; the e-commerce service reads it in creation order and posts each row once
CREATE TABLE client_demo_posting
(
    id          BIGSERIAL PRIMARY KEY,
    demo_id     BIGINT                   NOT NULL REFERENCES client_demo (id),
    kind        VARCHAR(20)              NOT NULL,
    amount      NUMERIC(14, 2)           NOT NULL,
    occurred_on DATE                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_client_demo_posting_kind CHECK (kind IN ('COMPLETED', 'CORRECTION')),
    CONSTRAINT chk_client_demo_posting_amount CHECK (amount <> 0)
);

CREATE INDEX idx_client_demo_posting_created ON client_demo_posting (created_at, id);
