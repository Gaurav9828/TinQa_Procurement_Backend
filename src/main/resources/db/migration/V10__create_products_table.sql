-- Products Table (each product is an instance of a configured item)
CREATE TABLE products
(
    id         BIGSERIAL PRIMARY KEY,
    product_id VARCHAR(50) NOT NULL UNIQUE, -- Business identifier, e.g. PRD-1728375000000-AB12
    item_id    BIGINT      NOT NULL REFERENCES items (id),
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);

CREATE INDEX idx_products_item ON products (item_id);
CREATE INDEX idx_products_is_active ON products (is_active);
