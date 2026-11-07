package com.tinqa.procurement.order.payment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One payment TinQa made to a supplier for an order, one refund the supplier paid back, or the reversal of one of them.
 * Recorded by one person and confirmed by a different one (maker-checker); only approved entries count. While it waits
 * for approval its recorder may correct or withdraw it; once decided it never changes (a mistake is reversed).
 */
@Entity
@Table(name = "supplier_payment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SupplierPaymentKind kind;

    // PAYMENT: the part of the plan it pays. REVERSAL: the reversed entry's (null when it reverses a refund)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMilestone milestone;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    // UTR, cheque number, transaction id or receipt number
    @Column(length = 100)
    private String reference;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(length = 500)
    private String note;

    @Column(name = "reverses_id", updatable = false)
    private Long reversesId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierPaymentStatus status;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** Paid to the supplier (a payment, or the reversal of a refund) rather than paid back by them. */
    public boolean towardsPayments() {
        return kind == SupplierPaymentKind.PAYMENT || (kind == SupplierPaymentKind.REVERSAL && milestone != null);
    }
}
