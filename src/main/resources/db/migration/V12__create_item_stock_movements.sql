-- Item stock movements requested by other services (e.g. Ecommerce products built from items).
-- One row per movementId (idempotency key); allocations record exactly which stock entries moved.
CREATE TABLE item_stock_movements
(
    id           BIGSERIAL PRIMARY KEY,
    movement_id  UUID         NOT NULL UNIQUE,
    source       VARCHAR(50)  NOT NULL, -- e.g. ECOMMERCE_PRODUCT
    reference    VARCHAR(100) NOT NULL, -- e.g. product:42; returns only draw on what this reference consumed
    status       VARCHAR(20)  NOT NULL, -- APPLIED, REVERSED
    performed_by VARCHAR(255),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    reversed_at  TIMESTAMP WITH TIME ZONE,
    reversed_by  VARCHAR(255)
);

CREATE INDEX idx_item_stock_movements_source_reference ON item_stock_movements (source, reference);

CREATE TABLE item_stock_movement_allocations
(
    id          BIGSERIAL PRIMARY KEY,
    movement_id BIGINT         NOT NULL REFERENCES item_stock_movements (id) ON DELETE CASCADE,
    line_index  INT            NOT NULL,
    item_id     BIGINT         NOT NULL REFERENCES items (id),
    stock_id    BIGINT         NOT NULL REFERENCES stock (id),
    units       NUMERIC(14, 3) NOT NULL, -- positive = consumed from the stock entry, negative = returned to it
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_item_stock_movement_allocations_units_non_zero CHECK (units <> 0)
);

CREATE INDEX idx_item_stock_movement_allocations_movement ON item_stock_movement_allocations (movement_id);
CREATE INDEX idx_item_stock_movement_allocations_item_stock ON item_stock_movement_allocations (item_id, stock_id);
