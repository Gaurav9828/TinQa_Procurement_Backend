-- Purchase orders and quotations: status history, change requests, invoices, supplier payments and refund claims.
-- Tables: orders, order_status_history, order_change_request, order_invoice, supplier_payment, supplier_refund_claim.

-- orders --------------------------------------------------------------------------------------------------------------

CREATE TABLE orders (
    id bigserial NOT NULL,
    order_number character varying(50) NOT NULL,
    supplier_id bigint NOT NULL,
    item_id bigint NOT NULL,
    order_quantity numeric(14,3) NOT NULL,
    unit_type character varying(20) NOT NULL,
    unit_price numeric(12,2) NOT NULL,
    total_price numeric(14,2) NOT NULL,
    shipment_price numeric(12,2) DEFAULT 0.00 NOT NULL,
    tax_breakup jsonb DEFAULT '{}'::jsonb NOT NULL,
    order_status character varying(30) DEFAULT 'QUOTATION'::character varying NOT NULL,
    expected_delivery date,
    actual_delivery date,
    order_date date NOT NULL,
    additional_info jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint NOT NULL,
    updated_by bigint,
    advance_percent numeric(5,2),
    midterm_percent numeric(5,2),
    expected_payment_method character varying(20),
    CONSTRAINT chk_orders_expected_payment_method CHECK (((expected_payment_method IS NULL) OR (expected_payment_method IN ('CASH', 'ONLINE', 'BANK_TRANSFER', 'CHEQUE')))),
    CONSTRAINT chk_orders_payment_plan CHECK ((((advance_percent IS NULL) AND (midterm_percent IS NULL)) OR ((advance_percent >= (0)::numeric) AND (midterm_percent >= (0)::numeric) AND ((advance_percent + midterm_percent) <= (100)::numeric)))),
    CONSTRAINT chk_orders_status CHECK (((order_status)::text = ANY (ARRAY[('QUOTATION'::character varying)::text, ('PENDING'::character varying)::text, ('SUPPLIER_LEVEL_PENDING'::character varying)::text, ('CONFIRMED'::character varying)::text, ('SHIPPED'::character varying)::text, ('DELIVERED'::character varying)::text, ('CANCELLED'::character varying)::text, ('RETURN_REQUESTED'::character varying)::text, ('RETURN_APPROVED'::character varying)::text, ('RETURN_REJECTED'::character varying)::text, ('RETURN_PICKED_UP'::character varying)::text, ('RETURN_RECEIVED'::character varying)::text, ('REFUND_INITIATED'::character varying)::text, ('REFUND_FAILED'::character varying)::text, ('REFUND_COMPLETED'::character varying)::text]))),
    CONSTRAINT orders_pkey PRIMARY KEY (id),
    CONSTRAINT orders_order_number_key UNIQUE (order_number),
    CONSTRAINT orders_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT orders_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT orders_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT orders_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES users(id)
);

CREATE INDEX idx_orders_item ON orders USING btree (item_id);
CREATE INDEX idx_orders_status_id ON orders USING btree (order_status, id);
CREATE INDEX idx_orders_supplier ON orders USING btree (supplier_id);

-- order_status_history ------------------------------------------------------------------------------------------------

CREATE TABLE order_status_history (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    from_status character varying(30),
    to_status character varying(30) NOT NULL,
    changed_by bigint NOT NULL,
    reason character varying(500),
    actual_delivery date,
    changed_at timestamp with time zone NOT NULL,
    CONSTRAINT order_status_history_pkey PRIMARY KEY (id),
    CONSTRAINT order_status_history_changed_by_fkey FOREIGN KEY (changed_by) REFERENCES users(id),
    CONSTRAINT order_status_history_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE INDEX idx_order_status_history_order ON order_status_history USING btree (order_id, changed_at);

-- order_change_request ------------------------------------------------------------------------------------------------

CREATE TABLE order_change_request (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    requested_by bigint NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    changes jsonb NOT NULL,
    previous jsonb NOT NULL,
    note character varying(500),
    status character varying(20) NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    decision_reason character varying(500),
    CONSTRAINT chk_order_change_request_status CHECK ((status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))),
    CONSTRAINT order_change_request_pkey PRIMARY KEY (id),
    CONSTRAINT order_change_request_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT order_change_request_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT order_change_request_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES users(id)
);

CREATE INDEX idx_order_change_request_order ON order_change_request USING btree (order_id, requested_at);
CREATE INDEX idx_order_change_request_status ON order_change_request USING btree (status, requested_at);
CREATE UNIQUE INDEX uq_order_change_request_open ON order_change_request USING btree (order_id) WHERE ((status)::text = 'PENDING'::text);

-- order_invoice -------------------------------------------------------------------------------------------------------

