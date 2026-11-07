package com.tinqa.procurement.order.payment;

/** PAYMENT: TinQa paid the dealer. REFUND: the dealer paid TinQa back. REVERSAL: corrects one approved entry. */
public enum DealerPaymentKind {
    PAYMENT,
    REFUND,
    REVERSAL
}
