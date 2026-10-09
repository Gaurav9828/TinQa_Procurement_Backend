-- An order's quantity is received into exactly one stock entry; a second entry for the same order would
-- duplicate that quantity. Enforced here so concurrent requests cannot both create one.
ALTER TABLE stock
    ADD CONSTRAINT uq_stock_order_number UNIQUE (order_number);

-- The unique constraint's index replaces the plain one
DROP INDEX IF EXISTS idx_stock_order_number;
