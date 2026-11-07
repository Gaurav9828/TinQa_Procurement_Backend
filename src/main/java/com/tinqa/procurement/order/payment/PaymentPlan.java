package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.exception.ApiErrors;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A quotation's payment plan: the advance and midterm percentages are chosen, the final part is whatever is left
 * (30 % + 20 % leaves 50 % for after testing). At least half is always paid after delivery and testing, so advance and
 * midterm together are at most 50 %. Orders made before plans existed have none (both null).
 */
public final class PaymentPlan {

    public static final BigDecimal HUNDRED = new BigDecimal("100");
    /** The least share of the order paid after the units are delivered and tested (the final part). */
    public static final BigDecimal MIN_FINAL_PERCENT = new BigDecimal("50");

    private PaymentPlan() {
    }

    /** Both percentages given, neither negative, and together leaving at least {@link #MIN_FINAL_PERCENT} for the end. */
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
        if (HUNDRED.subtract(both).compareTo(MIN_FINAL_PERCENT) < 0) {
            // Named on the midterm when there is one (it was typed last), else on the advance
            throw ApiErrors.fieldError(midtermPercent.signum() > 0 ? "midtermPercent" : "advancePercent",
                    "At least 50% is paid after delivery and testing, so advance and midterm together can be at most 50% (they are "
                            + both.stripTrailingZeros().toPlainString() + "%).");
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
