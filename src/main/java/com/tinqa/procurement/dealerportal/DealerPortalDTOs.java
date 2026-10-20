package com.tinqa.procurement.dealerportal;

import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.SafeJsonMap;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.order.change.OrderChangeDTOs;
import com.tinqa.procurement.order.change.OrderChangeStatus;
import com.tinqa.procurement.order.enums.OrderStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * What a dealer sees and sends. Deliberately separate from the admin DTOs: no internal ids of staff, no internal
 * notes, and the dealer is never chosen by the request but always taken from the signed-in account.
 */
public final class DealerPortalDTOs {

    private DealerPortalDTOs() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Profile {
        private Long dealerId;
        private String name;
        private String tradeName;
        private String email;
        private String phoneNumber;
        private String alternatePhoneNumber;
        private String street;
        private String landmark;
        private String city;
        private String district;
        private String state;
        private String country;
        private String pincode;
        private String gstin;
        private String panNumber;
        private String username;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private String name;
        private String brand;
        private String sku;
        private String unitOfMeasure;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuotationRequest {
        @NotNull(message = "Item is required")
        @Positive(message = "Item must be a valid ID")
        private Long itemId;

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        @Digits(integer = 11, fraction = 3, message = "Quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal orderQuantity;

        @NotBlank(message = "Unit is required")
        @Size(max = 20, message = "Unit cannot exceed 20 characters")
        @Pattern(regexp = ValidationPatterns.UNIT, message = "Unit " + ValidationPatterns.UNIT_MESSAGE)
        private String unitType;

        @NotNull(message = "Unit price is required")
        @PositiveOrZero(message = "Unit price cannot be negative")
        @MaxAmount(message = "Unit price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal unitPrice;

        @NotNull(message = "Shipment price is required")
        @PositiveOrZero(message = "Shipment price cannot be negative")
        @MaxAmount(message = "Shipment price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal shipmentPrice;

        /** Optional GST breakup (see QuotationTax): supplyType, gstRate, hsnSac, cgst, sgst, igst, cess */
        @SafeJsonMap(maxEntries = 20, maxDepth = 1)
        private Map<String, Object> taxBreakup;

        private LocalDate expectedDelivery;

        @Size(max = 500, message = "Remarks cannot exceed 500 characters")
        private String note;
    }

    /** A proposed change: only the fields that are sent are changed. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangeRequest {
        @Positive(message = "Item must be a valid ID")
        private Long itemId;

        @Positive(message = "Quantity must be greater than zero")
        @Digits(integer = 11, fraction = 3, message = "Quantity can have at most 11 digits and 3 decimal places")
        private BigDecimal orderQuantity;

        @Size(max = 20, message = "Unit cannot exceed 20 characters")
        @Pattern(regexp = ValidationPatterns.UNIT, message = "Unit " + ValidationPatterns.UNIT_MESSAGE)
        private String unitType;

        @PositiveOrZero(message = "Unit price cannot be negative")
        @MaxAmount(message = "Unit price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal unitPrice;

        @PositiveOrZero(message = "Shipment price cannot be negative")
        @MaxAmount(message = "Shipment price cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal shipmentPrice;

        /** A new GST breakup; {} removes it */
        @SafeJsonMap(maxEntries = 20, maxDepth = 1)
        private Map<String, Object> taxBreakup;

        private LocalDate expectedDelivery;

        @Size(max = 500, message = "Remarks cannot exceed 500 characters")
        private String note;

        // Why the dealer wants the change, for the approver
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    /** A change request as the dealer sees it (no staff names). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {
        private Long id;
        private OrderChangeDTOs.Terms changes;
        private OrderChangeDTOs.Terms previous;
        private BigDecimal proposedTotalPrice;
        private String reason;
        private OrderChangeStatus status;
        private Instant requestedAt;
        private Instant decidedAt;
        private String decisionReason;

        public static Change of(OrderChangeDTOs.Response response) {
            return Change.builder()
                    .id(response.getId())
                    .changes(response.getChanges())
                    .previous(response.getPrevious())
                    .proposedTotalPrice(response.getProposedTotalPrice())
                    .reason(response.getNote())
                    .status(response.getStatus())
                    .requestedAt(response.getRequestedAt())
                    .decidedAt(response.getDecidedAt())
                    .decisionReason(response.getDecisionReason())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusStep {
        private OrderStatus fromStatus;
        private OrderStatus toStatus;
        private Instant changedAt;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        private Long id;
        private String orderNumber;
        private Long itemId;
        private String itemName;
        private String itemSku;
        private BigDecimal orderQuantity;
        private String unitType;
        private BigDecimal unitPrice;
        private BigDecimal shipmentPrice;
        private BigDecimal totalPrice;
        private Map<String, Object> taxBreakup;
        private OrderStatus orderStatus;
        private LocalDate orderDate;
        private LocalDate expectedDelivery;
        private LocalDate actualDelivery;
        private LocalDateTime createdAt;
        /** The dealer's own remarks */
        private String note;
        /** Raised by the dealer (rather than by a Dealer Manager on their behalf) */
        private boolean raisedByYou;
        /** A change can be requested now: still a quotation and no other change is waiting */
        private boolean changeable;
        // Approved by staff and waiting for the dealer to accept or reject it
        private boolean awaitingYourResponse;
        /** Confirmed and not yet shipped: the dealer can mark it as shipped */
        private boolean shippable;
        private Change pendingChange;
        /** Only on the single-order view */
        private List<StatusStep> history;
        private List<Change> changes;
    }

    public enum OrderDecision {
        ACCEPT, REJECT
    }

    /** The dealer's answer to an order awaiting them; a rejection must say why. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderResponseRequest {
        @NotNull(message = "Decision is required (ACCEPT or REJECT)")
        private OrderDecision decision;

        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }
}
