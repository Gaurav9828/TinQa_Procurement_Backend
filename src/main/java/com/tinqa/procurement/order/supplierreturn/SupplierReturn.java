package com.tinqa.procurement.order.supplierreturn;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A return of pieces of an order that failed testing, to the supplier, who repays what TinQa paid for them and the
 * return shipping. See SupplierReturnStatus for its steps; each one is kept in supplier_return_event.
 */
@Entity
@Table(name = "supplier_return")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "stock_id", updatable = false)
    private Long stockId;

    @Column(nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal units;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "piece_details", length = 1000, updatable = false)
    private String pieceDetails;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_mode", nullable = false, length = 20, updatable = false)
    private ReturnShippingMode shippingMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SupplierReturnStatus status;

    // units x unit price, with its GST under the quotation
    @Column(name = "pieces_value", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal piecesValue;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "supplier_responded_at")
    private Instant supplierRespondedAt;

    @Column(name = "supplier_response_reason", length = 500)
    private String supplierResponseReason;

    @Column(length = 100)
    private String courier;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "dispatched_on")
    private LocalDate dispatchedOn;

    @Column(name = "shipping_charge", precision = 14, scale = 2)
    private BigDecimal shippingCharge;

    @Column(name = "dispatch_note", length = 500)
    private String dispatchNote;

    @Column(name = "dispatched_by")
    private Long dispatchedBy;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "repayment_id")
    private Long repaymentId;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
