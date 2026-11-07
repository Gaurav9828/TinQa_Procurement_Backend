-- Received stock, item movements, products built from stock, product approvals and purchase postings.
-- Tables: stock, item_stock_movements, item_stock_movement_allocations, products, product_stock_allocations, product_approval_requests, purchase_postings.

-- stock ---------------------------------------------------------------------------------------------------------------

CREATE TABLE stock (
    id bigserial NOT NULL,
    stock_identity_number character varying(100) NOT NULL,
    batch_number character varying(100) NOT NULL,
    order_number character varying(50) NOT NULL,
    supplier_id bigint NOT NULL,
    item_id bigint NOT NULL,
    units_passed_test numeric(14,3) DEFAULT 0.000 NOT NULL,
    defected_units numeric(14,3) DEFAULT 0.000 NOT NULL,
    available_units numeric(14,3) DEFAULT 0.000 NOT NULL,
    has_tested boolean DEFAULT false NOT NULL,
    additional_info jsonb DEFAULT '{}'::jsonb NOT NULL,
    approval_status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    approved_by bigint,
    approved_at timestamp with time zone,
    rejection_reason character varying(500),
    date_of_arrival date NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint NOT NULL,
    updated_by bigint,
    approved_units_passed numeric(14,3),
    approved_defected_units numeric(14,3),
    CONSTRAINT chk_stock_approved_counts CHECK ((((approved_units_passed IS NULL) = (approved_defected_units IS NULL)) AND ((approved_units_passed IS NULL) OR ((approved_units_passed >= (0)::numeric) AND (approved_defected_units >= (0)::numeric))))),
    CONSTRAINT stock_pkey PRIMARY KEY (id),
    CONSTRAINT stock_order_number_key UNIQUE (order_number),
    CONSTRAINT stock_stock_identity_number_key UNIQUE (stock_identity_number),
    CONSTRAINT stock_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES users(id),
    CONSTRAINT stock_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT stock_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT stock_order_number_fkey FOREIGN KEY (order_number) REFERENCES orders(order_number),
    CONSTRAINT stock_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT stock_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES users(id)
);

CREATE INDEX idx_stock_approval_status ON stock USING btree (approval_status);
CREATE INDEX idx_stock_awaiting_approval ON stock USING btree (id) WHERE (((approval_status)::text = 'PENDING'::text) OR (((approval_status)::text = 'APPROVED'::text) AND (approved_units_passed IS NOT NULL) AND ((units_passed_test <> approved_units_passed) OR (defected_units <> approved_defected_units))));
CREATE INDEX idx_stock_item ON stock USING btree (item_id);
CREATE INDEX idx_stock_supplier ON stock USING btree (supplier_id);

-- item_stock_movements ------------------------------------------------------------------------------------------------

CREATE TABLE item_stock_movements (
    id bigserial NOT NULL,
    movement_id uuid NOT NULL,
    source character varying(50) NOT NULL,
    reference character varying(100) NOT NULL,
    status character varying(20) NOT NULL,
    performed_by character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    reversed_at timestamp with time zone,
    reversed_by character varying(255),
    CONSTRAINT item_stock_movements_pkey PRIMARY KEY (id),
    CONSTRAINT item_stock_movements_movement_id_key UNIQUE (movement_id)
);

CREATE INDEX idx_item_stock_movements_source_reference ON item_stock_movements USING btree (source, reference);

-- item_stock_movement_allocations -------------------------------------------------------------------------------------

CREATE TABLE item_stock_movement_allocations (
    id bigserial NOT NULL,
    movement_id bigint NOT NULL,
    line_index integer NOT NULL,
    item_id bigint NOT NULL,
    stock_id bigint NOT NULL,
    units numeric(14,3) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_item_stock_movement_allocations_units_non_zero CHECK ((units <> (0)::numeric)),
    CONSTRAINT item_stock_movement_allocations_pkey PRIMARY KEY (id),
    CONSTRAINT item_stock_movement_allocations_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT item_stock_movement_allocations_movement_id_fkey FOREIGN KEY (movement_id) REFERENCES item_stock_movements(id) ON DELETE CASCADE,
    CONSTRAINT item_stock_movement_allocations_stock_id_fkey FOREIGN KEY (stock_id) REFERENCES stock(id)
);

CREATE INDEX idx_item_stock_movement_allocations_item_stock ON item_stock_movement_allocations USING btree (item_id, stock_id);
CREATE INDEX idx_item_stock_movement_allocations_movement ON item_stock_movement_allocations USING btree (movement_id);

-- products ------------------------------------------------------------------------------------------------------------

CREATE TABLE products (
    id bigserial NOT NULL,
    product_id character varying(50) NOT NULL,
    item_id bigint NOT NULL,
    quantity numeric(14,3) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint,
    updated_by bigint,
    CONSTRAINT chk_products_quantity_positive CHECK ((quantity > (0)::numeric)),
    CONSTRAINT products_pkey PRIMARY KEY (id),
    CONSTRAINT products_product_id_key UNIQUE (product_id),
    CONSTRAINT products_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id)
);

CREATE INDEX idx_products_is_active ON products USING btree (is_active);
CREATE INDEX idx_products_item ON products USING btree (item_id);

-- product_stock_allocations -------------------------------------------------------------------------------------------

