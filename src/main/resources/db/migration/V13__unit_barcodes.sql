-- ==========================================
-- V13: a barcode on every accountable unit
-- ==========================================
-- Every piece the company owns carries its own barcode, entered (or scanned) by the admin who records it: each passed
-- unit of item stock received from a dealer, and each unit of a storefront (Ecommerce) product built from those items.
-- A product unit lists the item units fitted into it, so a barcode leads to its batch, purchase order and dealer, to the
-- product it was built into, and from a product to every part inside it.
--
-- A barcode is registered PENDING with what it belongs to, and is locked once that is approved (the stock entry, or the
-- product). After that only a Super Admin (BARCODE_APPROVE) can change it; anyone else asks for the correction and a
-- Super Admin decides. Everything that ever happened to a barcode is kept in barcode_events.

CREATE TABLE barcodes
(
    id                   BIGSERIAL PRIMARY KEY,
    -- Upper-case letters, digits and '-', 8-32 characters (EAN-8/13, UPC-A, Code 128 labels...)
    code                 VARCHAR(32)              NOT NULL,
    unit_type            VARCHAR(20)              NOT NULL,
    status               VARCHAR(30)              NOT NULL,
    -- Item units: the item and the stock entry (batch -> purchase order -> dealer) the piece was received in
    item_id              BIGINT REFERENCES items (id),
    stock_id             BIGINT REFERENCES stock (id),
    -- Product units: the Ecommerce product (other database, so no foreign key) and its title when registered
    ecommerce_product_id BIGINT,
    product_title        VARCHAR(200),
    -- Item units built into a product unit
    fitted_into_id       BIGINT REFERENCES barcodes (id),
    -- Why the unit is in its status (e.g. the reason stock was reduced)
    status_note          VARCHAR(500),
    -- Set when what the barcode belongs to was approved: from then on only a Super Admin can change the code
    locked_at            TIMESTAMP WITH TIME ZONE,
    locked_by            BIGINT,
    -- Admin user id, or the Ecommerce admin's email for product units registered through the service API
    created_by           BIGINT,
    created_by_name      VARCHAR(255),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version              BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT chk_barcodes_code CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{6,30}[A-Z0-9]$'),
    CONSTRAINT chk_barcodes_unit_type CHECK (unit_type IN ('ITEM_UNIT', 'PRODUCT_UNIT')),
    CONSTRAINT chk_barcodes_status CHECK (status IN ('PENDING', 'IN_STOCK', 'FITTED', 'REMOVED', 'WITHDRAWAL_PENDING', 'VOID')),
    CONSTRAINT chk_barcodes_owner CHECK (
        (unit_type = 'ITEM_UNIT' AND item_id IS NOT NULL AND stock_id IS NOT NULL AND ecommerce_product_id IS NULL)
            OR (unit_type = 'PRODUCT_UNIT' AND ecommerce_product_id IS NOT NULL AND item_id IS NULL AND stock_id IS NULL)),
    CONSTRAINT chk_barcodes_fitted CHECK (status <> 'FITTED' OR (unit_type = 'ITEM_UNIT' AND fitted_into_id IS NOT NULL))
);

-- A code belongs to one live unit; a voided registration (rejected, reversed or corrected away) frees it
CREATE UNIQUE INDEX uq_barcodes_live_code ON barcodes (code) WHERE status <> 'VOID';
CREATE INDEX idx_barcodes_code ON barcodes (code);
CREATE INDEX idx_barcodes_stock ON barcodes (stock_id, status) WHERE stock_id IS NOT NULL;
CREATE INDEX idx_barcodes_item_status ON barcodes (item_id, status) WHERE item_id IS NOT NULL;
CREATE INDEX idx_barcodes_product ON barcodes (ecommerce_product_id, status) WHERE ecommerce_product_id IS NOT NULL;
CREATE INDEX idx_barcodes_fitted_into ON barcodes (fitted_into_id) WHERE fitted_into_id IS NOT NULL;

-- Every change of a barcode, oldest first. Changes made by an item stock movement carry its id, so reversing the
-- movement undoes exactly them
CREATE TABLE barcode_events
(
    id                  BIGSERIAL PRIMARY KEY,
    barcode_id          BIGINT                   NOT NULL REFERENCES barcodes (id),
    event_type          VARCHAR(30)              NOT NULL,
    from_status         VARCHAR(30),
    to_status           VARCHAR(30)              NOT NULL,
    from_fitted_into_id BIGINT REFERENCES barcodes (id),
    to_fitted_into_id   BIGINT REFERENCES barcodes (id),
    old_code            VARCHAR(32),
    new_code            VARCHAR(32),
    movement_id         UUID,
    note                VARCHAR(500),
    actor_id            BIGINT,
    actor_name          VARCHAR(255),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_barcode_events_barcode ON barcode_events (barcode_id, id);
CREATE INDEX idx_barcode_events_movement ON barcode_events (movement_id, id) WHERE movement_id IS NOT NULL;

-- Changes to locked barcodes, decided by a Super Admin: a corrected code (CORRECTION), or codes for stock received
-- before barcodes were recorded (ASSIGNMENT). A Super Admin's own request is applied at once and kept as history
CREATE TABLE barcode_requests
(
    id              BIGSERIAL PRIMARY KEY,
    request_type    VARCHAR(20)              NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    barcode_id      BIGINT REFERENCES barcodes (id),
    current_code    VARCHAR(32),
    new_code        VARCHAR(32),
    stock_id        BIGINT REFERENCES stock (id),
    codes           JSONB,
    reason          VARCHAR(500)             NOT NULL,
    requested_by    BIGINT                   NOT NULL,
    requested_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by      BIGINT,
    decided_at      TIMESTAMP WITH TIME ZONE,
    decision_reason VARCHAR(500),
    version         BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT chk_barcode_requests_type CHECK (request_type IN ('CORRECTION', 'ASSIGNMENT')),
    CONSTRAINT chk_barcode_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_barcode_requests_target CHECK (
        (request_type = 'CORRECTION' AND barcode_id IS NOT NULL AND new_code IS NOT NULL)
            OR (request_type = 'ASSIGNMENT' AND stock_id IS NOT NULL AND codes IS NOT NULL))
);

-- One open correction per barcode
CREATE UNIQUE INDEX uq_barcode_requests_pending_correction ON barcode_requests (barcode_id)
    WHERE status = 'PENDING' AND request_type = 'CORRECTION';
CREATE INDEX idx_barcode_requests_status ON barcode_requests (status, requested_at, id);
