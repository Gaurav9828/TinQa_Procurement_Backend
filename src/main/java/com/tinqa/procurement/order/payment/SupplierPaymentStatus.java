package com.tinqa.procurement.order.payment;

/**
 * A payment to the supplier is requested (PENDING_APPROVAL) and approved by a Super Admin (TO_PAY) or REJECTED. The
 * Finance Manager then pays it and gives its reference (AWAITING_SUPPLIER). It counts as paid (APPROVED) only once the
 * supplier confirms they received it; when they say it did not arrive it is NOT_RECEIVED, and the Finance Manager
 * re-checks it and sends it again (back to AWAITING_SUPPLIER). Refunds and reversals are recorded (PENDING_APPROVAL)
 * and then confirmed (APPROVED) or REJECTED by someone else. The requester may withdraw an entry while it waits on
 * TinQa (WITHDRAWN).
 *
 * <p>Money back from the supplier (a reversal of a payment they confirmed, a refund due on a claim, a return of failed
 * pieces) is paid by the supplier and checked by a Finance Manager, with no Super Admin step: asked of them
 * (REPAYMENT_DUE), paid with its reference (REPAYMENT_SENT), then confirmed as arrived (APPROVED) or not
 * (REPAYMENT_MISSING, and the supplier sends it again). A reversal of a refund is money going out to the supplier and is
 * decided by a Super Admin (PENDING_APPROVAL).
 */
public enum SupplierPaymentStatus {
    PENDING_APPROVAL,
    TO_PAY,
    AWAITING_SUPPLIER,
    NOT_RECEIVED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    REPAYMENT_DUE,
    REPAYMENT_SENT,
    REPAYMENT_MISSING
}