CREATE TABLE product_stock_allocations (
    id bigserial NOT NULL,
    product_id bigint NOT NULL,
    stock_id bigint NOT NULL,
    quantity numeric(14,3) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    created_by bigint,
    CONSTRAINT chk_product_stock_allocations_quantity_positive CHECK ((quantity > (0)::numeric)),
    CONSTRAINT product_stock_allocations_pkey PRIMARY KEY (id),
    CONSTRAINT product_stock_allocations_product_id_fkey FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT product_stock_allocations_stock_id_fkey FOREIGN KEY (stock_id) REFERENCES stock(id)
);

CREATE INDEX idx_product_stock_allocations_product ON product_stock_allocations USING btree (product_id);
CREATE INDEX idx_product_stock_allocations_stock ON product_stock_allocations USING btree (stock_id);

-- product_approval_requests -------------------------------------------------------------------------------------------

CREATE TABLE product_approval_requests (
    id bigserial NOT NULL,
    ecommerce_product_id bigint NOT NULL,
    product_title character varying(150) NOT NULL,
    request_type character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    snapshot jsonb NOT NULL,
    requested_by bigint NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    decision_reason character varying(500),
    proposed_changes jsonb,
    previous_snapshot jsonb,
    change_summary jsonb,
    applied_at timestamp with time zone,
    applied_by bigint,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_product_approval_requests_applied CHECK (((applied_at IS NULL) OR (((request_type)::text = 'UPDATE'::text) AND ((status)::text = 'APPROVED'::text)))),
    CONSTRAINT chk_product_approval_requests_reason CHECK (((status NOT IN ('REJECTED', 'CANCELLED')) OR (btrim((COALESCE(decision_reason, ''::character varying))::text) <> ''::text))),
    CONSTRAINT chk_product_approval_requests_status CHECK ((status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))),
    CONSTRAINT chk_product_approval_requests_type CHECK ((request_type IN ('CREATE', 'RESUBMISSION', 'UPDATE'))),
    CONSTRAINT chk_product_approval_requests_update_fields CHECK ((((request_type)::text <> 'UPDATE'::text) OR ((jsonb_typeof(proposed_changes) = 'object'::text) AND (previous_snapshot IS NOT NULL) AND (jsonb_typeof(change_summary) = 'array'::text)))),
    CONSTRAINT chk_product_approval_requests_update_status CHECK ((((request_type)::text <> 'UPDATE'::text) OR ((status)::text <> 'CANCELLED'::text))),
    CONSTRAINT product_approval_requests_pkey PRIMARY KEY (id),
    CONSTRAINT product_approval_requests_applied_by_fkey FOREIGN KEY (applied_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT product_approval_requests_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT product_approval_requests_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES users(id)
);

CREATE INDEX idx_product_approval_requests_product_requested ON product_approval_requests USING btree (ecommerce_product_id, requested_at DESC);
CREATE INDEX idx_product_approval_requests_status_requested ON product_approval_requests USING btree (status, requested_at, id);
CREATE UNIQUE INDEX uq_product_approval_requests_one_pending ON product_approval_requests USING btree (ecommerce_product_id) WHERE ((status)::text = 'PENDING'::text);

-- purchase_postings ---------------------------------------------------------------------------------------------------

CREATE TABLE purchase_postings (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    stock_id bigint,
    reason character varying(30) NOT NULL,
    units_passed numeric(14,3) NOT NULL,
    amount numeric(14,2) NOT NULL,
    occurred_on date NOT NULL,
    legacy_history_id bigint,
    created_at timestamp with time zone NOT NULL,
    cgst numeric(14,2) DEFAULT 0 NOT NULL,
    sgst numeric(14,2) DEFAULT 0 NOT NULL,
    igst numeric(14,2) DEFAULT 0 NOT NULL,
    cess numeric(14,2) DEFAULT 0 NOT NULL,
    gst_rate numeric(5,2),
    supply_type character varying(12),
    hsn_sac character varying(8),
    CONSTRAINT chk_purchase_posting_gst_split CHECK ((((supply_type IS NULL) AND (cgst = (0)::numeric) AND (sgst = (0)::numeric) AND (igst = (0)::numeric)) OR (((supply_type)::text = 'INTRA_STATE'::text) AND (igst = (0)::numeric) AND (cgst = sgst)) OR (((supply_type)::text = 'INTER_STATE'::text) AND (cgst = (0)::numeric) AND (sgst = (0)::numeric)))),
    CONSTRAINT chk_purchase_posting_supply_type CHECK (((supply_type IS NULL) OR (supply_type IN ('INTRA_STATE', 'INTER_STATE')))),
    CONSTRAINT purchase_postings_pkey PRIMARY KEY (id),
    CONSTRAINT purchase_postings_legacy_history_id_key UNIQUE (legacy_history_id),
    CONSTRAINT purchase_postings_legacy_history_id_fkey FOREIGN KEY (legacy_history_id) REFERENCES order_status_history(id),
    CONSTRAINT purchase_postings_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT purchase_postings_stock_id_fkey FOREIGN KEY (stock_id) REFERENCES stock(id)
);

CREATE INDEX idx_purchase_postings_created ON purchase_postings USING btree (created_at, id);
CREATE INDEX idx_purchase_postings_order ON purchase_postings USING btree (order_id);
