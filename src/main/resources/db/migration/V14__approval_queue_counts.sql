-- Read models built from several areas (engineering rule 11). Created last: SQL functions check their tables exist.

CREATE FUNCTION approval_queue_counts(p_queues text[]) RETURNS TABLE(queue text, waiting bigint)
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
-- GET /v1/orders/payments?status=PENDING_APPROVAL (SupplierPaymentService.waiting)
SELECT 'SUPPLIER_PAYMENTS', count(*) FROM supplier_payment
WHERE 'SUPPLIER_PAYMENTS' = ANY (p_queues) AND status = 'PENDING_APPROVAL'
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;
