-- Client demos: staff, visits, products, units, expenses, postings and history.
-- Tables: client_demo, client_demo_staff, client_demo_visit, client_demo_product, client_demo_unit, client_demo_expense, client_demo_event, client_demo_posting.

-- client_demo ---------------------------------------------------------------------------------------------------------

CREATE TABLE client_demo (
    id bigserial NOT NULL,
    demo_type character varying(20) NOT NULL,
    status character varying(30) NOT NULL,
    client_category character varying(30) NOT NULL,
    client_category_other character varying(100),
    client_name character varying(150) NOT NULL,
    contact_person character varying(100),
    contact_phone character varying(20) NOT NULL,
    contact_email character varying(255),
    address_line character varying(300) NOT NULL,
    city character varying(100) NOT NULL,
    state character varying(100),
    pincode character varying(10),
    map_link character varying(500),
    start_date date NOT NULL,
    start_time time without time zone NOT NULL,
    number_of_days integer NOT NULL,
    proposed_budget numeric(12,2) NOT NULL,
    purpose character varying(1000),
    outcome character varying(1000),
    created_by bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    submitted_by bigint,
    submitted_at timestamp with time zone,
    decided_by bigint,
    decided_at timestamp with time zone,
    rejection_reason character varying(500),
    cancel_reason character varying(500),
    reopen_reason character varying(500),
    booked_total numeric(14,2) DEFAULT 0 NOT NULL,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    extra_budget numeric(12,2) DEFAULT 0 NOT NULL,
    extra_budget_reason character varying(1000),
    CONSTRAINT chk_client_demo_budget CHECK ((proposed_budget >= (0)::numeric)),
    CONSTRAINT chk_client_demo_category CHECK ((client_category IN ('HOTEL', 'HOME_STAY', 'CORPORATE_OFFICE', 'HOSPITAL', 'CLINIC', 'HOME_VISIT', 'SHOPPING_MALL', 'RESTAURANT', 'PUBLIC_PLACE', 'OTHER'))),
    CONSTRAINT chk_client_demo_days CHECK (((number_of_days >= 1) AND (number_of_days <= 60))),
    CONSTRAINT chk_client_demo_extra_budget CHECK (((extra_budget >= (0)::numeric) AND ((extra_budget = (0)::numeric) OR (extra_budget_reason IS NOT NULL)))),
    CONSTRAINT chk_client_demo_status CHECK ((status IN ('DRAFT', 'QUOTATION_PENDING', 'APPROVED', 'COMPLETION_PENDING', 'COMPLETED', 'REOPENED', 'CANCELLED'))),
    CONSTRAINT chk_client_demo_type CHECK ((demo_type IN ('APPOINTMENT', 'PROMOTION'))),
    CONSTRAINT client_demo_pkey PRIMARY KEY (id),
    CONSTRAINT client_demo_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT client_demo_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT client_demo_submitted_by_fkey FOREIGN KEY (submitted_by) REFERENCES users(id)
);

CREATE INDEX idx_client_demo_start ON client_demo USING btree (start_date DESC, id DESC);
CREATE INDEX idx_client_demo_status_submitted ON client_demo USING btree (status, submitted_at, id);

-- client_demo_staff ---------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_staff (
    demo_id bigint NOT NULL,
    employee_id bigint NOT NULL,
    CONSTRAINT client_demo_staff_pkey PRIMARY KEY (demo_id, employee_id),
    CONSTRAINT client_demo_staff_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id) ON DELETE CASCADE,
    CONSTRAINT client_demo_staff_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES employee(id)
);

CREATE INDEX idx_client_demo_staff_employee ON client_demo_staff USING btree (employee_id);

-- client_demo_visit ---------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_visit (
    id bigserial NOT NULL,
    demo_id bigint NOT NULL,
    client_category character varying(30) NOT NULL,
    client_category_other character varying(100),
    client_name character varying(150) NOT NULL,
    legal_name character varying(150),
    gstin character varying(15),
    contact_person character varying(100),
    contact_phone character varying(20) NOT NULL,
    contact_email character varying(255),
    address_line character varying(300) NOT NULL,
    city character varying(100) NOT NULL,
    state character varying(100),
    pincode character varying(10),
    map_link character varying(500),
    day_number integer NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    client_phone character varying(20) NOT NULL,
    client_email character varying(255),
    CONSTRAINT chk_client_demo_visit_category CHECK ((client_category IN ('HOTEL', 'HOME_STAY', 'CORPORATE_OFFICE', 'HOSPITAL', 'CLINIC', 'HOME_VISIT', 'SHOPPING_MALL', 'RESTAURANT', 'PUBLIC_PLACE', 'OTHER'))),
    CONSTRAINT chk_client_demo_visit_day CHECK (((day_number >= 1) AND (day_number <= 60))),
    CONSTRAINT chk_client_demo_visit_slot CHECK ((end_time > start_time)),
    CONSTRAINT client_demo_visit_pkey PRIMARY KEY (id),
    CONSTRAINT client_demo_visit_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id) ON DELETE CASCADE
);

CREATE INDEX idx_client_demo_visit_demo ON client_demo_visit USING btree (demo_id, day_number, start_time);

-- client_demo_product -------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_product (
    demo_id bigint NOT NULL,
    product_id bigint NOT NULL,
    product_name character varying(200) NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT chk_client_demo_product_quantity CHECK (((quantity >= 1) AND (quantity <= 1000))),
    CONSTRAINT client_demo_product_pkey PRIMARY KEY (demo_id, product_id),
    CONSTRAINT client_demo_product_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id) ON DELETE CASCADE
);

