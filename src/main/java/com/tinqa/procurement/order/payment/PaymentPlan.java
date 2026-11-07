package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.exception.ApiErrors;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A quotation's payment plan: the advance and midterm percentages are chosen, the final part is whatever is left
 * (30 % + 30 % leaves 40 % for after testing). Orders made before plans existed have none (both null).
 */
public final class PaymentPlan {

    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private PaymentPlan() {
    }

    /** Both percentages given, neither negative, and together at most 100 %. */
    public static void validate(BigDecimal advancePercent, BigDecimal midtermPercent) {
        if (advancePercent == null) {
            throw ApiErrors.fieldError("advancePercent", "Give the advance payment percentage (0 for none).");
        }
        if (midtermPercent == null) {
            throw ApiErrors.fieldError("midtermPercent", "Give the midterm payment percentage (0 for none).");
        }
        if (advancePercent.signum() < 0 || midtermPercent.signum() < 0) {
            throw ApiErrors.fieldError(advancePercent.signum() < 0 ? "advancePercent" : "midtermPercent", "A payment percentage cannot be negative.");
        }
        BigDecimal both = advancePercent.add(midtermPercent);
        if (both.compareTo(HUNDRED) > 0) {
            throw ApiErrors.fieldError("midtermPercent", "Advance and midterm together cannot be more than 100% (they are "
                    + both.stripTrailingZeros().toPlainString() + "%). The final payment is what is left of 100%.");
        }
    }

    /** What is left after the advance and the midterm; null without a plan. */
    public static BigDecimal finalPercent(BigDecimal advancePercent, BigDecimal midtermPercent) {
        if (advancePercent == null || midtermPercent == null) {
            return null;
        }
        return HUNDRED.subtract(advancePercent).subtract(midtermPercent);
    }

    /** {@code percent} % of {@code value}, in rupees and paise. A missing percentage is 0. */
    public static BigDecimal share(BigDecimal value, BigDecimal percent) {
        if (percent == null || value == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return value.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
