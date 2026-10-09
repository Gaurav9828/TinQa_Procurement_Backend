package com.tinqa.procurement.order.dto;

import com.tinqa.procurement.common.validation.*;
import com.tinqa.procurement.order.enums.OrderStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public class OrderDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotNull(message = "Dealer ID is required")
        @Positive(message = "Dealer ID must be a valid ID")
        private Long dealerId;

        @NotNull(message = "Item ID is required")
        @Positive(message = "Item ID must be a valid ID")
        private Long itemId;

        @NotNull(message = "Order quantity is required")
        @Positive(message = "Order quantity must be greater than zero")
        @Digits(integer = 11, fraction = 3, message = "Order quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal orderQuantity;

        @NotBlank(message = "Unit type is required")
        @Size(max = 20, message = "Unit type cannot exceed 20 characters")
        @Pattern(regexp = ValidationPatterns.UNIT, message = "Unit type " + ValidationPatterns.UNIT_MESSAGE)
        private String unitType;

        @NotNull(message = "Unit price is required")
        @PositiveOrZero(message = "Unit price cannot be negative")
        @MaxAmount(message = "Unit price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal unitPrice;

        @NotNull(message = "Shipment price is required")
        @PositiveOrZero(message = "Shipment price cannot be negative")
        @MaxAmount(message = "Shipment price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal shipmentPrice;

        @SafeJsonMap
        private Map<String, Object> taxBreakup;
        private LocalDate expectedDelivery;

        @NotNull(message = "Order date is required")
        @PastOrPresent(message = "Order date cannot be in the future")
        private LocalDate orderDate;

        @SafeJsonMap
        private Map<String, Object> additionalInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @Positive(message = "Dealer ID must be a valid ID")
        private Long dealerId;
        @Positive(message = "Item ID must be a valid ID")
        private Long itemId;

        @Positive(message = "Order quantity must be greater than zero")
        @Digits(integer = 11, fraction = 3, message = "Order quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal orderQuantity;

        @Size(max = 20, message = "Unit type cannot exceed 20 characters")
        @Pattern(regexp = ValidationPatterns.UNIT, message = "Unit type " + ValidationPatterns.UNIT_MESSAGE)
        private String unitType;

        @PositiveOrZero(message = "Unit price cannot be negative")
        @MaxAmount(message = "Unit price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal unitPrice;

        @PositiveOrZero(message = "Shipment price cannot be negative")
        @MaxAmount(message = "Shipment price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal shipmentPrice;

        @SafeJsonMap
        private Map<String, Object> taxBreakup;
        private LocalDate expectedDelivery;
        @PastOrPresent(message = "Order date cannot be in the future")
        private LocalDate orderDate;
        @SafeJsonMap
        private Map<String, Object> additionalInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStatusRequest {
        @NotNull(message = "Order status is required")
        private OrderStatus status;
        @PastOrPresent(message = "Actual delivery date cannot be in the future")
        private LocalDate actualDelivery;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalDecisionRequest {
        @NotNull(message = "Approval status is required")
        private OrderStatus decision;
        @Size(max = 500, message = "Rejection reason cannot exceed 500 characters")
        private String rejectionReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String orderNumber;
        private Long dealerId;
        private String dealerName;
        private Long itemId;
        private String itemName;
        private BigDecimal orderQuantity;
        private String unitType;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
        private BigDecimal shipmentPrice;
        private Map<String, Object> taxBreakup;
        private OrderStatus orderStatus;
        private LocalDate expectedDelivery;
        private LocalDate actualDelivery;
        private LocalDate orderDate;
        private Map<String, Object> additionalInfo;
        private LocalDateTime createdAt;
        private Long createdBy;
    }
}