-- client_demo_unit ----------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_unit (
    id bigserial NOT NULL,
    demo_id bigint NOT NULL,
    barcode_id bigint NOT NULL,
    code character varying(32) NOT NULL,
    product_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    handed_out_at timestamp with time zone,
    handed_out_by bigint,
    returned_at timestamp with time zone,
    returned_by bigint,
    out_move_key character varying(80),
    back_move_key character varying(80),
    write_off_reason character varying(500),
    CONSTRAINT chk_client_demo_unit_out CHECK (((status IN ('OUT', 'RETURNED', 'WRITTEN_OFF')) = (handed_out_at IS NOT NULL))),
    CONSTRAINT chk_client_demo_unit_status CHECK ((status IN ('RESERVED', 'OUT', 'RETURNED', 'WRITTEN_OFF', 'NOT_TAKEN'))),
    CONSTRAINT client_demo_unit_pkey PRIMARY KEY (id),
    CONSTRAINT uq_client_demo_unit UNIQUE (demo_id, barcode_id),
    CONSTRAINT client_demo_unit_barcode_id_fkey FOREIGN KEY (barcode_id) REFERENCES barcodes(id),
    CONSTRAINT client_demo_unit_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id) ON DELETE CASCADE,
    CONSTRAINT client_demo_unit_handed_out_by_fkey FOREIGN KEY (handed_out_by) REFERENCES users(id),
    CONSTRAINT client_demo_unit_returned_by_fkey FOREIGN KEY (returned_by) REFERENCES users(id)
);

CREATE INDEX idx_client_demo_unit_barcode_active ON client_demo_unit USING btree (barcode_id) WHERE (status IN ('RESERVED', 'OUT'));

-- client_demo_expense -------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_expense (
    id bigserial NOT NULL,
    demo_id bigint NOT NULL,
    kind character varying(10) NOT NULL,
    day_number integer,
    vendor_name character varying(150),
    vendor_address character varying(300),
    vendor_map_link character varying(500),
    vendor_phone character varying(20),
    vendor_gstin character varying(15),
    meal_type character varying(10),
    from_place character varying(150),
    to_place character varying(150),
    travel_mode character varying(20),
    reference_number character varying(60),
    amount numeric(12,2) NOT NULL,
    gst_amount numeric(12,2),
    description character varying(300),
    reason character varying(500),
    document_id bigint,
    added_by bigint NOT NULL,
    added_at timestamp with time zone NOT NULL,
    updated_by bigint,
    updated_at timestamp with time zone,
    start_time time without time zone,
    end_time time without time zone,
    start_date date,
    end_date date,
    CONSTRAINT chk_client_demo_expense_amount CHECK ((amount >= (0)::numeric)),
    CONSTRAINT chk_client_demo_expense_day CHECK (((day_number IS NULL) OR ((day_number >= 1) AND (day_number <= 60)))),
    CONSTRAINT chk_client_demo_expense_gst CHECK (((gst_amount IS NULL) OR ((gst_amount >= (0)::numeric) AND (gst_amount <= amount)))),
    CONSTRAINT chk_client_demo_expense_kind CHECK ((kind IN ('STAY', 'FOOD', 'TRAVEL', 'OTHER'))),
    CONSTRAINT chk_client_demo_expense_meal CHECK (((meal_type IS NULL) OR (meal_type IN ('BREAKFAST', 'LUNCH', 'SNACKS', 'DINNER')))),
    CONSTRAINT chk_client_demo_expense_times CHECK ((((start_time IS NULL) = (end_time IS NULL)) AND ((start_date IS NULL) = (start_time IS NULL)) AND ((end_date IS NULL) = (start_time IS NULL)) AND ((end_date IS NULL) OR ((end_date + end_time) > (start_date + start_time))))),
    CONSTRAINT client_demo_expense_pkey PRIMARY KEY (id),
    CONSTRAINT client_demo_expense_added_by_fkey FOREIGN KEY (added_by) REFERENCES users(id),
    CONSTRAINT client_demo_expense_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id) ON DELETE CASCADE,
    CONSTRAINT client_demo_expense_document_id_fkey FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT client_demo_expense_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES users(id)
);

CREATE INDEX idx_client_demo_expense_demo ON client_demo_expense USING btree (demo_id, day_number, id);

-- client_demo_event ---------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_event (
    id bigserial NOT NULL,
    demo_id bigint NOT NULL,
    type character varying(30) NOT NULL,
    actor_id bigint NOT NULL,
    at timestamp with time zone NOT NULL,
    detail character varying(1000),
    reason character varying(500),
    CONSTRAINT client_demo_event_pkey PRIMARY KEY (id),
    CONSTRAINT client_demo_event_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT client_demo_event_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id) ON DELETE CASCADE
);

CREATE INDEX idx_client_demo_event_demo ON client_demo_event USING btree (demo_id, id);

-- client_demo_posting -------------------------------------------------------------------------------------------------

CREATE TABLE client_demo_posting (
    id bigserial NOT NULL,
    demo_id bigint NOT NULL,
    kind character varying(20) NOT NULL,
    amount numeric(14,2) NOT NULL,
    occurred_on date NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_client_demo_posting_amount CHECK ((amount <> (0)::numeric)),
    CONSTRAINT chk_client_demo_posting_kind CHECK ((kind IN ('COMPLETED', 'CORRECTION'))),
    CONSTRAINT client_demo_posting_pkey PRIMARY KEY (id),
    CONSTRAINT client_demo_posting_demo_id_fkey FOREIGN KEY (demo_id) REFERENCES client_demo(id)
);

CREATE INDEX idx_client_demo_posting_created ON client_demo_posting USING btree (created_at, id);
