package com.tinqa.procurement.order.change;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * A dealer's proposed edit to one of their quotations. Nothing on the order changes until an approver accepts it.
 * {@code changes} holds only the proposed fields; {@code previous} the same fields as they were when proposed.
 */
@Entity
@Table(name = "order_change_request")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> changes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> previous;

    // The dealer's explanation for the approver
    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderChangeStatus status;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;
}
