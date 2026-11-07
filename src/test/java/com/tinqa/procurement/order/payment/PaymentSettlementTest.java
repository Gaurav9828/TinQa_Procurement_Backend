package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.tax.QuotationTax;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 10 PCS x 100.00 + 50.00 shipment = 1050.00 before GST, as in SupplierPaymentIntegrationTest. */
class PaymentSettlementTest {

    private static Order order(Map<String, Object> taxBreakup) {
        return Order.builder().unitType("PCS").unitPrice(new BigDecimal("100.00")).orderQuantity(new BigDecimal("10"))
                .shipmentPrice(new BigDecimal("50.00")).taxBreakup(taxBreakup).build();
    }

    private static Map<String, Object> gst18IntraState() {
        Map<String, Object> breakup = new HashMap<>();
        breakup.put(QuotationTax.SUPPLY_TYPE, QuotationTax.INTRA_STATE);
        breakup.put(QuotationTax.GST_RATE, new BigDecimal("18"));
        breakup.put(QuotationTax.TAXABLE_VALUE, new BigDecimal("1050.00"));
        breakup.put(QuotationTax.CGST, new BigDecimal("94.50"));
        breakup.put(QuotationTax.SGST, new BigDecimal("94.50"));
        breakup.put(QuotationTax.CESS, BigDecimal.ZERO);
        breakup.put(QuotationTax.TOTAL_TAX, new BigDecimal("189.00"));
        breakup.put(QuotationTax.GRAND_TOTAL, new BigDecimal("1239.00"));
        return breakup;
    }

    /** The worth after testing exactly as SupplierPaymentService works it out. */
    private static BigDecimal payable(Order order, String passed) {
        BigDecimal units = new BigDecimal(passed);
        BigDecimal value = units.signum() > 0
                ? order.getUnitPrice().multiply(units).setScale(2, java.math.RoundingMode.HALF_UP).add(order.getShipmentPrice())
                : BigDecimal.ZERO.setScale(2);
        return value.add(PurchasePostingRecorder.gstOn(order, value).total());
    }

    private static void assertBalances(SupplierPaymentDTOs.Settlement s) {
        assertThat(s.getOrderValue().subtract(s.getFailedValue()).subtract(s.getShortValue()).subtract(s.getShipmentDropped())
                .subtract(s.getGstReduction())).isEqualByComparingTo(s.getPayable());
        assertThat(s.getReduction()).isEqualByComparingTo(s.getOrderValue().subtract(s.getPayable()));
        assertThat(s.getPassedTaxable().add(s.getPassedGst())).isEqualByComparingTo(s.getPayable());
    }

    @Test
    void failedUnitsAndTheirGstAreTakenOffTheQuotedTotal() {
        Order order = order(gst18IntraState());
        SupplierPaymentDTOs.Settlement s = PaymentSettlement.of(order, new BigDecimal("1239.00"), payable(order, "8"),
                new BigDecimal("8"), new BigDecimal("2"));

        assertThat(s.getQuotedTaxable()).isEqualByComparingTo("1050.00");
        assertThat(s.getQuotedGst()).isEqualByComparingTo("189.00");
        assertThat(s.getFailedValue()).isEqualByComparingTo("200.00");
        assertThat(s.getShortUnits()).isEqualByComparingTo("0");
        assertThat(s.getShortValue()).isEqualByComparingTo("0");
        assertThat(s.getPassedTaxable()).isEqualByComparingTo("850.00");
        // 850.00 x 18 % = 153.00, so 36.00 of GST goes with the failed units
        assertThat(s.getPassedGst()).isEqualByComparingTo("153.00");
        assertThat(s.getGstReduction()).isEqualByComparingTo("36.00");
        assertThat(s.getReduction()).isEqualByComparingTo("236.00");
        assertThat(s.getPayable()).isEqualByComparingTo("1003.00");
        assertThat(s.getGstRate()).isEqualByComparingTo("18");
        assertBalances(s);
    }

    @Test
    void unitsNeverDeliveredAreTakenOffApartFromTheFailedOnes() {
        Order order = order(Map.of());
        SupplierPaymentDTOs.Settlement s = PaymentSettlement.of(order, new BigDecimal("1050.00"), payable(order, "7"),
                new BigDecimal("7"), new BigDecimal("2"));

        assertThat(s.getShortUnits()).isEqualByComparingTo("1");
        assertThat(s.getShortValue()).isEqualByComparingTo("100.00");
        assertThat(s.getFailedValue()).isEqualByComparingTo("200.00");
        assertThat(s.getGstReduction()).isEqualByComparingTo("0");
        assertThat(s.getGstRate()).isNull();
        assertThat(s.getPayable()).isEqualByComparingTo("750.00");
        assertBalances(s);
    }

    @Test
    void theShipmentGoesTooWhenNoUnitPassed() {
        Order order = order(gst18IntraState());
        SupplierPaymentDTOs.Settlement s = PaymentSettlement.of(order, new BigDecimal("1239.00"), payable(order, "0"),
                BigDecimal.ZERO, new BigDecimal("10"));

        assertThat(s.getShipmentDropped()).isEqualByComparingTo("50.00");
        assertThat(s.getFailedValue()).isEqualByComparingTo("1000.00");
        assertThat(s.getGstReduction()).isEqualByComparingTo("189.00");
        assertThat(s.getPayable()).isEqualByComparingTo("0");
        assertThat(s.getReduction()).isEqualByComparingTo("1239.00");
        assertBalances(s);
    }
}
