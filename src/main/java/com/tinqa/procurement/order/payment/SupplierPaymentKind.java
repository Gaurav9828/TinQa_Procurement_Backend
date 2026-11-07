package com.tinqa.procurement.order.payment;

/** PAYMENT: TinQa paid the supplier. REFUND: the supplier paid TinQa back. REVERSAL: corrects one approved entry. */
public enum SupplierPaymentKind {
    PAYMENT,
    REFUND,
    REVERSAL
}
