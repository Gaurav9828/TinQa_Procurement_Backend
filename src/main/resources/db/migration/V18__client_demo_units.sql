-- ==========================================
-- V18: client demos: the product units taken, by barcode
-- ==========================================
-- Every product unit staff take to a demo is picked by its barcode when the demo is planned (reserved for the demo's
-- days: no overlapping demo can pick it). On the day it is handed out: it leaves stock (barcode ON_DEMO here, and the
-- storefront's sellable stock goes down at once). Afterwards it is handed back into stock. A demo can't be completed
-- or cancelled while a unit is out; a Super Admin writes off a unit that did not come back.

ALTER TABLE barcodes DROP CONSTRAINT chk_barcodes_status;
ALTER TABLE barcodes ADD CONSTRAINT chk_barcodes_status
    CHECK (status IN ('PENDING', 'IN_STOCK', 'FITTED', 'REMOVED', 'WITHDRAWAL_PENDING', 'ON_DEMO', 'VOID'));

CREATE TABLE client_demo_unit
(
    id               BIGSERIAL PRIMARY KEY,
    demo_id          BIGINT                   NOT NULL REFERENCES client_demo (id) ON DELETE CASCADE,
    barcode_id       BIGINT                   NOT NULL REFERENCES barcodes (id),
    code             VARCHAR(32)              NOT NULL,
    product_id       BIGINT                   NOT NULL,
    status           VARCHAR(20)              NOT NULL,
    handed_out_at    TIMESTAMP WITH TIME ZONE,
    handed_out_by    BIGINT REFERENCES users (id),
    returned_at      TIMESTAMP WITH TIME ZONE,
    returned_by      BIGINT REFERENCES users (id),
    out_move_key     VARCHAR(80),
    back_move_key    VARCHAR(80),
    write_off_reason VARCHAR(500),
    CONSTRAINT uq_client_demo_unit UNIQUE (demo_id, barcode_id),
    CONSTRAINT chk_client_demo_unit_status CHECK (status IN ('RESERVED', 'OUT', 'RETURNED', 'WRITTEN_OFF', 'NOT_TAKEN')),
    CONSTRAINT chk_client_demo_unit_out CHECK ((status IN ('OUT', 'RETURNED', 'WRITTEN_OFF')) = (handed_out_at IS NOT NULL))
);

-- Which demos hold a unit (DemoUnitRepository.findHolders): only reserved and out units, a small subset
CREATE INDEX idx_client_demo_unit_barcode_active ON client_demo_unit (barcode_id) WHERE status IN ('RESERVED', 'OUT');
-- A demo's units (the demo detail); uq_client_demo_unit leads with demo_id and covers it
