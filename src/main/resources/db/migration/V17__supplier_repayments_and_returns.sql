-- Area: supplier payments (V8__orders.sql, V15, V16) and returns of failed pieces to the supplier.
-- 1. Money back from a supplier is paid by the supplier and checked by a Finance Manager: a reversal of a payment the
--    supplier confirmed, a refund due on a claim, or what a return of failed pieces costs them. REPAYMENT_DUE (asked of
--    the supplier) -> REPAYMENT_SENT (the supplier gave the reference, maybe a receipt) -> APPROVED (a Finance Manager
--    confirmed it arrived) or REPAYMENT_MISSING (it did not; the supplier sends it again). No Super Admin step.
-- 2. supplier_return: a Supplier Manager raises a return of failed pieces, a Super Admin approves it, the supplier
--    accepts it, the pieces are dispatched (or collected), the supplier confirms they arrived and repays them.
-- See docs/supplier-returns-and-repayments.md in the Web UI repo.

-- supplier_payment: repayments ----------------------------------------------------------------------------------------

ALTER TABLE supplier_payment ALTER COLUMN status TYPE character varying(30);
ALTER TABLE supplier_payment DROP CONSTRAINT supplier_payment_status_check;
ALTER TABLE supplier_payment ADD CONSTRAINT supplier_payment_status_check
    CHECK ((status IN ('PENDING_APPROVAL', 'TO_PAY', 'AWAITING_SUPPLIER', 'NOT_RECEIVED', 'APPROVED', 'REJECTED', 'WITHDRAWN',
                       'REPAYMENT_DUE', 'REPAYMENT_SENT', 'REPAYMENT_MISSING')));

-- A return's repayment: the return shipping charge the supplier pays on top of the pieces (never part of the claim).
-- The pieces part may be 0 when TinQa paid nothing for them yet, but something is always owed
ALTER TABLE supplier_payment
    ADD COLUMN charge_amount numeric(14,2) DEFAULT 0 NOT NULL,
    ADD COLUMN return_id bigint;
ALTER TABLE supplier_payment DROP CONSTRAINT supplier_payment_amount_check;
ALTER TABLE supplier_payment ADD CONSTRAINT supplier_payment_amount_check
    CHECK ((amount >= (0)::numeric AND charge_amount >= (0)::numeric AND amount + charge_amount > (0)::numeric));

-- At most one reversal per entry that is confirmed or still on its way
DROP INDEX uq_supplier_payment_reversal;
CREATE UNIQUE INDEX uq_supplier_payment_reversal ON supplier_payment USING btree (reverses_id)
    WHERE (((kind)::text = 'REVERSAL'::text)
        AND ((status)::text IN ('PENDING_APPROVAL', 'APPROVED', 'REPAYMENT_DUE', 'REPAYMENT_SENT', 'REPAYMENT_MISSING')));

-- The Finance Manager's "repayments to check" list, oldest first (GET /v1/orders/payments/repayments-to-check)
CREATE INDEX idx_supplier_payment_repayment_sent ON supplier_payment USING btree (recorded_at, id)
    WHERE ((status)::text = 'REPAYMENT_SENT');
-- My Orders: per order, what the supplier still has to pay back (SupplierPaymentRepository.countRepaymentsDue)
CREATE INDEX idx_supplier_payment_repayment_due ON supplier_payment USING btree (order_id)
    WHERE ((status)::text IN ('REPAYMENT_DUE', 'REPAYMENT_MISSING'));
-- The company account's feed of confirmed repayments (GET /v1/internal/finance/supplier-receipts)
CREATE INDEX idx_supplier_payment_receipts ON supplier_payment USING btree (decided_at, id)
    WHERE ((status)::text = 'APPROVED' AND (kind)::text IN ('REFUND', 'REVERSAL'));

