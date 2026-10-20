package com.tinqa.procurement.order.tax;

import com.tinqa.procurement.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationTaxTest {

    private static final BigDecimal TAXABLE = new BigDecimal("1050.00");

    private static Map<String, Object> sent(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void noBreakupIsStoredEmpty() {
        assertThat(QuotationTax.normalise(null, TAXABLE)).isEmpty();
        assertThat(QuotationTax.normalise(sent("supplyType", "", "gstRate", null), TAXABLE)).isEmpty();
        assertThat(QuotationTax.grandTotal(Map.of(), TAXABLE)).isEqualByComparingTo("1050.00");
    }

    @Test
    void sameStateSplitsTheRateIntoEqualCgstAndSgst() {
        Map<String, Object> stored = QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 18, "hsnSac", "7408"), TAXABLE);
        assertThat((BigDecimal) stored.get("taxableValue")).isEqualByComparingTo("1050.00");
        assertThat((BigDecimal) stored.get("cgst")).isEqualByComparingTo("94.50");
        assertThat((BigDecimal) stored.get("sgst")).isEqualByComparingTo("94.50");
        assertThat((BigDecimal) stored.get("igst")).isEqualByComparingTo("0");
        assertThat((BigDecimal) stored.get("totalTax")).isEqualByComparingTo("189.00");
        assertThat((BigDecimal) stored.get("grandTotal")).isEqualByComparingTo("1239.00");
        assertThat(stored.get("hsnSac")).isEqualTo("7408");
    }

    @Test
    void otherStateIsIgstWithCess() {
        Map<String, Object> stored = QuotationTax.normalise(sent("supplyType", "INTER_STATE", "gstRate", "5", "cess", "10"), TAXABLE);
        assertThat((BigDecimal) stored.get("igst")).isEqualByComparingTo("52.50");
        assertThat((BigDecimal) stored.get("cgst")).isEqualByComparingTo("0");
        assertThat((BigDecimal) stored.get("grandTotal")).isEqualByComparingTo("1112.50");
    }

    @Test
    void typedAmountsMayDifferByRoundingOnly() {
        Map<String, Object> rounded = QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 18, "cgst", "95", "sgst", "95"), TAXABLE);
        assertThat((BigDecimal) rounded.get("cgst")).isEqualByComparingTo("95.00");

        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 18, "cgst", "120"), TAXABLE))
                .isInstanceOf(ApiException.class).hasMessageContaining("94.50");
        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 18, "cgst", "94.50", "sgst", "95"), TAXABLE))
                .hasMessageContaining("SGST must equal CGST");
    }

    @Test
    void lawShapesTheBreakup() {
        assertThatThrownBy(() -> QuotationTax.normalise(sent("gstRate", 18), TAXABLE)).hasMessageContaining("CGST + SGST");
        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTRA_STATE"), TAXABLE)).hasMessageContaining("rate");
        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 12), TAXABLE))
                .hasMessageContaining("rates in force");
        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 18, "igst", "189"), TAXABLE))
                .hasMessageContaining("IGST doesn't apply");
        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTER_STATE", "gstRate", 18, "cgst", "94.5"), TAXABLE))
                .hasMessageContaining("CGST doesn't apply");
        assertThatThrownBy(() -> QuotationTax.normalise(sent("supplyType", "INTER_STATE", "gstRate", 18, "hsnSac", "12"), TAXABLE))
                .hasMessageContaining("HSN/SAC");
    }

    @Test
    void followsANewTaxableValueAtItsRate() {
        Map<String, Object> stored = QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", 18, "cess", "5"), TAXABLE);
        Map<String, Object> doubled = QuotationTax.recalculate(stored, new BigDecimal("2100.00"));
        assertThat((BigDecimal) doubled.get("cgst")).isEqualByComparingTo("189.00");
        assertThat((BigDecimal) doubled.get("cess")).isEqualByComparingTo("5.00");
        assertThat((BigDecimal) doubled.get("grandTotal")).isEqualByComparingTo("2483.00");
        assertThat(QuotationTax.recalculate(stored, TAXABLE)).isSameAs(stored);
        assertThat(QuotationTax.sameTerms(stored, QuotationTax.normalise(sent("supplyType", "INTRA_STATE", "gstRate", "18.0", "cess", 5), TAXABLE))).isTrue();
        assertThat(QuotationTax.sameTerms(stored, Map.of())).isFalse();
    }
}
