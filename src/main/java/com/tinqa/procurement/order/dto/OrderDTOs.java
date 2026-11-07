package com.tinqa.procurement.order.dto;

import com.tinqa.procurement.common.validation.*;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.payment.PaymentMethod;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public class OrderDTOs {

    /** additionalInfo key: why a quotation's unit price differs from the item's MRP (required when it does). */
    public static final String PRICE_CHANGE_REASON = "priceChangeReason";
    /** additionalInfo key: the item's MRP when the quotation was created at another price, for approvers. */
    public static final String QUOTED_MRP = "mrp";
    /** additionalInfo key: the unit price quoted before the latest price change (a staff edit or an approved supplier change). */
    public static final String PREVIOUS_UNIT_PRICE = "previousUnitPrice";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotNull(message = "Supplier ID is required")
        @Positive(message = "Supplier ID must be a valid ID")
        private Long supplierId;

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

        // Payment plan: advance and midterm percentages (the final part, paid after testing, is the rest of 100)
        @NotNull(message = "Advance payment percentage is required (0 for none)")
        @DecimalMin(value = "0", message = "Advance payment percentage cannot be negative")
        @DecimalMax(value = "100", message = "Advance payment percentage cannot be more than 100")
        @Digits(integer = 3, fraction = 2, message = "Advance payment percentage can have at most 2 decimal places")
        private BigDecimal advancePercent;

        // Optional: left out (or 0) for a plan with only an advance and the final payment
        @DecimalMin(value = "0", message = "Midterm payment percentage cannot be negative")
        @DecimalMax(value = "100", message = "Midterm payment percentage cannot be more than 100")
        @Digits(integer = 3, fraction = 2, message = "Midterm payment percentage can have at most 2 decimal places")
        private BigDecimal midtermPercent;

        @NotNull(message = "Expected payment method is required")
        private PaymentMethod expectedPaymentMethod;

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
        @Positive(message = "Supplier ID must be a valid ID")
        private Long supplierId;
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

        @DecimalMin(value = "0", message = "Advance payment percentage cannot be negative")
        @DecimalMax(value = "100", message = "Advance payment percentage cannot be more than 100")
        @Digits(integer = 3, fraction = 2, message = "Advance payment percentage can have at most 2 decimal places")
        private BigDecimal advancePercent;
        @DecimalMin(value = "0", message = "Midterm payment percentage cannot be negative")
        @DecimalMax(value = "100", message = "Midterm payment percentage cannot be more than 100")
        @Digits(integer = 3, fraction = 2, message = "Midterm payment percentage can have at most 2 decimal places")
        private BigDecimal midtermPercent;
        private PaymentMethod expectedPaymentMethod;

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

        // Required for DELIVERED and ignored otherwise; checked in the service because the rule depends on the status
        private LocalDate actualDelivery;

        // Required for CANCELLED, RETURN_REQUESTED, RETURN_REJECTED and REFUND_FAILED (checked in the service)
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;

        // DELIVERED only: a Level 2 approver accepting a supplier's invoice that didn't match the quotation (checked in the service)
        @Size(max = 400, message = "Reason for accepting the invoice cannot exceed 400 characters")
        private String invoiceOverrideReason;
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
        private Long supplierId;
        private String supplierName;
        private Long itemId;
        private String itemName;
        private BigDecimal orderQuantity;
        private String unitType;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
        private BigDecimal shipmentPrice;
        private Map<String, Object> taxBreakup;
        private BigDecimal advancePercent;
        private BigDecimal midtermPercent;
        /** 100 less the advance and the midterm: paid after the delivered units are tested */
        private BigDecimal finalPercent;
        private PaymentMethod expectedPaymentMethod;
        private OrderStatus orderStatus;
        private LocalDate expectedDelivery;
        private LocalDate actualDelivery;
        private LocalDate orderDate;
        private Map<String, Object> additionalInfo;
        private LocalDateTime createdAt;
        private Long createdBy;
        /** The supplier raised this quotation themselves from their supplier login */
        private boolean raisedBySupplier;
        /** A supplier's change to this quotation waits for approval; until then it can't be edited, submitted or cancelled */
        private boolean supplierChangePending;
    }
}