ALTER TABLE supplier_payment_event DROP CONSTRAINT supplier_payment_event_type_check;
ALTER TABLE supplier_payment_event ADD CONSTRAINT supplier_payment_event_type_check
    CHECK ((type IN ('REQUESTED', 'CORRECTED', 'REQUEST_APPROVED', 'REQUEST_REJECTED', 'PAID', 'NOT_RECEIVED', 'RESENT',
                     'RECEIVED', 'WITHDRAWN', 'RECORDED', 'APPROVED', 'REJECTED', 'REPAYMENT_REQUESTED', 'REPAYMENT_SENT',
                     'REPAYMENT_RESENT', 'REPAYMENT_MISSING', 'REPAYMENT_RECEIVED')));

-- supplier_return -----------------------------------------------------------------------------------------------------

CREATE TABLE supplier_return (
    id bigserial NOT NULL,
    order_id bigint NOT NULL,
    stock_id bigint,
    units numeric(14,3) NOT NULL,
    reason character varying(500) NOT NULL,
    -- Serial numbers, barcodes, what is wrong with each piece
    piece_details character varying(1000),
    shipping_mode character varying(20) NOT NULL,
    status character varying(30) NOT NULL,
    -- units x unit price, with its GST under the quotation
    pieces_value numeric(14,2) NOT NULL,
    requested_by bigint NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    decision_reason character varying(500),
    supplier_responded_at timestamp with time zone,
    supplier_response_reason character varying(500),
    courier character varying(100),
    tracking_number character varying(100),
    dispatched_on date,
    shipping_charge numeric(14,2),
    dispatch_note character varying(500),
    dispatched_by bigint,
    dispatched_at timestamp with time zone,
    received_at timestamp with time zone,
    repayment_id bigint,
    settled_at timestamp with time zone,
    cancel_reason character varying(500),
    cancelled_at timestamp with time zone,
    CONSTRAINT supplier_return_pkey PRIMARY KEY (id),
    CONSTRAINT supplier_return_units_check CHECK ((units > (0)::numeric)),
    CONSTRAINT supplier_return_charge_check CHECK ((shipping_charge IS NULL OR shipping_charge >= (0)::numeric)),
    CONSTRAINT supplier_return_mode_check CHECK ((shipping_mode IN ('TINQA_SHIPS', 'SUPPLIER_PICKUP'))),
    CONSTRAINT supplier_return_status_check CHECK ((status IN ('PENDING_APPROVAL', 'REJECTED', 'AWAITING_SUPPLIER', 'DECLINED',
        'ACCEPTED', 'IN_TRANSIT', 'RECEIVED_BY_SUPPLIER', 'SETTLED', 'CANCELLED'))),
    CONSTRAINT supplier_return_order_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT supplier_return_stock_fkey FOREIGN KEY (stock_id) REFERENCES stock(id),
    CONSTRAINT supplier_return_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT supplier_return_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT supplier_return_dispatched_by_fkey FOREIGN KEY (dispatched_by) REFERENCES users(id),
    CONSTRAINT supplier_return_repayment_fkey FOREIGN KEY (repayment_id) REFERENCES supplier_payment(id)
);

ALTER TABLE supplier_payment ADD CONSTRAINT supplier_payment_return_fkey FOREIGN KEY (return_id) REFERENCES supplier_return(id);

-- An order's returns, newest first (GET /v1/orders/{id}/returns)
CREATE INDEX idx_supplier_return_order ON supplier_return USING btree (order_id, requested_at, id);
-- The Super Admin's queue, oldest first (GET /v1/orders/returns/waiting)
CREATE INDEX idx_supplier_return_waiting ON supplier_return USING btree (requested_at, id) WHERE ((status)::text = 'PENDING_APPROVAL');
-- My Orders: returns waiting for the supplier (SupplierReturnRepository.countToAnswer)
CREATE INDEX idx_supplier_return_to_answer ON supplier_return USING btree (order_id)
    WHERE ((status)::text IN ('AWAITING_SUPPLIER', 'IN_TRANSIT'));

