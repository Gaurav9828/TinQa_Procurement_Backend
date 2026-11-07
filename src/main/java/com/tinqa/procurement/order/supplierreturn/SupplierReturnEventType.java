package com.tinqa.procurement.order.supplierreturn;

/** One step of a return (supplier_return_event). */
public enum SupplierReturnEventType {
    REQUESTED,
    APPROVED,
    REJECTED,
    ACCEPTED,
    DECLINED,
    RESENT,
    DISPATCHED,
    RECEIVED,
    SETTLED,
    CANCELLED;

    /** The supplier sees every step from the Super Admin's approval on. */
    public boolean shownToSupplier() {
        return this != REQUESTED && this != REJECTED;
    }
}
