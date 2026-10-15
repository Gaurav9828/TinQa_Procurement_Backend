package com.tinqa.procurement.order.entity;

import com.tinqa.procurement.order.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

// Append-only: one row per status change, written in the same transaction as the change
@Entity
@Table(name = "order_status_history")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    // Null for the row written when the order is created
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private OrderStatus toStatus;

    // The authenticated admin, never a value from the request body
    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "actual_delivery", updatable = false)
    private LocalDate actualDelivery;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;
}