-- Append-only: one row per step of a return, written in the same transaction as the step
CREATE TABLE supplier_return_event (
    id bigserial NOT NULL,
    return_id bigint NOT NULL,
    order_id bigint NOT NULL,
    type character varying(20) NOT NULL,
    actor_id bigint,
    occurred_at timestamp with time zone NOT NULL,
    note character varying(1000),
    CONSTRAINT supplier_return_event_pkey PRIMARY KEY (id),
    CONSTRAINT supplier_return_event_type_check CHECK ((type IN ('REQUESTED', 'APPROVED', 'REJECTED', 'ACCEPTED', 'DECLINED',
        'RESENT', 'DISPATCHED', 'RECEIVED', 'SETTLED', 'CANCELLED'))),
    CONSTRAINT supplier_return_event_return_fkey FOREIGN KEY (return_id) REFERENCES supplier_return(id),
    CONSTRAINT supplier_return_event_order_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT supplier_return_event_actor_fkey FOREIGN KEY (actor_id) REFERENCES users(id)
);

-- An order's returns and its timeline
CREATE INDEX idx_supplier_return_event_order ON supplier_return_event USING btree (order_id, occurred_at, id);
-- The returns of a queue
CREATE INDEX idx_supplier_return_event_return ON supplier_return_event USING btree (return_id, occurred_at, id);

-- approval_queue_counts: adds SUPPLIER_RETURNS and SUPPLIER_REPAYMENTS; every other branch is unchanged from V16 -------

CREATE OR REPLACE FUNCTION approval_queue_counts(p_queues text[]) RETURNS TABLE(queue text, waiting bigint)
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$
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
-- Quotations a supplier raised, minus those with a supplier change waiting (decided in the Suppliers tab first)
SELECT 'QUOTATIONS', count(*) FROM orders o
WHERE 'QUOTATIONS' = ANY (p_queues) AND o.order_status = 'QUOTATION'
  AND EXISTS (SELECT 1 FROM users u WHERE u.id = o.created_by AND u.role = 'SUPPLIER')
  AND NOT EXISTS (SELECT 1 FROM order_change_request c WHERE c.order_id = o.id AND c.status = 'PENDING')
UNION ALL
-- GET /v1/orders?status=PENDING
SELECT 'ORDERS', count(*) FROM orders
WHERE 'ORDERS' = ANY (p_queues) AND order_status = 'PENDING'
UNION ALL
-- GET /v1/orders?status=RETURN_REQUESTED
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
-- GET /v1/admin/supplier-logins?status=APPROVAL_PENDING
SELECT 'SUPPLIER_LOGINS', count(*) FROM supplier_login
WHERE 'SUPPLIER_LOGINS' = ANY (p_queues) AND status = 'APPROVAL_PENDING'
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
-- GET /v1/orders/payments?status=PENDING_APPROVAL (SupplierPaymentService.waiting): payment requests, refund reversals
SELECT 'SUPPLIER_PAYMENTS', count(*) FROM supplier_payment
WHERE 'SUPPLIER_PAYMENTS' = ANY (p_queues) AND status = 'PENDING_APPROVAL'
UNION ALL
-- GET /v1/orders/payments/to-make (SupplierPaymentService.toMake): approved requests to pay, and payments to send again
SELECT 'SUPPLIER_PAYMENTS_TO_MAKE', count(*) FROM supplier_payment
WHERE 'SUPPLIER_PAYMENTS_TO_MAKE' = ANY (p_queues) AND status IN ('TO_PAY', 'NOT_RECEIVED')
UNION ALL
-- GET /v1/orders/payments/repayments-to-check (SupplierPaymentService.repaymentsToCheck): money the supplier says they paid back
SELECT 'SUPPLIER_REPAYMENTS', count(*) FROM supplier_payment
WHERE 'SUPPLIER_REPAYMENTS' = ANY (p_queues) AND status = 'REPAYMENT_SENT'
UNION ALL
-- GET /v1/orders/returns/waiting (SupplierReturnService.waiting): returns of failed pieces for the Super Admin
SELECT 'SUPPLIER_RETURNS', count(*) FROM supplier_return
WHERE 'SUPPLIER_RETURNS' = ANY (p_queues) AND status = 'PENDING_APPROVAL'
$$;
