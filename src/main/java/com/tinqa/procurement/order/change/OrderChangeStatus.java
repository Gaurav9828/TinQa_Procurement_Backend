package com.tinqa.procurement.order.change;

public enum OrderChangeStatus {
    /** Waiting for an approver; the quotation is frozen meanwhile */
    PENDING,
    APPROVED,
    REJECTED,
    /** Taken back by the dealer before anyone decided */
    WITHDRAWN
}
