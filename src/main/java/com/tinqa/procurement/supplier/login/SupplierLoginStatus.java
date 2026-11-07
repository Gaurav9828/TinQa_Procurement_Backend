package com.tinqa.procurement.supplier.login;

/**
 * Where a supplier's login stands. Only ACTIVE signs in, and only while the supplier itself is active.
 */
public enum SupplierLoginStatus {
    /** Created by a Supplier Manager, waiting for someone else to approve it */
    APPROVAL_PENDING,
    ACTIVE,
    /** Not approved; a Supplier Manager may submit it again */
    REJECTED,
    /** Switched off by a Supplier Manager; can be switched on again without a new approval */
    DISABLED
}
