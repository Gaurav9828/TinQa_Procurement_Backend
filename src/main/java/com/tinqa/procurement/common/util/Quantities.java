package com.tinqa.procurement.common.util;

import java.math.BigDecimal;

/**
 * Formatting of quantities in user-facing messages.
 */
public final class Quantities {

    private Quantities() {
    }

    // 10.500 -> "10.5", 3.000 -> "3"
    public static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
