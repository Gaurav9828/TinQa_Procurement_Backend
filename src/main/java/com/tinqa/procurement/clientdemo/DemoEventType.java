package com.tinqa.procurement.clientdemo;

/** An entry of a demo's timeline. */
public enum DemoEventType {
    CREATED,
    UPDATED,
    QUOTATION_SUBMITTED,
    QUOTATION_APPROVED,
    QUOTATION_REJECTED,
    WITHDRAWN,
    EXPENSE_ADDED,
    EXPENSE_UPDATED,
    EXPENSE_REMOVED,
    INVOICE_ATTACHED,
    COMPLETION_SUBMITTED,
    COMPLETION_APPROVED,
    COMPLETION_REJECTED,
    REOPENED,
    CANCELLED,
    // Product units taken to the demo, by barcode
    UNITS_HANDED_OUT,
    UNITS_HANDED_BACK,
    UNIT_WRITTEN_OFF
}
