package com.tinqa.procurement.order.change;

import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.payment.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SupplierQuotedTermsTest {

    private static Order order() {
        Item item = new Item();
        item.setId(7L);
        item.setName("USB Cable");
        Order order = Order.builder().item(item).orderQuantity(new BigDecimal("10")).unitType("PCS")
                .unitPrice(new BigDecimal("100.00")).shipmentPrice(new BigDecimal("50.00"))
                .taxBreakup(Map.of("supplyType", "INTRA_STATE", "gstRate", new BigDecimal("18")))
                .expectedDelivery(LocalDate.of(2099, 12, 31))
                .advancePercent(new BigDecimal("30.00")).midtermPercent(new BigDecimal("20.00"))
                .expectedPaymentMethod(PaymentMethod.BANK_TRANSFER).build();
        order.recalculateTotalPrice();
        return order;
    }

    @Test
    void nothingIsComparedWhenStaffPreparedTheQuotation() {
        assertThat(SupplierQuotedTerms.changes(order())).isEmpty();
    }

    @Test
    void unchangedTermsShowNoChangeWhateverTheirScale() {
        Order order = order();
        order.setSupplierQuotedTerms(SupplierQuotedTerms.of(order));
        // 30.00 and 30 are the same percentage
        order.setAdvancePercent(new BigDecimal("30"));
        assertThat(SupplierQuotedTerms.changes(order)).isEmpty();
    }

    @Test
    void listsEveryTermTinqaChangedWithWhatWasQuotedAndWhatItIsNow() {
        Order order = order();
        order.setSupplierQuotedTerms(SupplierQuotedTerms.of(order));
        order.setAdvancePercent(new BigDecimal("20"));
        order.setUnitPrice(new BigDecimal("90.00"));
        order.recalculateTotalPrice();
        order.setTaxBreakup(Map.of("supplyType", "INTER_STATE", "gstRate", new BigDecimal("18")));
        order.setExpectedPaymentMethod(PaymentMethod.CHEQUE);

        List<SupplierQuotedTerms.TermChange> changes = SupplierQuotedTerms.changes(order);

        assertThat(changes).extracting(SupplierQuotedTerms.TermChange::field, SupplierQuotedTerms.TermChange::quoted,
                SupplierQuotedTerms.TermChange::current).containsExactly(
                tuple("UNIT_PRICE", "₹100.00", "₹90.00"),
                tuple("TOTAL_PRICE", "₹1050.00", "₹950.00"),
                tuple("GST", "18% · CGST + SGST", "18% · IGST"),
                tuple("ADVANCE_PERCENT", "30%", "20%"),
                tuple("FINAL_PERCENT", "50%", "60%"),
                tuple("PAYMENT_METHOD", "Bank to bank transfer", "Cheque"));
        assertThat(SupplierQuotedTerms.summary(changes)).contains("Advance payment 30% → 20%");
        assertThat(SupplierQuotedTerms.labels(changes)).contains("advance payment", "payment method");
    }

    @Test
    void aRemovedGstBreakupAndAnotherDeliveryDateAreShown() {
        Order order = order();
        order.setSupplierQuotedTerms(SupplierQuotedTerms.of(order));
        order.setTaxBreakup(Map.of());
        order.setExpectedDelivery(LocalDate.of(2099, 11, 30));

        assertThat(SupplierQuotedTerms.changes(order)).extracting(SupplierQuotedTerms.TermChange::field,
                SupplierQuotedTerms.TermChange::quoted, SupplierQuotedTerms.TermChange::current).containsExactly(
                tuple("GST", "18% · CGST + SGST", "No GST"),
                tuple("EXPECTED_DELIVERY", "31 Dec 2099", "30 Nov 2099"));
    }

    @Test
    void anApprovedSupplierChangeBecomesTheirWordButKeepsEarlierTinqaChangesVisible() {
        Order order = order();
        order.setSupplierQuotedTerms(SupplierQuotedTerms.of(order));
        // TinQa lowers the advance, then the supplier's own price change is approved
        order.setAdvancePercent(new BigDecimal("20"));
        order.setUnitPrice(new BigDecimal("110.00"));
        order.recalculateTotalPrice();
        order.setSupplierQuotedTerms(SupplierQuotedTerms.afterSupplierChange(order,
                OrderChangeDTOs.Terms.builder().unitPrice(new BigDecimal("110.00")).build()));

        assertThat(SupplierQuotedTerms.changes(order)).extracting(SupplierQuotedTerms.TermChange::field)
                .containsExactly("ADVANCE_PERCENT", "FINAL_PERCENT");
    }

    @Test
    void aSupplierChangeToAStaffQuotationMakesEveryTermTheirWord() {
        Order order = order();
        order.setAdvancePercent(new BigDecimal("10"));
        order.setSupplierQuotedTerms(SupplierQuotedTerms.afterSupplierChange(order,
                OrderChangeDTOs.Terms.builder().advancePercent(new BigDecimal("10")).build()));
        Map<String, Object> expected = new LinkedHashMap<>(SupplierQuotedTerms.of(order));

        assertThat(order.getSupplierQuotedTerms()).isEqualTo(expected);
        assertThat(SupplierQuotedTerms.changes(order)).isEmpty();
    }
}
