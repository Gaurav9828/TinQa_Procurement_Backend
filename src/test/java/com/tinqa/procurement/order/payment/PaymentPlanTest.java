package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPlanTest {

    @Test
    void theFinalPartIsWhatIsLeftOfOneHundred() {
        assertThat(PaymentPlan.finalPercent(new BigDecimal("30"), new BigDecimal("30"))).isEqualByComparingTo("40");
        assertThat(PaymentPlan.finalPercent(new BigDecimal("12.5"), new BigDecimal("0"))).isEqualByComparingTo("87.5");
        assertThat(PaymentPlan.finalPercent(null, null)).isNull();
    }

    @Test
    void atLeastHalfIsAlwaysPaidAfterDeliveryAndTesting() {
        assertThatCode(() -> PaymentPlan.validate(new BigDecimal("30"), new BigDecimal("20"))).doesNotThrowAnyException();
        assertThatCode(() -> PaymentPlan.validate(new BigDecimal("50"), BigDecimal.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> PaymentPlan.validate(BigDecimal.ZERO, BigDecimal.ZERO)).doesNotThrowAnyException();
        // 80 % up front leaves only 20 % for after delivery
        assertThatThrownBy(() -> PaymentPlan.validate(new BigDecimal("80"), BigDecimal.ZERO))
                .isInstanceOf(ApiException.class).hasMessageContaining("at most 50%").hasMessageContaining("80%");
        assertThatThrownBy(() -> PaymentPlan.validate(new BigDecimal("30"), new BigDecimal("20.01")))
                .isInstanceOf(ApiException.class).hasMessageContaining("50.01%");
        assertThatThrownBy(() -> PaymentPlan.validate(new BigDecimal("30"), new BigDecimal("30"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PaymentPlan.validate(new BigDecimal("-1"), BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PaymentPlan.validate(null, BigDecimal.ZERO)).isInstanceOf(ApiException.class);
    }

    @Test
    void aShareIsRoundedToPaise() {
        assertThat(PaymentPlan.share(new BigDecimal("1050.00"), new BigDecimal("30"))).isEqualByComparingTo("315.00");
        assertThat(PaymentPlan.share(new BigDecimal("100.00"), new BigDecimal("33.33"))).isEqualByComparingTo("33.33");
        assertThat(PaymentPlan.share(new BigDecimal("100.00"), null)).isEqualByComparingTo("0");
    }
}
