package com.tinqa.procurement.order.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Online, bank transfer and cheque entries name their transaction; cash needs no reference. */
class PaymentReferenceTest {

    @Test
    void cashNeedsNoReference() {
        assertThatCode(() -> SupplierPaymentService.ensureReferenceGiven(PaymentMethod.CASH, null)).doesNotThrowAnyException();
        assertThatCode(() -> SupplierPaymentService.ensureReferenceGiven(PaymentMethod.CASH, "Receipt 7")).doesNotThrowAnyException();
    }

    @Test
    void everyOtherMethodNeedsOne() {
        assertThatThrownBy(() -> SupplierPaymentService.ensureReferenceGiven(PaymentMethod.ONLINE, null)).hasMessageContaining("transaction ID");
        assertThatThrownBy(() -> SupplierPaymentService.ensureReferenceGiven(PaymentMethod.BANK_TRANSFER, "  ")).hasMessageContaining("UTR");
        assertThatThrownBy(() -> SupplierPaymentService.ensureReferenceGiven(PaymentMethod.CHEQUE, "")).hasMessageContaining("cheque number");
        assertThatCode(() -> SupplierPaymentService.ensureReferenceGiven(PaymentMethod.CHEQUE, "004512")).doesNotThrowAnyException();
    }
}
