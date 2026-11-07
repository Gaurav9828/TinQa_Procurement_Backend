package com.tinqa.procurement.order.payment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money a supplier owes back for one order. Opened by TinQa itself when testing (the stock entry's approval), a
 * cancellation or a completed return leaves more paid than the order is now worth. {@code claimedAmount} follows the
 * figures; {@code agreedAmount} is what the supplier must pay back, equal to it until a Level 2 changes it with a reason.
 */
@Entity
@Table(name = "supplier_refund_claim")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierRefundClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RefundClaimCause cause;

    // Units that failed testing (they go back to the supplier)
    @Column(name = "failed_units", nullable = false, precision = 14, scale = 3)
    private BigDecimal failedUnits;

    @Column(name = "claimed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "agreed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal agreedAmount;

    @Column(name = "adjust_reason", length = 500)
    private String adjustReason;

    @Column(name = "adjusted_by")
    private Long adjustedBy;

    @Column(name = "adjusted_at")
    private Instant adjustedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundClaimStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "settled_at")
    private Instant settledAt;
}
