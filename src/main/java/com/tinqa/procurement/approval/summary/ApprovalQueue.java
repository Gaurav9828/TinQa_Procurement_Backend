package com.tinqa.procurement.approval.summary;

import com.tinqa.procurement.security.access.Permission;

import java.util.List;

/**
 * The Approvals Hub queues this service holds. Each is counted only for callers holding every one of its permissions:
 * the decision's permission and the one its list endpoint needs, so a badge never shows what the list would refuse.
 * The names are the SQL function approval_queue_counts' queue names (V15) and the keys of GET /v1/approvals/summary.
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
    DEALER_LOGINS(Permission.DEALER_LOGIN_APPROVE),
    QUOTATION_CHANGES(Permission.DEALER_QUOTATION_CHANGE_APPROVE),
    BARCODES(Permission.BARCODE_APPROVE),
    CLIENT_DEMOS(Permission.DEMO_APPROVE);

    private final List<Permission> required;

    ApprovalQueue(Permission... required) {
        this.required = List.of(required);
    }

    public List<Permission> required() {
        return required;
    }
}
