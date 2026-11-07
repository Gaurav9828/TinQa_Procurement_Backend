-- Area: supplier payments (V8__orders.sql, V15__supplier_payment_cash_receipt.sql). A payment to a supplier now starts as a
-- request (PENDING_APPROVAL) that only a Super Admin approves (TO_PAY). The Finance Manager then pays it and gives the
-- reference (AWAITING_SUPPLIER). The supplier confirms receipt (APPROVED), or says it did not arrive (NOT_RECEIVED); the
-- Finance Manager then re-checks it and sends it again, optionally with a proof file. Every step is kept in
-- supplier_payment_event, which also feeds the order's flow history (GET /v1/orders/{id}/timeline).

ALTER TABLE supplier_payment DROP CONSTRAINT supplier_payment_status_check;
ALTER TABLE supplier_payment ADD CONSTRAINT supplier_payment_status_check
    CHECK ((status IN ('PENDING_APPROVAL', 'TO_PAY', 'AWAITING_SUPPLIER', 'NOT_RECEIVED', 'APPROVED', 'REJECTED', 'WITHDRAWN')));

-- A request has no payment date yet: it is given when the payment is made
ALTER TABLE supplier_payment ALTER COLUMN paid_on DROP NOT NULL;

ALTER TABLE supplier_payment
    ADD COLUMN paid_by bigint,
    ADD COLUMN paid_at timestamp with time zone,
    ADD CONSTRAINT supplier_payment_paid_by_fkey FOREIGN KEY (paid_by) REFERENCES users(id);

-- Payments made before requests existed: the Level 2 who confirmed them is the one who released the money
UPDATE supplier_payment SET paid_by = decided_by, paid_at = decided_at
WHERE kind = 'PAYMENT' AND decided_by IS NOT NULL
  AND (status = 'AWAITING_SUPPLIER' OR supplier_responded_at IS NOT NULL);

-- The Finance Manager's "payments to make" list, oldest first (GET /v1/orders/payments/to-make)
CREATE INDEX idx_supplier_payment_to_make ON supplier_payment USING btree (recorded_at, id)
    WHERE ((status)::text IN ('TO_PAY', 'NOT_RECEIVED'));

-- supplier_payment_event ----------------------------------------------------------------------------------------------

-- Append-only: one row per step of a payment entry, written in the same transaction as the step
CREATE TABLE supplier_payment_event (
    id bigserial NOT NULL,
    payment_id bigint NOT NULL,
    order_id bigint NOT NULL,
    type character varying(30) NOT NULL,
    -- The user who took the step (staff or the supplier's login); null for steps the system took
    actor_id bigint,
    occurred_at timestamp with time zone NOT NULL,
    reference character varying(100),
    paid_on date,
    note character varying(500),
    proof_document_id bigint,
    CONSTRAINT supplier_payment_event_type_check CHECK ((type IN ('REQUESTED', 'CORRECTED', 'REQUEST_APPROVED', 'REQUEST_REJECTED',
        'PAID', 'NOT_RECEIVED', 'RESENT', 'RECEIVED', 'WITHDRAWN', 'RECORDED', 'APPROVED', 'REJECTED'))),
    CONSTRAINT supplier_payment_event_pkey PRIMARY KEY (id),
    CONSTRAINT supplier_payment_event_payment_fkey FOREIGN KEY (payment_id) REFERENCES supplier_payment(id),
    CONSTRAINT supplier_payment_event_order_fkey FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT supplier_payment_event_actor_fkey FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT supplier_payment_event_proof_fkey FOREIGN KEY (proof_document_id) REFERENCES document(id)
);

-- The order's timeline (SupplierPaymentEventRepository.findByOrderIdOrderByAtAscIdAsc)
CREATE INDEX idx_supplier_payment_event_order ON supplier_payment_event USING btree (order_id, occurred_at, id);
-- The events of the entries of a list (the Approvals Hub queues)
CREATE INDEX idx_supplier_payment_event_payment ON supplier_payment_event USING btree (payment_id, occurred_at, id);

-- Backfill what the existing rows tell -----------------------------------------------------------------------------------

-- Requested (payments) or recorded (refunds, reversals)
INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at, reference, paid_on, note)
SELECT id, order_id, CASE WHEN kind = 'PAYMENT' THEN 'REQUESTED' ELSE 'RECORDED' END, recorded_by, recorded_at,
       CASE WHEN kind = 'PAYMENT' THEN NULL ELSE reference END, CASE WHEN kind = 'PAYMENT' THEN NULL ELSE paid_on END, note
