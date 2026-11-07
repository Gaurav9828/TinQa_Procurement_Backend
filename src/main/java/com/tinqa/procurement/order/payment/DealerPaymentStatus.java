package com.tinqa.procurement.order.payment;

/** Recorded (PENDING_APPROVAL), then confirmed or rejected by someone else; the recorder may withdraw it first. */
public enum DealerPaymentStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
