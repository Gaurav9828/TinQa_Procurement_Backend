-- Changes to an approved product also need Admin L2 approval (request_type = UPDATE).
ALTER TABLE product_approval_requests
    ADD COLUMN proposed_changes  JSONB,                    -- full Ecommerce PUT body to apply once approved (opaque here)
    ADD COLUMN previous_snapshot JSONB,                    -- live product when the change was requested
    ADD COLUMN change_summary    JSONB,                    -- [{label, oldValue, newValue}] for the reviewer
    ADD COLUMN applied_at        TIMESTAMP WITH TIME ZONE, -- when the approved change was written to Ecommerce
    ADD COLUMN applied_by        BIGINT REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE product_approval_requests
    DROP CONSTRAINT chk_product_approval_requests_type;

ALTER TABLE product_approval_requests
    ADD CONSTRAINT chk_product_approval_requests_type CHECK (request_type IN ('CREATE', 'RESUBMISSION', 'UPDATE')),
    ADD CONSTRAINT chk_product_approval_requests_update_fields CHECK (
        request_type <> 'UPDATE'
            OR (jsonb_typeof(proposed_changes) = 'object' AND previous_snapshot IS NOT NULL
                AND jsonb_typeof(change_summary) = 'array')),
    -- Changes are only approved or rejected, never cancelled, and only approved changes can be applied
    ADD CONSTRAINT chk_product_approval_requests_update_status CHECK (request_type <> 'UPDATE' OR status <> 'CANCELLED'),
    ADD CONSTRAINT chk_product_approval_requests_applied CHECK (applied_at IS NULL OR (request_type = 'UPDATE' AND status = 'APPROVED'));
