package com.tinqa.procurement.order.dto;

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
        private Long dealerId;

        @NotNull(message = "Item ID is required")
        private Long itemId;

        @NotNull(message = "Order quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        private BigDecimal orderQuantity;

        @NotBlank(message = "Unit type is required")
        private String unitType;

        @NotNull(message = "Unit price is required")
        @PositiveOrZero(message = "Unit price must be positive")
        private BigDecimal unitPrice;

        @NotNull(message = "Shipment price is required")
        @PositiveOrZero(message = "Shipment price must be positive")
        private BigDecimal shipmentPrice;

        private Map<String, Object> taxBreakup;
        private LocalDate expectedDelivery;

        @NotNull(message = "Order date is required")
        private LocalDate orderDate;

        private Map<String, Object> additionalInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private Long dealerId;
        private Long itemId;

        @Positive(message = "Quantity must be greater than zero")
        private BigDecimal orderQuantity;

        private String unitType;

        @PositiveOrZero(message = "Unit price must be positive")
        private BigDecimal unitPrice;

        @PositiveOrZero(message = "Shipment price must be positive")
        private BigDecimal shipmentPrice;

        private Map<String, Object> taxBreakup;
        private LocalDate expectedDelivery;
        private LocalDate orderDate;
        private Map<String, Object> additionalInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStatusRequest {
        @NotNull(message = "Order status is required")
        private OrderStatus status;
        private LocalDate actualDelivery;
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