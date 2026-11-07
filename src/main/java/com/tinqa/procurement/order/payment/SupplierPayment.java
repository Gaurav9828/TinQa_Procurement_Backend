package com.tinqa.procurement.order.payment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One payment TinQa makes to a supplier for an order, one refund the supplier paid back, or the reversal of one of them.
 * A payment is requested by a Finance Manager, approved by a Super Admin, paid, and counts once the supplier confirms
 * they received it (see SupplierPaymentStatus). Refunds and reversals are recorded by one person and confirmed by
 * another. Only approved entries count as paid; a confirmed entry never changes (a mistake is reversed). Its steps are
 * kept in supplier_payment_event.
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

    // A payment's date is given when it is made (null while it is only requested)
    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(length = 500)
    private String note;

    @Column(name = "reverses_id", updatable = false)
    private Long reversesId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SupplierPaymentStatus status;

    // A return's repayment: the return shipping charge, paid on top of amount and never part of the refund claim
    @Column(name = "charge_amount", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal chargeAmount = BigDecimal.ZERO.setScale(2);

    // The return of failed pieces this repayment is for
    @Column(name = "return_id", updatable = false)
    private Long returnId;

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

    // Payments to the supplier: who made the payment (gave its reference) and when
    @Column(name = "paid_by")
    private Long paidBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    // Payments to the supplier: when the supplier said they received it (or not), and why not
    @Column(name = "supplier_responded_at")
    private Instant supplierRespondedAt;

    @Column(name = "supplier_response_reason", length = 500)
    private String supplierResponseReason;

    /** Every payment to the supplier, whatever the method: the supplier confirms they received it before it counts. */
    public boolean needsSupplierReceipt() {
        return kind == SupplierPaymentKind.PAYMENT;
    }

    /**
     * Money back from the supplier, which they pay and a Finance Manager checks: a refund, or the reversal of a payment.
     * (The reversal of a refund is money going out to the supplier, decided by a Super Admin.)
     */
    public boolean repayment() {
        return kind == SupplierPaymentKind.REFUND || (kind == SupplierPaymentKind.REVERSAL && milestone != null);
    }

    /** What the supplier pays for a repayment: the amount and any return shipping charge. */
    public BigDecimal totalDue() {
        return amount.add(chargeAmount == null ? BigDecimal.ZERO : chargeAmount);
    }

    /** Paid to the supplier (a payment, or the reversal of a refund) rather than paid back by them. */
    public boolean towardsPayments() {
        return kind == SupplierPaymentKind.PAYMENT || (kind == SupplierPaymentKind.REVERSAL && milestone != null);
    }
}
