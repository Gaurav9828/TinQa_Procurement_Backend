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

public final class SupplierPaymentDTOs {

    private SupplierPaymentDTOs() {
    }

    /**
     * A request to pay the supplier (PAYMENT, against a milestone) or a refund the supplier paid back (REFUND). A
     * payment's amount is set by the plan (what is still owed on its part, see Milestone.left): left out, it is filled
     * in; given, it must be exactly that. A payment request has no reference or date yet (given when it is paid, see
     * PaidRequest; any sent are ignored). A refund's amount, reference and date are given (the supplier may pay back
     * in parts).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordRequest {
        @NotNull(message = "Say whether this is a payment to the supplier or a refund from them")
        private SupplierPaymentKind kind;

        // Required for a payment, left out for a refund (checked in the service)
        private PaymentMilestone milestone;

        @Positive(message = "Amount must be more than zero")
        @MaxAmount(message = "Amount cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
        private BigDecimal amount;

        @NotNull(message = "Payment method is required")
        private PaymentMethod method;

        // Refunds: required unless paid in cash (transaction ID, UTR or cheque number; checked in the service)
        @Size(max = 100, message = "Reference cannot exceed 100 characters")
        private String reference;

        // Refunds: required (checked in the service). Payment requests: given when paid
        @PastOrPresent(message = "Payment date cannot be in the future")
        private LocalDate paidOn;

        @Size(max = 500, message = "Note cannot exceed 500 characters")
        private String note;
    }

    /** The Finance Manager made an approved payment: its reference (unless cash) and date. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaidRequest {
        // Required unless paid in cash: transaction ID, UTR or cheque number (checked in the service)
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

    /** The supplier's answer to a payment waiting for them: received it, or not (with a reason). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceiptRequest {
        @NotNull(message = "Say whether you received this payment")
        private Boolean received;

        // Required when it was not received (checked in the service)
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    /**
     * The supplier pays money back: how, its reference (required unless cash, checked in the service), the date, and a
     * note. {@code amount} is given only for a refund they pay on their own; an ask's amount is set by TinQa. Sent as a
     * multipart form with an optional receipt file.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepaymentRequest {
        private BigDecimal amount;
        private PaymentMethod method;
        private String reference;
        private LocalDate paidOn;
        private String note;
    }

    /** A Finance Manager's check of money the supplier says they paid back: it arrived, or not (with a reason). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepaymentCheckRequest {
        @NotNull(message = "Say whether the money arrived")
        private Boolean received;

        // Required when it did not arrive (checked in the service)
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        private String reason;
    }

    /** One confirmed repayment for the company account's supplier money (see docs/supplier-returns-and-repayments.md §8). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Receipt {
        private Long receiptId;
        private String postingKey;
        private SupplierPaymentKind kind;
        /** IN: the supplier paid TinQa. OUT: a confirmed refund was reversed (paid back to them) */
        private String direction;
        /** FAILED_PIECES_RETURN, REFUND_CLAIM, PAYMENT_REVERSAL or REFUND_REVERSAL */
        private String reason;
        private String claimCause;
        private String orderNumber;
        private String supplierName;
        private Long returnId;
        private PaymentMilestone milestone;
        private BigDecimal amount;
        private BigDecimal chargeAmount;
        private PaymentMethod method;
        private String reference;
        private LocalDate paidOn;
        private String note;
        private String receiptFileName;
        private String confirmedBy;
        private Instant confirmedAt;
    }

    public record ReceiptPage(List<Receipt> receipts, boolean hasMore) {
    }

    /** A Level 2 changes what the supplier must pay back, e.g. after agreeing a deduction with them. */
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
        private String supplierName;
        private SupplierPaymentKind kind;
        private PaymentMilestone milestone;
        private BigDecimal amount;
        /** A return's repayment: the return shipping charge, paid on top of amount (0 otherwise) */
        private BigDecimal chargeAmount;
        /** The return of failed pieces this repayment is for */
        private Long returnId;
        /** A payment request waiting for approval: the earlier part of the plan to approve first (null: can be approved) */
        private PaymentMilestone waitsFor;
        private PaymentMethod method;
        private String reference;
        private LocalDate paidOn;
        private String note;
        private Long reversesId;
        /** An approved entry with an approved or waiting reversal */
        private boolean reversed;
        private SupplierPaymentStatus status;
        /** Staff names: left out of the supplier's own view */
        private String recordedByUsername;
        private Long recordedBy;
        private Instant recordedAt;
        private String decidedByUsername;
        private Instant decidedAt;
        private String decisionReason;
        /** Payments: when the supplier answered, and why they said they did not receive it */
        private Instant supplierRespondedAt;
        private String supplierResponseReason;
        /** Payments: who made the payment (gave its reference), and when */
        private String paidByUsername;
        private Instant paidAt;
        /** Payments: how many times it was sent to the supplier (paid, then each re-send) */
        private int sendCount;
        /** Every step, oldest first (the supplier sees only those from the payment on, without staff names) */
        private List<Event> events;
    }

    /** One step of an entry (supplier_payment_event). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private Long id;
        private SupplierPaymentEventType type;
        private Instant at;
        /** The designation ("Finance & Accounts Manager"), "Supplier", or null for the system */
        private String actorRole;
        /** Staff view only */
        private String actorName;
        private String reference;
        private LocalDate paidOn;
        private String note;
        private Long proofDocumentId;
        private String proofFileName;
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
        /**
         * What a payment against this part is now: its planned amount less what was paid or waits for confirmation,
         * within what may be paid in all before (or after) testing. 0 when it isn't due or nothing is left.
         */
        private BigDecimal left;
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
        /** Still to be paid back by the supplier */
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
        /** What TinQa owes the supplier in all, now */
        private BigDecimal payable;
        /** Confirmed payments to the supplier, less confirmed reversals */
        private BigDecimal paid;
        private BigDecimal pendingPayments;
        /** Of the waiting payments: those confirmed by staff, waiting for the supplier to confirm receipt */
        private BigDecimal awaitingSupplier;
        /** Confirmed refunds from the supplier, less confirmed reversals */
        private BigDecimal refunded;
        private BigDecimal pendingRefunds;
        /** Still to pay the supplier (payable less what was paid and not refunded) */
        private BigDecimal outstanding;
        /** The most that can be recorded as paid now (the final part waits for testing) */
        private BigDecimal payableNow;
        /** The milestones a payment can be recorded against now */
        private List<PaymentMilestone> openMilestones;
        /**
         * Delivered, tested, fully paid and every payment received by the supplier: no entry of the order can be
         * reversed any more (reverse answers 409 SUPPLIER_PAYMENT_REVERSAL_CLOSED)
         */
        private boolean reversalClosed;
        private List<Milestone> milestones;
        private Claim claim;
        /** Why the order is worth what it is after testing; null until the stock entry is approved (or once closed) */
        private Settlement settlement;
    }

    /**
     * The breakdown from the quoted value to the worth after testing (see PaymentSettlement). Money is in rupees with
     * GST where named; {@code orderValue - failedValue - shortValue - shipmentDropped - gstReduction = payable}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Settlement {
        private String unitType;
        private BigDecimal unitPrice;
        private BigDecimal orderedUnits;
        private BigDecimal passedUnits;
        private BigDecimal failedUnits;
        /** Neither passed nor failed: not delivered (negative when more than ordered was tested) */
        private BigDecimal shortUnits;
        private BigDecimal shipment;
        /** The quotation's GST rate, null without a GST breakup */
        private BigDecimal gstRate;
        /** Unit price x ordered units */
        private BigDecimal quotedGoods;
        /** Goods plus shipment, before GST */
        private BigDecimal quotedTaxable;
        private BigDecimal quotedGst;
        /** The quoted total, GST included */
        private BigDecimal orderValue;
        /** Unit price x failed units, before GST */
        private BigDecimal failedValue;
        /** Unit price x units not delivered, before GST */
        private BigDecimal shortValue;
        /** The shipment, taken off only when no unit passed */
        private BigDecimal shipmentDropped;
        /** The quoted GST less the GST on what passed */
        private BigDecimal gstReduction;
        /** The quoted total less the worth after testing */
        private BigDecimal reduction;
        /** Unit price x passed units */
        private BigDecimal passedGoods;
        private BigDecimal passedTaxable;
        private BigDecimal passedGst;
        /** The worth after testing, GST included (Summary.payable) */
        private BigDecimal payable;
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
