-- The dealer's invoice for an order, uploaded by staff before they mark it delivered, and what checking it against the
-- quotation found. Each upload is a new row; the latest one for an order is the one that counts. A delivery whose
-- invoice did not match (or could not be read) needs an approver's override, recorded on the row.
CREATE TABLE order_invoice
(
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT                   NOT NULL REFERENCES orders (id),
    document_id     BIGINT                   NOT NULL REFERENCES document (id),
    invoice_number  VARCHAR(40),
    status          VARCHAR(20)              NOT NULL,
    expected_total  NUMERIC(14, 2)           NOT NULL,
    found_total     NUMERIC(14, 2),
    checks          JSONB                    NOT NULL,
    uploaded_by     BIGINT                   NOT NULL REFERENCES users (id),
    uploaded_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    override_by     BIGINT REFERENCES users (id),
    override_at     TIMESTAMP WITH TIME ZONE,
    override_reason VARCHAR(500),
    CONSTRAINT chk_order_invoice_status CHECK (status IN ('MATCHED', 'MISMATCH', 'UNVERIFIED'))
);

CREATE INDEX idx_order_invoice_order ON order_invoice (order_id, id);
