package com.tinqa.procurement.stock.dto;

import com.tinqa.procurement.common.validation.*;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class StockDTOs {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateFromOrderRequest {
        @NotBlank(message = "Order number is required")
        @Size(max = 50, message = "Order number cannot exceed 50 characters")
        @Pattern(regexp = ValidationPatterns.CODE, message = "Order number " + ValidationPatterns.CODE_MESSAGE)
        private String orderNumber;

        @Size(max = 100, message = "Batch number cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CODE, message = "Batch number " + ValidationPatterns.CODE_MESSAGE)
        private String batchNumber;

        @NotNull(message = "Units passed test is required")
        @PositiveOrZero(message = "Units passed test cannot be negative")
        @Digits(integer = 11, fraction = 3, message = "Units passed test can have at most 11 digits and 3 decimal places")
        private BigDecimal unitsPassedTest;

        @NotNull(message = "Defected units is required")
        @PositiveOrZero(message = "Defected units cannot be negative")
        @Digits(integer = 11, fraction = 3, message = "Defected units can have at most 11 digits and 3 decimal places")
        private BigDecimal defectedUnits;

        @NotNull(message = "Testing status (hasTested) is required")
        private Boolean hasTested;

        @NotNull(message = "Date of arrival is required")
        @PastOrPresent(message = "Date of arrival cannot be in the future")
        private LocalDate dateOfArrival;

        @SafeJsonMap
        private Map<String, Object> additionalInfo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuantityAdjustmentRequest {
        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        @Digits(integer = 11, fraction = 3, message = "Quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal quantity;

        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalDecisionRequest {
        @NotNull(message = "Approval status is required")
        private ApprovalStatus decision;
        @Size(max = 500, message = "Rejection reason cannot exceed 500 characters")
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
        @Size(max = 100, message = "Batch number cannot exceed 100 characters")
        @Pattern(regexp = ValidationPatterns.CODE, message = "Batch number " + ValidationPatterns.CODE_MESSAGE)
        private String batchNumber;

        @NotNull(message = "Dealer ID is required")
        @Positive(message = "Dealer ID must be a valid ID")
        private Long dealerId;

        @NotNull(message = "Item ID is required")
        @Positive(message = "Item ID must be a valid ID")
        private Long itemId;

        @NotNull(message = "Units passed test is required")
        @PositiveOrZero(message = "Units passed test cannot be negative")
        @Digits(integer = 11, fraction = 3, message = "Units passed test can have at most 11 digits and 3 decimal places")
        private BigDecimal unitsPassedTest;

        @NotNull(message = "Defected units is required")
        @PositiveOrZero(message = "Defected units cannot be negative")
        @Digits(integer = 11, fraction = 3, message = "Defected units can have at most 11 digits and 3 decimal places")
        private BigDecimal defectedUnits;

        @NotNull(message = "Testing status (hasTested) is required")
        private Boolean hasTested;

        @NotNull(message = "Date of arrival is required")
        @PastOrPresent(message = "Date of arrival cannot be in the future")
        private LocalDate dateOfArrival;

        @SafeJsonMap
        private Map<String, Object> additionalInfo;

        private Boolean isActive;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemAvailabilityResponse {
        private Long itemId;
        private String itemName;
        private String itemSku;
        private String unitOfMeasure;
        private BigDecimal totalAvailableUnits;
        private List<AvailableStockEntry> stocks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AvailableStockEntry {
        private Long stockId;
        private String stockIdentityNumber;
        private String batchNumber;
        private String orderNumber;
        private BigDecimal availableUnits;
        private LocalDate dateOfArrival;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemAvailabilitySummary {
        private Long itemId;
        private String itemName;
        private String itemSku;
        private String unitOfMeasure;
        private BigDecimal totalAvailableUnits;
    }
}
