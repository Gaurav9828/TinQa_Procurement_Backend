package com.tinqa.procurement.order.change;

import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.payment.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public final class OrderChangeDTOs {

    private OrderChangeDTOs() {
    }

    /**
     * The quotation terms a supplier may change. In a request's {@code changes} only the proposed fields are set;
     * {@code previous} has the same fields as they were. Names are display-only and never applied.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Terms {
        private Long itemId;
        private String itemName;
        private BigDecimal orderQuantity;
        private String unitType;
        private BigDecimal unitPrice;
        private BigDecimal shipmentPrice;
        /** The GST breakup as it would be stored (QuotationTax); {} when it is removed */
        private Map<String, Object> taxBreakup;
        private LocalDate expectedDelivery;
        /** Payment plan: the advance and midterm percentages (the final part is the rest) and the expected method */
        private BigDecimal advancePercent;
        private BigDecimal midtermPercent;
        private PaymentMethod expectedPaymentMethod;
        private String note;
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotNull(message = "Decision is required")
        private Decision decision;

        // Required to reject (checked in the service)
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long orderId;
        private String orderNumber;
        private OrderStatus orderStatus;
        private Long supplierId;
        private String supplierName;
        private Terms changes;
        private Terms previous;
        /** Order total if the change is approved */
        private BigDecimal proposedTotalPrice;
        private BigDecimal currentTotalPrice;
        private String note;
        private OrderChangeStatus status;
        private String requestedByUsername;
        private Instant requestedAt;
        private String decidedByUsername;
        private Instant decidedAt;
        private String decisionReason;
    }
}
