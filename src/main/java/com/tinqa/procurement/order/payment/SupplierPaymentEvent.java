package com.tinqa.procurement.order.payment;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** Append-only: one row per step of a payment entry, written in the same transaction as the step. */
@Entity
@Table(name = "supplier_payment_event")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private SupplierPaymentEventType type;

    // The user who took the step (staff or the supplier's login); null for the system
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant at;

    @Column(length = 100, updatable = false)
    private String reference;

    @Column(name = "paid_on", updatable = false)
    private LocalDate paidOn;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "proof_document_id", updatable = false)
    private Long proofDocumentId;
}
