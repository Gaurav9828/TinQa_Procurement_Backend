-- 1. Quantity of the linked item consumed by a product (e.g. 2 motherboards)
ALTER TABLE products
    ADD COLUMN quantity NUMERIC(14, 3) NOT NULL DEFAULT 1;

ALTER TABLE products
    ALTER COLUMN quantity DROP DEFAULT;

ALTER TABLE products
    ADD CONSTRAINT chk_products_quantity_positive CHECK (quantity > 0);

-- 2. Which stock entries a product's quantity was taken from (FIFO by arrival).
--    Used for traceability and to return stock if the product is deleted.
CREATE TABLE product_stock_allocations
(
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    stock_id   BIGINT         NOT NULL REFERENCES stock (id),
    quantity   NUMERIC(14, 3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,

    CONSTRAINT chk_product_stock_allocations_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_product_stock_allocations_product ON product_stock_allocations (product_id);
CREATE INDEX idx_product_stock_allocations_stock ON product_stock_allocations (stock_id);
