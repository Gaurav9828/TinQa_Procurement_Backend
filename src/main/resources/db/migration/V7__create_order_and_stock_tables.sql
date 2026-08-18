-- 1. Orders Table
CREATE TABLE orders
(
    id                  BIGSERIAL PRIMARY KEY,
    order_number        VARCHAR(50)    NOT NULL UNIQUE, -- Unique varchar order id
    dealer_id           BIGINT         NOT NULL REFERENCES dealers (id),
    item_id             BIGINT         NOT NULL REFERENCES items (id),
    order_quantity      NUMERIC(14, 3) NOT NULL,
    unit_type           VARCHAR(20)    NOT NULL, -- KG, PCS, LITERS, etc.
    unit_price          NUMERIC(12, 2) NOT NULL,
    total_price         NUMERIC(14, 2) NOT NULL,
    shipment_price      NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    tax_breakup         JSONB          NOT NULL DEFAULT '{}'::jsonb,
    order_status        VARCHAR(30)    NOT NULL DEFAULT 'PENDING', -- PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    expected_delivery   DATE,
    actual_delivery     DATE,
    order_date          DATE           NOT NULL,
    additional_info     JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by          BIGINT         NOT NULL REFERENCES users (id),
    updated_by          BIGINT         REFERENCES users (id)
);

CREATE INDEX idx_orders_number ON orders (order_number);
CREATE INDEX idx_orders_dealer ON orders (dealer_id);
CREATE INDEX idx_orders_item ON orders (item_id);
CREATE INDEX idx_orders_status ON orders (order_status);

-- 2. Stock Table Linked Hand-in-Hand with Orders
CREATE TABLE stock
(
    id                        BIGSERIAL PRIMARY KEY,
    stock_identity_number      VARCHAR(100)   NOT NULL UNIQUE,
    batch_number              VARCHAR(100)   NOT NULL,
    order_number              VARCHAR(50)    NOT NULL REFERENCES orders (order_number), -- Linked directly to order_number
    dealer_id                 BIGINT         NOT NULL REFERENCES dealers (id),
    item_id                   BIGINT         NOT NULL REFERENCES items (id),

    -- Inventory Inspection & Quantities (Primary total quantity sourced from linked order_number)
    units_passed_test         NUMERIC(14, 3) NOT NULL DEFAULT 0.000,
    defected_units            NUMERIC(14, 3) NOT NULL DEFAULT 0.000,
    available_units           NUMERIC(14, 3) NOT NULL DEFAULT 0.000,

    -- Quality Testing & Additional Info
    has_tested                BOOLEAN        NOT NULL DEFAULT FALSE,
    additional_info           JSONB          NOT NULL DEFAULT '{}'::jsonb,

    -- Admin L2 Approval Workflow
    approval_status           VARCHAR(30)    NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    approved_by               BIGINT         REFERENCES users (id),
    approved_at               TIMESTAMP WITH TIME ZONE,
    rejection_reason          VARCHAR(500),

    -- Audit & Timestamps
    date_of_arrival           DATE           NOT NULL,
    is_active                 BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by                BIGINT         NOT NULL REFERENCES users (id),
    updated_by                BIGINT         REFERENCES users (id)
);

CREATE INDEX idx_stock_identity_number ON stock (stock_identity_number);
CREATE INDEX idx_stock_batch_number ON stock (batch_number);
CREATE INDEX idx_stock_order_number ON stock (order_number);
CREATE INDEX idx_stock_item ON stock (item_id);
CREATE INDEX idx_stock_dealer ON stock (dealer_id);
CREATE INDEX idx_stock_approval_status ON stock (approval_status);