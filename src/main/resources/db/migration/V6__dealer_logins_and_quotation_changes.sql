-- Dealers sign in to the web app with their own login (role DEALER). A Dealer Manager creates the login and it can
-- only sign in once someone else approves it. One login per dealer.
CREATE TABLE dealer_login
(
    id               BIGSERIAL PRIMARY KEY,
    dealer_id        BIGINT                   NOT NULL UNIQUE REFERENCES dealers (id),
    user_id          BIGINT                   NOT NULL UNIQUE REFERENCES users (id),
    status           VARCHAR(20)              NOT NULL,
    created_by       BIGINT                   NOT NULL REFERENCES users (id),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by       BIGINT REFERENCES users (id),
    decided_at       TIMESTAMP WITH TIME ZONE,
    rejection_reason VARCHAR(500),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_dealer_login_status CHECK (status IN ('APPROVAL_PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'))
);

CREATE INDEX idx_dealer_login_status ON dealer_login (status);

-- A dealer's proposed edit to one of their quotations. It changes nothing until an approver accepts it; `previous`
-- keeps the values it was proposed against, so the approver sees exactly what would change.
CREATE TABLE order_change_request
(
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT                   NOT NULL REFERENCES orders (id),
    requested_by    BIGINT                   NOT NULL REFERENCES users (id),
    requested_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    changes         JSONB                    NOT NULL,
    previous        JSONB                    NOT NULL,
    note            VARCHAR(500),
    status          VARCHAR(20)              NOT NULL,
    decided_by      BIGINT REFERENCES users (id),
    decided_at      TIMESTAMP WITH TIME ZONE,
    decision_reason VARCHAR(500),
    CONSTRAINT chk_order_change_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);

-- At most one open request per order, so two concurrent requests can never both be waiting
CREATE UNIQUE INDEX uq_order_change_request_open ON order_change_request (order_id) WHERE status = 'PENDING';
CREATE INDEX idx_order_change_request_status ON order_change_request (status, requested_at);
CREATE INDEX idx_order_change_request_order ON order_change_request (order_id, requested_at);
