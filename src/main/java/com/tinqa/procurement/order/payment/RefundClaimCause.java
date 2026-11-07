package com.tinqa.procurement.order.payment;

/** Why the dealer owes money back: units failed testing, or the order was cancelled or returned after a payment. */
public enum RefundClaimCause {
    FAILED_TESTING,
    ORDER_CANCELLED,
    ORDER_RETURNED
}
