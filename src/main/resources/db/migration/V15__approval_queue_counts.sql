-- ==========================================
-- V15: approval queue counts in one statement, and indexes that match the queues
-- ==========================================
-- The Approvals Hub showed a badge per queue by downloading every queue's whole list (about ten calls, 40-60 SQL
-- statements per visit). approval_queue_counts answers every badge in one statement; GET /v1/approvals/summary
-- passes only the queues the caller may decide, and the branches for the others are skipped by the planner.
--
-- Each branch must count exactly what that queue's list endpoint returns. When a queue's rule changes in Java,
-- change it here too (CREATE OR REPLACE in a new migration); ApprovalSummaryIntegrationTest compares the two.

-- Queue lists filter on status and sort oldest first: one composite index each replaces the plain status index
CREATE INDEX idx_orders_status_id ON orders (order_status, id);
DROP INDEX IF EXISTS idx_orders_status;

CREATE INDEX idx_employee_status_created ON employee (status, created_at, id);
DROP INDEX IF EXISTS idx_employee_status;

CREATE INDEX idx_profile_change_status_requested ON employee_profile_change_request (status, requested_at DESC);
DROP INDEX IF EXISTS idx_profile_change_status;

CREATE INDEX idx_product_approval_requests_status_requested ON product_approval_requests (status, requested_at, id);
DROP INDEX IF EXISTS idx_product_approval_requests_status;

CREATE INDEX idx_dealer_login_status_created ON dealer_login (status, created_at);
DROP INDEX IF EXISTS idx_dealer_login_status;

CREATE INDEX idx_client_demo_status_submitted ON client_demo (status, submitted_at, id);
DROP INDEX IF EXISTS idx_client_demo_status;

CREATE INDEX idx_document_status_created ON document (status, created_at);
DROP INDEX IF EXISTS idx_document_status;

-- Declared on the Document entity but never created (ddl-auto is validate, so @Index does nothing)
CREATE INDEX IF NOT EXISTS idx_doc_uploader ON document (uploader_type, uploaded_by_user_id);

-- Stock waiting for Admin L2: new entries, and approved entries whose counts changed since approval. Without this
-- the queue scans every approved stock row, a set that only grows.
CREATE INDEX idx_stock_awaiting_approval ON stock (id)
    WHERE approval_status = 'PENDING'
       OR (approval_status = 'APPROVED' AND approved_units_passed IS NOT NULL
           AND (units_passed_test <> approved_units_passed OR defected_units <> approved_defected_units));

-- One row per requested queue: (queue, waiting). Queue names are the keys of GET /v1/approvals/summary.
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
$$;
