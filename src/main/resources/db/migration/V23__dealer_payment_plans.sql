-- Dealer payment plans, payments made to dealers and refund claims against them.
--
-- A quotation says how the dealer wants to be paid: an advance and a midterm percentage (the final part is the rest,
-- paid only after the delivered units are tested, i.e. once the order's stock entry is approved) and the payment
-- method they expect. Orders made before this have no plan (both percentages NULL).
ALTER TABLE orders
    ADD COLUMN advance_percent         NUMERIC(5, 2),
    ADD COLUMN midterm_percent         NUMERIC(5, 2),
    ADD COLUMN expected_payment_method VARCHAR(20),
    ADD CONSTRAINT chk_orders_payment_plan CHECK (
        (advance_percent IS NULL AND midterm_percent IS NULL)
        OR (advance_percent >= 0 AND midterm_percent >= 0 AND advance_percent + midterm_percent <= 100)),
    ADD CONSTRAINT chk_orders_expected_payment_method CHECK (
        expected_payment_method IS NULL OR expected_payment_method IN ('CASH', 'ONLINE', 'BANK_TRANSFER', 'CHEQUE'));

-- What TinQa paid a dealer for an order (PAYMENT, against a milestone), what the dealer paid back (REFUND), and
-- corrections of either (REVERSAL of one approved entry, same amount and milestone). Recorded by one person and
-- confirmed by another; only APPROVED rows count. Amounts are always positive: the kind gives the direction.
CREATE TABLE dealer_payment
(
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT                   NOT NULL REFERENCES orders (id),
    kind            VARCHAR(20)              NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND', 'REVERSAL')),
    milestone       VARCHAR(20) CHECK (milestone IN ('ADVANCE', 'MIDTERM', 'FINAL')),
    amount          NUMERIC(14, 2)           NOT NULL CHECK (amount > 0),
    method          VARCHAR(20)              NOT NULL CHECK (method IN ('CASH', 'ONLINE', 'BANK_TRANSFER', 'CHEQUE')),
    reference       VARCHAR(100),
    paid_on         DATE                     NOT NULL,
    note            VARCHAR(500),
    reverses_id     BIGINT REFERENCES dealer_payment (id),
    status          VARCHAR(20)              NOT NULL
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    recorded_by     BIGINT                   NOT NULL,
    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by      BIGINT,
    decided_at      TIMESTAMP WITH TIME ZONE,
    decision_reason VARCHAR(500),
    CONSTRAINT chk_dealer_payment_milestone CHECK (kind <> 'PAYMENT' OR milestone IS NOT NULL),
    CONSTRAINT chk_dealer_payment_refund CHECK (kind <> 'REFUND' OR milestone IS NULL),
    CONSTRAINT chk_dealer_payment_reverses CHECK ((kind = 'REVERSAL') = (reverses_id IS NOT NULL))
);

-- GET /v1/orders/{id}/payments and the per-order sums, oldest first
CREATE INDEX idx_dealer_payment_order ON dealer_payment (order_id, recorded_at, id);
-- The Approvals Hub queue: waiting payments, oldest first (a small subset of a growing table)
CREATE INDEX idx_dealer_payment_waiting ON dealer_payment (recorded_at, id) WHERE status = 'PENDING_APPROVAL';
-- An entry is reversed at most once (a rejected or withdrawn reversal frees it again)
CREATE UNIQUE INDEX uq_dealer_payment_reversal ON dealer_payment (reverses_id)
    WHERE kind = 'REVERSAL' AND status IN ('PENDING_APPROVAL', 'APPROVED');

-- What a dealer owes back for an order: opened automatically when testing (stock approval), a cancellation or a completed return leaves
-- TinQa having paid more than the order is now worth. A Level 2 can change the agreed amount with a reason; the claim
-- is settled once the dealer's approved refunds reach it.
CREATE TABLE dealer_refund_claim
(
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT                   NOT NULL UNIQUE REFERENCES orders (id),
    cause          VARCHAR(30)              NOT NULL CHECK (cause IN ('FAILED_TESTING', 'ORDER_CANCELLED', 'ORDER_RETURNED')),
    failed_units   NUMERIC(14, 3)           NOT NULL DEFAULT 0 CHECK (failed_units >= 0),
    claimed_amount NUMERIC(14, 2)           NOT NULL CHECK (claimed_amount >= 0),
    agreed_amount  NUMERIC(14, 2)           NOT NULL CHECK (agreed_amount >= 0),
    adjust_reason  VARCHAR(500),
    adjusted_by    BIGINT,
    adjusted_at    TIMESTAMP WITH TIME ZONE,
    status         VARCHAR(20)              NOT NULL CHECK (status IN ('OPEN', 'SETTLED')),
    opened_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at     TIMESTAMP WITH TIME ZONE
);

-- Approvals Hub: the dealer payments waiting for confirmation are one more queue.
CREATE OR REPLACE FUNCTION approval_queue_counts(p_queues TEXT[])
    RETURNS TABLE (queue TEXT, waiting BIGINT)
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
-- GET /v1/documents?status=WAITING_FOR_APPROVAL
SELECT 'DOCUMENTS', count(*) FROM document
WHERE 'DOCUMENTS' = ANY (p_queues) AND status = 'WAITING_FOR_APPROVAL'
UNION ALL
-- GET /admin/profile-approvals
SELECT 'PROFILE_CHANGES', count(*) FROM employee_profile_change_request
WHERE 'PROFILE_CHANGES' = ANY (p_queues) AND status = 'PENDING'
UNION ALL
-- GET /v1/stocks?status=PENDING (StockRepository.findAwaitingApproval)
SELECT 'STOCK', count(*) FROM stock
WHERE 'STOCK' = ANY (p_queues)
  AND (approval_status = 'PENDING'
    OR (approval_status = 'APPROVED' AND approved_units_passed IS NOT NULL
        AND (units_passed_test <> approved_units_passed OR defected_units <> approved_defected_units)))
UNION ALL
-- Quotations a dealer raised, minus those with a dealer change waiting (decided in the Dealers tab first)
SELECT 'QUOTATIONS', count(*) FROM orders o
WHERE 'QUOTATIONS' = ANY (p_queues) AND o.order_status = 'QUOTATION'
  AND EXISTS (SELECT 1 FROM users u WHERE u.id = o.created_by AND u.role = 'DEALER')
  AND NOT EXISTS (SELECT 1 FROM order_change_request c WHERE c.order_id = o.id AND c.status = 'PENDING')
UNION ALL
SELECT 'ORDERS', count(*) FROM orders
WHERE 'ORDERS' = ANY (p_queues) AND order_status = 'PENDING'
UNION ALL
SELECT 'RETURNS', count(*) FROM orders
WHERE 'RETURNS' = ANY (p_queues) AND order_status = 'RETURN_REQUESTED'
UNION ALL
-- GET /v1/product-approvals?status=PENDING
SELECT 'PRODUCTS', count(*) FROM product_approval_requests
WHERE 'PRODUCTS' = ANY (p_queues) AND status = 'PENDING'
UNION ALL
-- GET /v1/admin/employees/pending-approvals
SELECT 'EMPLOYEES', count(*) FROM employee
WHERE 'EMPLOYEES' = ANY (p_queues) AND status = 'APPROVAL_PENDING'
UNION ALL
-- GET /v1/admin/dealer-logins?status=APPROVAL_PENDING
SELECT 'DEALER_LOGINS', count(*) FROM dealer_login
WHERE 'DEALER_LOGINS' = ANY (p_queues) AND status = 'APPROVAL_PENDING'
UNION ALL
-- GET /v1/orders/change-requests?status=PENDING
SELECT 'QUOTATION_CHANGES', count(*) FROM order_change_request
WHERE 'QUOTATION_CHANGES' = ANY (p_queues) AND status = 'PENDING'
UNION ALL
-- GET /v1/barcodes/requests?status=PENDING
SELECT 'BARCODES', count(*) FROM barcode_requests
WHERE 'BARCODES' = ANY (p_queues) AND status = 'PENDING'
UNION ALL
-- GET /v1/admin/client-demos/waiting
SELECT 'CLIENT_DEMOS', count(*) FROM client_demo
WHERE 'CLIENT_DEMOS' = ANY (p_queues) AND status IN ('QUOTATION_PENDING', 'COMPLETION_PENDING')
UNION ALL
-- GET /v1/orders/payments?status=PENDING_APPROVAL (DealerPaymentService.waiting)
SELECT 'DEALER_PAYMENTS', count(*) FROM dealer_payment
WHERE 'DEALER_PAYMENTS' = ANY (p_queues) AND status = 'PENDING_APPROVAL'
$$;