FROM supplier_payment;

-- A Level 2's decision on a payment: approved (and, in the old flow, paid at once) or rejected
INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at, note)
SELECT id, order_id, 'REQUEST_APPROVED', decided_by, decided_at, decision_reason
FROM supplier_payment
WHERE kind = 'PAYMENT' AND paid_by IS NOT NULL;

INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at, reference, paid_on)
SELECT id, order_id, 'PAID', paid_by, paid_at, reference, paid_on
FROM supplier_payment
WHERE kind = 'PAYMENT' AND paid_by IS NOT NULL;

INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at, note)
SELECT id, order_id, 'REQUEST_REJECTED', decided_by, decided_at, decision_reason
FROM supplier_payment
WHERE kind = 'PAYMENT' AND status = 'REJECTED' AND supplier_responded_at IS NULL AND decided_at IS NOT NULL;

-- The supplier's answer
INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at, note)
SELECT p.id, p.order_id, CASE WHEN p.status = 'APPROVED' THEN 'RECEIVED' ELSE 'NOT_RECEIVED' END,
       (SELECT sl.user_id FROM orders o JOIN supplier_login sl ON sl.supplier_id = o.supplier_id WHERE o.id = p.order_id LIMIT 1),
       p.supplier_responded_at, p.supplier_response_reason
FROM supplier_payment p
WHERE p.kind = 'PAYMENT' AND p.supplier_responded_at IS NOT NULL;

-- Refunds and reversals: confirmed or rejected
INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at, note)
SELECT id, order_id, status, decided_by, decided_at, decision_reason
FROM supplier_payment
WHERE kind <> 'PAYMENT' AND status IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL;

-- Withdrawn by the recorder
INSERT INTO supplier_payment_event (payment_id, order_id, type, actor_id, occurred_at)
SELECT id, order_id, 'WITHDRAWN', decided_by, decided_at
FROM supplier_payment
WHERE status = 'WITHDRAWN' AND decided_at IS NOT NULL;

-- Payments are approved by a Super Admin only: no custom role may hold the approval group any more
DELETE FROM custom_role_group WHERE permission_group = 'SUPPLIER_PAYMENT_APPROVAL';
ALTER TABLE custom_role_group DROP CONSTRAINT chk_custom_role_group_allowed;
ALTER TABLE custom_role_group ADD CONSTRAINT chk_custom_role_group_allowed
    CHECK (((permission_group)::text <> ALL (ARRAY['SUPPLIER_PORTAL'::text, 'SYSTEM_ADMINISTRATION'::text, 'SELF_SERVICE'::text,
        'SUPPLIER_PAYMENT_APPROVAL'::text])));

-- approval_queue_counts: adds SUPPLIER_PAYMENTS_TO_MAKE; every other branch is unchanged from V14 ------------------------

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
-- GET /v1/orders/payments?status=PENDING_APPROVAL (SupplierPaymentService.waiting): payment requests, refunds, reversals
SELECT 'SUPPLIER_PAYMENTS', count(*) FROM supplier_payment
WHERE 'SUPPLIER_PAYMENTS' = ANY (p_queues) AND status = 'PENDING_APPROVAL'
UNION ALL
-- GET /v1/orders/payments/to-make (SupplierPaymentService.toMake): approved requests to pay, and payments to send again
SELECT 'SUPPLIER_PAYMENTS_TO_MAKE', count(*) FROM supplier_payment
WHERE 'SUPPLIER_PAYMENTS_TO_MAKE' = ANY (p_queues) AND status IN ('TO_PAY', 'NOT_RECEIVED')
$$;
