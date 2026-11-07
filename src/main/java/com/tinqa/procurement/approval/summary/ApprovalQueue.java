package com.tinqa.procurement.approval.summary;

import com.tinqa.procurement.security.access.Permission;

import java.util.List;

/**
 * The Approvals Hub queues this service holds. Each is counted only for callers holding every one of its permissions:
 * the decision's permission and the one its list endpoint needs, so a badge never shows what the list would refuse.
 * The names are the SQL function approval_queue_counts' queue names (V14__approval_queue_counts.sql) and the keys of GET /v1/approvals/summary.
 */
public enum ApprovalQueue {
    DOCUMENTS(Permission.DOCUMENT_APPROVE, Permission.DOCUMENT_VIEW),
    PROFILE_CHANGES(Permission.EMPLOYEE_PROFILE_CHANGE_APPROVE),
    STOCK(Permission.STOCK_APPROVE, Permission.STOCK_VIEW),
    QUOTATIONS(Permission.PROCUREMENT_ORDER_UPDATE_STATUS, Permission.PROCUREMENT_ORDER_VIEW),
    ORDERS(Permission.PROCUREMENT_ORDER_APPROVE, Permission.PROCUREMENT_ORDER_VIEW),
    RETURNS(Permission.PROCUREMENT_ORDER_APPROVE, Permission.PROCUREMENT_ORDER_VIEW),
    PRODUCTS(Permission.PRODUCT_APPROVAL_DECIDE),
    EMPLOYEES(Permission.EMPLOYEE_APPROVE),
    SUPPLIER_LOGINS(Permission.SUPPLIER_LOGIN_APPROVE),
    QUOTATION_CHANGES(Permission.SUPPLIER_QUOTATION_CHANGE_APPROVE),
    BARCODES(Permission.BARCODE_APPROVE),
    CLIENT_DEMOS(Permission.DEMO_APPROVE),
    // Payment requests to suppliers, and reversals of refunds, waiting for a Super Admin (never the requester)
    SUPPLIER_PAYMENTS(Permission.SUPPLIER_PAYMENT_APPROVE, Permission.PROCUREMENT_ORDER_VIEW),
    // Approved payment requests for a Finance Manager to pay, and payments the supplier says did not arrive
    SUPPLIER_PAYMENTS_TO_MAKE(Permission.SUPPLIER_PAYMENT_RECORD, Permission.PROCUREMENT_ORDER_VIEW),
    // Money a supplier says they paid back, for a Finance Manager to check (V17)
    SUPPLIER_REPAYMENTS(Permission.SUPPLIER_PAYMENT_RECORD, Permission.PROCUREMENT_ORDER_VIEW),
    // Returns of failed pieces waiting for a Super Admin (never the raiser) (V17)
    SUPPLIER_RETURNS(Permission.SUPPLIER_RETURN_APPROVE, Permission.PROCUREMENT_ORDER_VIEW);

    private final List<Permission> required;

    ApprovalQueue(Permission... required) {
        this.required = List.of(required);
    }

    public List<Permission> required() {
        return required;
    }
}
