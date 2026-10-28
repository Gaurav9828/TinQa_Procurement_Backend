package com.tinqa.procurement.clientdemo;

/**
 * Where a demo is in its life: planned (DRAFT) → quotation waiting for approval → APPROVED (scheduled; bills are
 * recorded while it runs) → completion waiting for approval → COMPLETED (booked to the company account). A Super Admin
 * may reopen a completed demo to correct it (REOPENED); its completion is then approved again. Rejections send a demo
 * back one step with the reason.
 */
public enum DemoStatus {
    DRAFT("Draft"),
    QUOTATION_PENDING("Quotation awaiting approval"),
    APPROVED("Approved"),
    COMPLETION_PENDING("Completion awaiting approval"),
    COMPLETED("Completed"),
    REOPENED("Reopened for correction"),
    CANCELLED("Cancelled");

    private final String label;

    DemoStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether bills may be added, changed or removed in this status. */
    public boolean billsOpen() {
        return this == DRAFT || this == APPROVED || this == REOPENED;
    }
}
