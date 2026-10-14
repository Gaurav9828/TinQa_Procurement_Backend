-- Admin L2 approval of products created in the Ecommerce service (product rows live there; no FK).
CREATE TABLE product_approval_requests
(
    id                   BIGSERIAL PRIMARY KEY,
    ecommerce_product_id BIGINT                   NOT NULL,
    product_title        VARCHAR(150)             NOT NULL,
    request_type         VARCHAR(20)              NOT NULL, -- CREATE, RESUBMISSION
    status               VARCHAR(20)              NOT NULL, -- PENDING, APPROVED, REJECTED, CANCELLED
    snapshot             JSONB                    NOT NULL, -- what the reviewer saw
    requested_by         BIGINT                   NOT NULL REFERENCES users (id),
    requested_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by           BIGINT REFERENCES users (id),
    decided_at           TIMESTAMP WITH TIME ZONE,
    decision_reason      VARCHAR(500),
    version              BIGINT                   NOT NULL DEFAULT 0,

    CONSTRAINT chk_product_approval_requests_type CHECK (request_type IN ('CREATE', 'RESUBMISSION')),
    CONSTRAINT chk_product_approval_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_product_approval_requests_reason CHECK (
        status NOT IN ('REJECTED', 'CANCELLED') OR btrim(coalesce(decision_reason, '')) <> '')
);

CREATE INDEX idx_product_approval_requests_product_requested
    ON product_approval_requests (ecommerce_product_id, requested_at DESC);
CREATE INDEX idx_product_approval_requests_status ON product_approval_requests (status);

-- At most one request per product can be waiting for a decision
CREATE UNIQUE INDEX uq_product_approval_requests_one_pending
    ON product_approval_requests (ecommerce_product_id) WHERE status = 'PENDING';
