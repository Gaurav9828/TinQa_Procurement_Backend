package com.tinqa.procurement.order.supplierreturn;

/**
 * A return of pieces that failed testing. Raised by a Supplier Manager (PENDING_APPROVAL), approved by a Super Admin
 * (AWAITING_SUPPLIER) or REJECTED; the supplier accepts it (ACCEPTED) or declines it with a reason (DECLINED, and the
 * Supplier Manager may send it again). The pieces are dispatched or collected (IN_TRANSIT), the supplier confirms they
 * arrived (RECEIVED_BY_SUPPLIER: what they owe is asked of them), and a Finance Manager confirms their repayment
 * (SETTLED). A Supplier Manager may cancel it before dispatch (CANCELLED).
 */
public enum SupplierReturnStatus {
    PENDING_APPROVAL,
    REJECTED,
    AWAITING_SUPPLIER,
    DECLINED,
    ACCEPTED,
    IN_TRANSIT,
    RECEIVED_BY_SUPPLIER,
    SETTLED,
    CANCELLED;

    /** Holds its units: every status but rejected and cancelled. */
    public boolean holdsUnits() {
        return this != REJECTED && this != CANCELLED;
    }

    /** The supplier sees it: from the Super Admin's approval on (and a cancellation after that). */
    public boolean shownToSupplier() {
        return this != PENDING_APPROVAL && this != REJECTED;
    }
}
