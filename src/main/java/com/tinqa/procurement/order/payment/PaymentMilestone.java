package com.tinqa.procurement.order.payment;

/**
 * The three parts of a quotation's payment plan. The advance can be paid once the order is approved, the midterm once
 * the dealer has shipped it, and the final part only after the delivered units were tested (stock entry approved).
 */
public enum PaymentMilestone {
    ADVANCE,
    MIDTERM,
    FINAL
}
