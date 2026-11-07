package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.validation.MaxAmount;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.order.enums.OrderStatus;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DealerPaymentDTOs {

    private DealerPaymentDTOs() {
    }

    /** A payment to the dealer (PAYMENT, against a milestone) or a refund the dealer paid back (REFUND). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordRequest {
        @NotNull(message = "Say whether this is a payment to the dealer or a refund from them")
        private DealerPaymentKind kind;

        // Required for a payment, left out for a refund (checked in the service)
        private PaymentMilestone milestone;

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be more than zero")
        @MaxAmount(message = "Amount cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal amount;

        @NotNull(message = "Payment method is required")
        private PaymentMethod method;

        @Size(max = 100, message = "Reference cannot exceed 100 characters")
        private String reference;

        @NotNull(message = "Payment date is required")
        @PastOrPresent(message = "Payment date cannot be in the future")
        private LocalDate paidOn;

        @Size(max = 500, message = "Note cannot exceed 500 characters")
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReverseRequest {
        @NotBlank(message = "Say why this entry is reversed")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
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

    /** A Level 2 changes what the dealer must pay back, e.g. after agreeing a deduction with them. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaimAdjustRequest {
        @NotNull(message = "The amount to be refunded is required")
        @PositiveOrZero(message = "The amount to be refunded cannot be negative")
        @MaxAmount(message = "Amount cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal agreedAmount;

        @NotBlank(message = "Say why the refund amount is changed")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private Long id;
        private Long orderId;
        private String orderNumber;
        private String dealerName;
        private DealerPaymentKind kind;
        private PaymentMilestone milestone;
        private BigDecimal amount;
        private PaymentMethod method;
        private String reference;
        private LocalDate paidOn;
        private String note;
        private Long reversesId;
        /** An approved entry with an approved or waiting reversal */
        private boolean reversed;
        private DealerPaymentStatus status;
        /** Staff names: left out of the dealer's own view */
        private String recordedByUsername;
        private Long recordedBy;
        private Instant recordedAt;
        private String decidedByUsername;
        private Instant decidedAt;
        private String decisionReason;
    }

    /** One part of the plan: what it is worth, what was paid and confirmed, and what waits for confirmation. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Milestone {
        private PaymentMilestone milestone;
        private BigDecimal percent;
        private BigDecimal planned;
        private BigDecimal paid;
        private BigDecimal pending;
        /** The order has reached the point where this part is due (approved, shipped, tested) */
        private boolean due;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Claim {
        private Long id;
        private RefundClaimCause cause;
        private BigDecimal failedUnits;
        private BigDecimal claimedAmount;
        private BigDecimal agreedAmount;
        private String adjustReason;
        private Instant adjustedAt;
        private BigDecimal refunded;
        /** Still to be paid back by the dealer */
        private BigDecimal refundDue;
        private RefundClaimStatus status;
        private Instant openedAt;
        private Instant settledAt;
    }

    /**
     * Where an order's payments stand. Before testing the order is worth its quoted total (GST included); once the
     * stock entry is approved, only what passed testing (passed units x unit price + shipment, with its GST).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private Long orderId;
        private String orderNumber;
        private OrderStatus orderStatus;
        private boolean hasPlan;
        private BigDecimal advancePercent;
        private BigDecimal midtermPercent;
        private BigDecimal finalPercent;
        private PaymentMethod expectedPaymentMethod;
        /** The quoted total, GST included */
        private BigDecimal orderValue;
        /** The stock entry (units tested) is approved */
        private boolean tested;
        private BigDecimal unitsPassed;
        private BigDecimal unitsFailed;
        /** What TinQa owes the dealer in all, now */
        private BigDecimal payable;
        /** Confirmed payments to the dealer, less confirmed reversals */
        private BigDecimal paid;
        private BigDecimal pendingPayments;
        /** Confirmed refunds from the dealer, less confirmed reversals */
        private BigDecimal refunded;
        private BigDecimal pendingRefunds;
        /** Still to pay the dealer (payable less what was paid and not refunded) */
        private BigDecimal outstanding;
        /** The most that can be recorded as paid now (the final part waits for testing) */
        private BigDecimal payableNow;
        /** The milestones a payment can be recorded against now */
        private List<PaymentMilestone> openMilestones;
        private List<Milestone> milestones;
        private Claim claim;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderPayments {
        private Summary summary;
        private List<Entry> entries;
    }
}