CREATE TABLE order_invoice (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    document_id bigint NOT NULL,
    invoice_number character varying(40),
    status character varying(20) NOT NULL,
    expected_total numeric(14,2) NOT NULL,
    found_total numeric(14,2),
    checks jsonb NOT NULL,
    uploaded_by bigint NOT NULL,
    uploaded_at timestamp with time zone NOT NULL,
    override_by bigint,
    override_at timestamp with time zone,
    override_reason character varying(500),
    CONSTRAINT chk_order_invoice_status CHECK ((status IN ('MATCHED', 'MISMATCH', 'UNVERIFIED'))),
    CONSTRAINT order_invoice_pkey PRIMARY KEY (id),
    CONSTRAINT order_invoice_document_id_fkey FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT order_invoice_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT order_invoice_override_by_fkey FOREIGN KEY (override_by) REFERENCES users(id),
    CONSTRAINT order_invoice_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES users(id)
);

CREATE INDEX idx_order_invoice_order ON order_invoice USING btree (order_id, id);

-- supplier_payment ----------------------------------------------------------------------------------------------------

CREATE TABLE supplier_payment (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    kind character varying(20) NOT NULL,
    milestone character varying(20),
    amount numeric(14,2) NOT NULL,
    method character varying(20) NOT NULL,
    reference character varying(100),
    paid_on date NOT NULL,
    note character varying(500),
    reverses_id bigint,
    status character varying(20) NOT NULL,
    recorded_by bigint NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    decision_reason character varying(500),
    CONSTRAINT chk_supplier_payment_milestone CHECK ((((kind)::text <> 'PAYMENT'::text) OR (milestone IS NOT NULL))),
    CONSTRAINT chk_supplier_payment_refund CHECK ((((kind)::text <> 'REFUND'::text) OR (milestone IS NULL))),
    CONSTRAINT chk_supplier_payment_reverses CHECK ((((kind)::text = 'REVERSAL'::text) = (reverses_id IS NOT NULL))),
    CONSTRAINT supplier_payment_amount_check CHECK ((amount > (0)::numeric)),
    CONSTRAINT supplier_payment_kind_check CHECK ((kind IN ('PAYMENT', 'REFUND', 'REVERSAL'))),
    CONSTRAINT supplier_payment_method_check CHECK ((method IN ('CASH', 'ONLINE', 'BANK_TRANSFER', 'CHEQUE'))),
    CONSTRAINT supplier_payment_milestone_check CHECK ((milestone IN ('ADVANCE', 'MIDTERM', 'FINAL'))),
    CONSTRAINT supplier_payment_status_check CHECK ((status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN'))),
    CONSTRAINT supplier_payment_pkey PRIMARY KEY (id),
    CONSTRAINT supplier_payment_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT supplier_payment_reverses_id_fkey FOREIGN KEY (reverses_id) REFERENCES supplier_payment(id)
);

CREATE INDEX idx_supplier_payment_order ON supplier_payment USING btree (order_id, recorded_at, id);
CREATE INDEX idx_supplier_payment_waiting ON supplier_payment USING btree (recorded_at, id) WHERE ((status)::text = 'PENDING_APPROVAL'::text);
CREATE UNIQUE INDEX uq_supplier_payment_reversal ON supplier_payment USING btree (reverses_id) WHERE (((kind)::text = 'REVERSAL'::text) AND (status IN ('PENDING_APPROVAL', 'APPROVED')));

-- supplier_refund_claim -----------------------------------------------------------------------------------------------

CREATE TABLE supplier_refund_claim (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    cause character varying(30) NOT NULL,
    failed_units numeric(14,3) DEFAULT 0 NOT NULL,
    claimed_amount numeric(14,2) NOT NULL,
    agreed_amount numeric(14,2) NOT NULL,
    adjust_reason character varying(500),
    adjusted_by bigint,
    adjusted_at timestamp with time zone,
    status character varying(20) NOT NULL,
    opened_at timestamp with time zone NOT NULL,
    settled_at timestamp with time zone,
    CONSTRAINT supplier_refund_claim_agreed_amount_check CHECK ((agreed_amount >= (0)::numeric)),
    CONSTRAINT supplier_refund_claim_cause_check CHECK ((cause IN ('FAILED_TESTING', 'ORDER_CANCELLED', 'ORDER_RETURNED'))),
    CONSTRAINT supplier_refund_claim_claimed_amount_check CHECK ((claimed_amount >= (0)::numeric)),
    CONSTRAINT supplier_refund_claim_failed_units_check CHECK ((failed_units >= (0)::numeric)),
    CONSTRAINT supplier_refund_claim_status_check CHECK ((status IN ('OPEN', 'SETTLED'))),
    CONSTRAINT supplier_refund_claim_pkey PRIMARY KEY (id),
    CONSTRAINT supplier_refund_claim_order_id_key UNIQUE (order_id),
    CONSTRAINT supplier_refund_claim_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id)
);
