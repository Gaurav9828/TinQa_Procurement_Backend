package com.tinqa.procurement.stock.dto;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public class StockDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateFromOrderRequest {
        @NotBlank(message = "Order number reference is required")
        private String orderNumber;

        private String batchNumber;

        @NotNull(message = "Units passed test is required")
        @PositiveOrZero(message = "Units passed test must be non-negative")
        private BigDecimal unitsPassedTest;

        @NotNull(message = "Defected units count is required")
        @PositiveOrZero(message = "Defected units must be non-negative")
        private BigDecimal defectedUnits;

        @NotNull(message = "Testing status (hasTested) is required")
        private Boolean hasTested;

        @NotNull(message = "Date of arrival is required")
        private LocalDate dateOfArrival;

        private Map<String, Object> additionalInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuantityAdjustmentRequest {
        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        private BigDecimal quantity;

        @NotBlank(message = "Reason is required")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalDecisionRequest {
        @NotNull(message = "Approval status is required")
        private ApprovalStatus decision;
        private String rejectionReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String stockIdentityNumber;
        private String batchNumber;
        private String orderNumber;
        private Long dealerId;
        private String dealerName;
        private Long itemId;
        private String itemName;
        private BigDecimal totalOrderQuantity; // Pulled dynamically from Order
        private String unitType;               // Pulled dynamically from Order
        private BigDecimal unitsPassedTest;
        private BigDecimal defectedUnits;
        private BigDecimal availableUnits;
        private BigDecimal unitPrice;          // Pulled dynamically from Order
        private BigDecimal totalPrice;         // Pulled dynamically from Order
        private Boolean hasTested;
        private Map<String, Object> additionalInfo;
        private ApprovalStatus approvalStatus;
        private Long approvedBy;
        private LocalDateTime approvedAt;
        private String rejectionReason;
        private LocalDate dateOfArrival;
        private Boolean isActive;
        private LocalDateTime createdAt;
        private Long createdBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @NotBlank(message = "Batch number is required")
        private String batchNumber;

        @NotNull(message = "Dealer ID is required")
        private Long dealerId;

        @NotNull(message = "Item ID is required")
        private Long itemId;

        @NotNull(message = "Units passed test is required")
        @PositiveOrZero(message = "Units passed test must be non-negative")
        private BigDecimal unitsPassedTest;

        @NotNull(message = "Defected units count is required")
        @PositiveOrZero(message = "Defected units must be non-negative")
        private BigDecimal defectedUnits;

        @NotNull(message = "Testing status (hasTested) is required")
        private Boolean hasTested;

        @NotNull(message = "Date of arrival is required")
        private LocalDate dateOfArrival;

        private Map<String, Object> additionalInfo;

        private Boolean isActive;
    }
}