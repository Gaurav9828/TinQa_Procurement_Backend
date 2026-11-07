package com.tinqa.procurement.order.payment;

/** One step of a supplier payment entry (supplier_payment_event). */
public enum SupplierPaymentEventType {
    // Payments
    REQUESTED,
    CORRECTED,
    REQUEST_APPROVED,
    REQUEST_REJECTED,
    PAID,
    NOT_RECEIVED,
    RESENT,
    RECEIVED,
    WITHDRAWN,
    // Refunds and reversals
    RECORDED,
    APPROVED,
    REJECTED,
    // Money back from the supplier: asked of them, paid back (again), not in the bank, confirmed by a Finance Manager
    REPAYMENT_REQUESTED,
    REPAYMENT_SENT,
    REPAYMENT_RESENT,
    REPAYMENT_MISSING,
    REPAYMENT_RECEIVED;

    /** The steps the supplier sees: from the payment being made on (never TinQa's internal approvals). */
    public boolean shownToSupplier() {
        return this == PAID || this == RESENT || this == RECEIVED || this == NOT_RECEIVED || this == APPROVED || repayment();
    }

    /** A step of money paid back by the supplier (all shown to them). */
    public boolean repayment() {
        return this == REPAYMENT_REQUESTED || this == REPAYMENT_SENT || this == REPAYMENT_RESENT || this == REPAYMENT_MISSING
                || this == REPAYMENT_RECEIVED;
    }
}
