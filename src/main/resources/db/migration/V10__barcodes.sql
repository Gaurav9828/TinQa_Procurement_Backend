-- Unit barcodes, their history and correction requests.
-- Tables: barcodes, barcode_events, barcode_requests.

-- barcodes ------------------------------------------------------------------------------------------------------------

CREATE TABLE barcodes (
    id bigserial NOT NULL,
    code character varying(32) NOT NULL,
    unit_type character varying(20) NOT NULL,
    status character varying(30) NOT NULL,
    item_id bigint,
    stock_id bigint,
    ecommerce_product_id bigint,
    product_title character varying(200),
    fitted_into_id bigint,
    status_note character varying(500),
    locked_at timestamp with time zone,
    locked_by bigint,
    created_by bigint,
    created_by_name character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_barcodes_code CHECK (((code)::text ~ '^[A-Z0-9][A-Z0-9-]{6,30}[A-Z0-9]$'::text)),
    CONSTRAINT chk_barcodes_fitted CHECK ((((status)::text <> 'FITTED'::text) OR (((unit_type)::text = 'ITEM_UNIT'::text) AND (fitted_into_id IS NOT NULL)))),
    CONSTRAINT chk_barcodes_owner CHECK (((((unit_type)::text = 'ITEM_UNIT'::text) AND (item_id IS NOT NULL) AND (stock_id IS NOT NULL) AND (ecommerce_product_id IS NULL)) OR (((unit_type)::text = 'PRODUCT_UNIT'::text) AND (ecommerce_product_id IS NOT NULL) AND (item_id IS NULL) AND (stock_id IS NULL)))),
    CONSTRAINT chk_barcodes_status CHECK ((status IN ('PENDING', 'IN_STOCK', 'FITTED', 'REMOVED', 'WITHDRAWAL_PENDING', 'ON_DEMO', 'VOID'))),
    CONSTRAINT chk_barcodes_unit_type CHECK ((unit_type IN ('ITEM_UNIT', 'PRODUCT_UNIT'))),
    CONSTRAINT barcodes_pkey PRIMARY KEY (id),
    CONSTRAINT barcodes_fitted_into_id_fkey FOREIGN KEY (fitted_into_id) REFERENCES barcodes(id),
    CONSTRAINT barcodes_item_id_fkey FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT barcodes_stock_id_fkey FOREIGN KEY (stock_id) REFERENCES stock(id)
);

CREATE INDEX idx_barcodes_code ON barcodes USING btree (code);
CREATE INDEX idx_barcodes_fitted_into ON barcodes USING btree (fitted_into_id) WHERE (fitted_into_id IS NOT NULL);
CREATE INDEX idx_barcodes_item_status ON barcodes USING btree (item_id, status) WHERE (item_id IS NOT NULL);
CREATE INDEX idx_barcodes_product ON barcodes USING btree (ecommerce_product_id, status) WHERE (ecommerce_product_id IS NOT NULL);
CREATE INDEX idx_barcodes_stock ON barcodes USING btree (stock_id, status) WHERE (stock_id IS NOT NULL);
CREATE UNIQUE INDEX uq_barcodes_live_code ON barcodes USING btree (code) WHERE ((status)::text <> 'VOID'::text);

-- barcode_events ------------------------------------------------------------------------------------------------------

CREATE TABLE barcode_events (
    id bigserial NOT NULL,
    barcode_id bigint NOT NULL,
    event_type character varying(30) NOT NULL,
    from_status character varying(30),
    to_status character varying(30) NOT NULL,
    from_fitted_into_id bigint,
    to_fitted_into_id bigint,
    old_code character varying(32),
    new_code character varying(32),
    movement_id uuid,
    note character varying(500),
    actor_id bigint,
    actor_name character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT barcode_events_pkey PRIMARY KEY (id),
    CONSTRAINT barcode_events_barcode_id_fkey FOREIGN KEY (barcode_id) REFERENCES barcodes(id),
    CONSTRAINT barcode_events_from_fitted_into_id_fkey FOREIGN KEY (from_fitted_into_id) REFERENCES barcodes(id),
    CONSTRAINT barcode_events_to_fitted_into_id_fkey FOREIGN KEY (to_fitted_into_id) REFERENCES barcodes(id)
);

CREATE INDEX idx_barcode_events_barcode ON barcode_events USING btree (barcode_id, id);
CREATE INDEX idx_barcode_events_movement ON barcode_events USING btree (movement_id, id) WHERE (movement_id IS NOT NULL);

-- barcode_requests ----------------------------------------------------------------------------------------------------

CREATE TABLE barcode_requests (
    id bigserial NOT NULL,
    request_type character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    barcode_id bigint,
    current_code character varying(32),
    new_code character varying(32),
    stock_id bigint,
    codes jsonb,
    reason character varying(500) NOT NULL,
    requested_by bigint NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    decision_reason character varying(500),
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_barcode_requests_status CHECK ((status IN ('PENDING', 'APPROVED', 'REJECTED'))),
    CONSTRAINT chk_barcode_requests_target CHECK (((((request_type)::text = 'CORRECTION'::text) AND (barcode_id IS NOT NULL) AND (new_code IS NOT NULL)) OR (((request_type)::text = 'ASSIGNMENT'::text) AND (stock_id IS NOT NULL) AND (codes IS NOT NULL)))),
    CONSTRAINT chk_barcode_requests_type CHECK ((request_type IN ('CORRECTION', 'ASSIGNMENT'))),
    CONSTRAINT barcode_requests_pkey PRIMARY KEY (id),
    CONSTRAINT barcode_requests_barcode_id_fkey FOREIGN KEY (barcode_id) REFERENCES barcodes(id),
    CONSTRAINT barcode_requests_stock_id_fkey FOREIGN KEY (stock_id) REFERENCES stock(id)
);

CREATE INDEX idx_barcode_requests_status ON barcode_requests USING btree (status, requested_at, id);
CREATE UNIQUE INDEX uq_barcode_requests_pending_correction ON barcode_requests USING btree (barcode_id) WHERE (((status)::text = 'PENDING'::text) AND ((request_type)::text = 'CORRECTION'::text));
