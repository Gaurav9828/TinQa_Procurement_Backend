-- V24: "dealer" becomes "supplier" everywhere. In this app a dealer was always the company we buy from, so the
-- schema, the stored codes and the Approvals Hub function now say supplier. Earlier migrations keep their wording
-- (applied migrations never change).
--
-- 1. Every table, column, constraint, index and sequence whose name contains "dealer" is renamed (dealers ->
--    suppliers, dealer_id -> supplier_id, dealer_login -> supplier_login, ...). Foreign keys and defaults follow by OID.
-- 2. Stored codes: every text value that is a whole code (capitals, digits, underscores only) and contains DEALER is
--    rewritten: role DEALER, designation DEALER_MANAGER, order status DEALER_LEVEL_PENDING, permission groups
--    DEALER_*, meeting type BUSINESS_TO_DEALER, document types DEALER / DEALER_DOCUMENT, ... Free text (names, emails,
--    notes, notification messages) is left alone, so a company called "ABC Dealers" keeps its name.
--    CHECK constraints listing such codes are dropped first and re-created with the new codes.
-- 3. JSONB: keys containing dealer (dealerNote, dealerId, ...) and whole-code string values are renamed the same way.
-- 4. approval_queue_counts reads the renamed tables and returns SUPPLIER_LOGINS / SUPPLIER_PAYMENTS.
--
-- Sessions signed in as DEALER before this release must sign in again (their token carries the old role).

DO
$$
DECLARE
    r       RECORD;
    checks  TEXT[] := '{}';
    defs    TEXT[] := '{}';
    tbls    TEXT[] := '{}';
    col       TEXT;
    hit       TEXT;
    setter    TEXT;
    found_any BOOLEAN;
    i       INT;
BEGIN
    -- 1. Names --------------------------------------------------------------------------------------------------
    FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v', 'm') AND c.relname LIKE '%dealer%'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME TO %I', r.relname, replace(r.relname, 'dealer', 'supplier'));
    END LOOP;

    FOR r IN SELECT c.relname, a.attname FROM pg_attribute a
             JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped
               AND a.attname LIKE '%dealer%'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME COLUMN %I TO %I', r.relname, r.attname,
                       replace(r.attname, 'dealer', 'supplier'));
    END LOOP;

    -- Renaming a constraint also renames the index behind it (primary key, unique)
    FOR r IN SELECT c.relname, k.conname FROM pg_constraint k
             JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND k.conname LIKE '%dealer%'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', r.relname, r.conname,
                       replace(r.conname, 'dealer', 'supplier'));
    END LOOP;

    FOR r IN SELECT c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND c.relkind IN ('i', 'S') AND c.relname LIKE '%dealer%'
    LOOP
        EXECUTE format('ALTER %s %I RENAME TO %I', CASE r.relkind WHEN 'i' THEN 'INDEX' ELSE 'SEQUENCE' END,
                       r.relname, replace(r.relname, 'dealer', 'supplier'));
    END LOOP;

    -- 2. Stored codes -------------------------------------------------------------------------------------------
    FOR r IN SELECT c.relname, k.conname, pg_get_constraintdef(k.oid) AS def FROM pg_constraint k
             JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND k.contype = 'c' AND pg_get_constraintdef(k.oid) LIKE '%DEALER%'
    LOOP
        tbls := tbls || r.relname::TEXT;
        checks := checks || r.conname::TEXT;
        defs := defs || replace(r.def, 'DEALER', 'SUPPLIER');
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.relname, r.conname);
    END LOOP;

    FOR r IN SELECT c.relname, a.attname, t.typname FROM pg_attribute a
             JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
             JOIN pg_type t ON t.oid = a.atttypid
             WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped
               AND t.typname IN ('varchar', 'text', 'bpchar', 'jsonb') AND c.relname <> 'flyway_schema_history'
    LOOP
        col := quote_ident(r.attname);
        IF r.typname = 'jsonb' THEN
            hit := col || '::text ~ ''"[A-Za-z0-9_]*[dD]ealer[A-Za-z0-9_]*"\s*:|"[A-Z0-9_]*DEALER[A-Z0-9_]*"''';
            setter := 'regexp_replace(regexp_replace(regexp_replace(' || col || '::text, '
                || '''"([A-Za-z0-9_]*)dealer([A-Za-z0-9_]*)"(\s*):'', ''"\1supplier\2"\3:'', ''g''), '
                || '''"([A-Za-z0-9_]*)Dealer([A-Za-z0-9_]*)"(\s*):'', ''"\1Supplier\2"\3:'', ''g''), '
                || '''"([A-Z0-9_]*)DEALER([A-Z0-9_]*)"'', ''"\1SUPPLIER\2"'', ''g'')::jsonb';
        ELSE
            hit := col || ' ~ ''^[A-Z0-9_]*DEALER[A-Z0-9_]*\Z''';
            setter := 'replace(' || col || ', ''DEALER'', ''SUPPLIER'')';
        END IF;
        EXECUTE 'SELECT EXISTS (SELECT 1 FROM ' || quote_ident(r.relname) || ' WHERE ' || hit || ')' INTO found_any;
        IF found_any THEN
            -- Append-only tables (ledgers, registers) refuse UPDATE through their own triggers. Renaming a code is not
            -- a business change, so those triggers are paused for this one statement only.
            EXECUTE format('ALTER TABLE %I DISABLE TRIGGER USER', r.relname);
            EXECUTE 'UPDATE ' || quote_ident(r.relname) || ' SET ' || col || ' = ' || setter || ' WHERE ' || hit;
            EXECUTE format('ALTER TABLE %I ENABLE TRIGGER USER', r.relname);
        END IF;
    END LOOP;

    FOR i IN 1 .. coalesce(array_length(checks, 1), 0)
    LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s', tbls[i], replace(checks[i], 'dealer', 'supplier'),
                       defs[i]);
    END LOOP;
END
$$;

-- 4. Approvals Hub counts (same queues as V23, renamed tables and queue keys) ----------------------------------------
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
