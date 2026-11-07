package com.tinqa.procurement.order.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Order lifecycle. Which moves are allowed, and by whom, is defined in
 * {@link com.tinqa.procurement.order.workflow.OrderStatusTransitions}.
 */
public enum OrderStatus {
    QUOTATION,
    PENDING,
    SUPPLIER_LEVEL_PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURN_APPROVED,
    RETURN_REJECTED,
    RETURN_PICKED_UP,
    RETURN_RECEIVED,
    REFUND_INITIATED,
    REFUND_FAILED,
    REFUND_COMPLETED;

    // "RETURN_PICKED_UP" -> "Return picked up", for messages shown to admins
    public String label() {
        String words = Arrays.stream(name().split("_"))
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
