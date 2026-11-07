package com.tinqa.procurement.order.supplierreturn;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Append-only: one row per step of a return, written in the same transaction as the step. */
@Entity
@Table(name = "supplier_return_event")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierReturnEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_id", nullable = false, updatable = false)
    private Long returnId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SupplierReturnEventType type;

    // Staff or the supplier's login; null for the system
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant at;

    @Column(length = 1000, updatable = false)
    private String note;
}
