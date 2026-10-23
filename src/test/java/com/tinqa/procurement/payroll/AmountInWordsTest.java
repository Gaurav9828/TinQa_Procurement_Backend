package com.tinqa.procurement.payroll;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @Test
    void writesRupeesInTheIndianSystem() {
        assertThat(AmountInWords.rupees(new BigDecimal("53000"))).isEqualTo("Rupees Fifty Three Thousand Only");
        assertThat(AmountInWords.rupees(new BigDecimal("125450.50")))
                .isEqualTo("Rupees One Lakh Twenty Five Thousand Four Hundred Fifty and Fifty Paise Only");
        assertThat(AmountInWords.rupees(new BigDecimal("10000000"))).isEqualTo("Rupees One Crore Only");
        assertThat(AmountInWords.rupees(new BigDecimal("0.05"))).isEqualTo("Rupees Zero and Five Paise Only");
        assertThat(AmountInWords.rupees(new BigDecimal("219"))).isEqualTo("Rupees Two Hundred Nineteen Only");
    }
}
