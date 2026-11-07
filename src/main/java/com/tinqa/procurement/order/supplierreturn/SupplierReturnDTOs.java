package com.tinqa.procurement.order.supplierreturn;

import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SupplierReturnDTOs {

    private SupplierReturnDTOs() {
    }

    /** A Supplier Manager raises a return of failed pieces (a Super Admin approves it before it goes to the supplier). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RaiseRequest {
        @NotNull(message = "Say how many pieces go back")
        @Positive(message = "At least one piece goes back")
        @Digits(integer = 11, fraction = 3, message = "Units can have at most 3 decimal places")
        private BigDecimal units;

        @NotBlank(message = "Say why the pieces go back")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;

        @Size(max = 1000, message = "Piece details cannot exceed 1000 characters")
        private String pieceDetails;

        @NotNull(message = "Say whether TinQa ships the pieces or the supplier collects them")
        private ReturnShippingMode shippingMode;
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

    /** A note: why a declined return is sent again, or why it is cancelled. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoteRequest {
        @NotBlank(message = "A note is required")
        @Size(max = 500, message = "Note cannot exceed 500 characters")
        private String note;
    }

    /** The pieces leave TinQa: shipped by TinQa (courier, tracking number, charge) or collected by the supplier. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatchRequest {
        @NotNull(message = "The dispatch (or pickup) date is required")
        @PastOrPresent(message = "The dispatch date cannot be in the future")
        private LocalDate dispatchedOn;

        @Size(max = 100, message = "Courier cannot exceed 100 characters")
        private String courier;

        @Size(max = 100, message = "Tracking number cannot exceed 100 characters")
        private String trackingNumber;

        @PositiveOrZero(message = "The shipping charge cannot be negative")
        @MaxAmount(message = "Amount cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal shippingCharge;

        @Size(max = 500, message = "Note cannot exceed 500 characters")
        private String note;
    }

    /** The supplier accepts a return, or declines it with a reason. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseRequest {
        @NotNull(message = "Say whether you accept the return")
        private Boolean accepted;

        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArrivedRequest {
        @Size(max = 500, message = "Note cannot exceed 500 characters")
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private Long id;
        private SupplierReturnEventType type;
        private Instant at;
        private String actorRole;
        /** Staff view only */
        private String actorName;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Return {
        private Long id;
        private Long orderId;
        private String orderNumber;
        private String supplierName;
        private String itemName;
        private BigDecimal units;
        private String unitType;
        private BigDecimal unitPrice;
        private String reason;
        private String pieceDetails;
        private ReturnShippingMode shippingMode;
        private SupplierReturnStatus status;
        private BigDecimal piecesValue;
        private String courier;
        private String trackingNumber;
        private LocalDate dispatchedOn;
        private BigDecimal shippingCharge;
        /** Once the pieces arrived: the repayment asked of the supplier, and what it asks (pieces + shipping) */
        private Long repaymentId;
        private BigDecimal amountDue;
        /** Staff view only */
        private String requestedByUsername;
        private Instant requestedAt;
        /** Staff view only */
        private String decidedByUsername;
        private Instant decidedAt;
        /** Staff view only */
        private String decisionReason;
        private Instant supplierRespondedAt;
        private String supplierResponseReason;
        private Instant receivedAt;
        private Instant settledAt;
        private String cancelReason;
        private List<Event> events;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderReturns {
        private Long orderId;
        private boolean tested;
        private BigDecimal failedUnits;
        private BigDecimal unitsInReturns;
        private BigDecimal unitsLeft;
        private boolean canRaise;
        /** Newest first */
        private List<Return> returns;
    }
}
