package com.tinqa.procurement.order.workflow;

import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.security.access.Permission;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.tinqa.procurement.order.enums.OrderStatus.*;

/**
 * The forward-only order workflow: the only moves PATCH /status may make, and the permission each one needs.
 * PENDING -> DEALER_LEVEL_PENDING / CANCELLED is the approval decision and goes through PATCH /approval
 * only, so it is deliberately not in this table. Anything not listed is rejected.
 */
public final class OrderStatusTransitions {

    // Routine progress, and the decisions that belong to an approver
    private static final Permission L1_AND_L2 = Permission.PROCUREMENT_ORDER_UPDATE_STATUS;
    private static final Permission L2_ONLY = Permission.PROCUREMENT_ORDER_APPROVE;

    private static final Map<OrderStatus, Map<OrderStatus, Permission>> STATUS_MOVES = new EnumMap<>(OrderStatus.class);

    static {
        allow(QUOTATION, PENDING, L1_AND_L2);
        allow(QUOTATION, CANCELLED, L1_AND_L2);
        allow(DEALER_LEVEL_PENDING, CONFIRMED, L2_ONLY);
        allow(DEALER_LEVEL_PENDING, CANCELLED, L2_ONLY);
        allow(CONFIRMED, SHIPPED, L1_AND_L2);
        allow(CONFIRMED, CANCELLED, L1_AND_L2);
        allow(SHIPPED, DELIVERED, L1_AND_L2);
        allow(DELIVERED, RETURN_REQUESTED, L1_AND_L2);
        allow(RETURN_REQUESTED, RETURN_APPROVED, L2_ONLY);
        allow(RETURN_REQUESTED, RETURN_REJECTED, L2_ONLY);
        allow(RETURN_APPROVED, RETURN_PICKED_UP, L1_AND_L2);
        allow(RETURN_PICKED_UP, RETURN_RECEIVED, L1_AND_L2);
        allow(RETURN_RECEIVED, REFUND_INITIATED, L1_AND_L2);
        allow(REFUND_INITIATED, REFUND_COMPLETED, L1_AND_L2);
        allow(REFUND_INITIATED, REFUND_FAILED, L1_AND_L2);
        allow(REFUND_FAILED, REFUND_INITIATED, L1_AND_L2);
    }

    /** Outcomes of the PATCH /approval decision on a PENDING order. */
    public static final Set<OrderStatus> APPROVAL_DECISIONS = EnumSet.of(DEALER_LEVEL_PENDING, CANCELLED);
    public static final Permission APPROVAL_PERMISSION = L2_ONLY;

    /** Moves that must say why. */
    public static final Set<OrderStatus> REASON_REQUIRED = EnumSet.of(CANCELLED, RETURN_REQUESTED, RETURN_REJECTED, REFUND_FAILED);

    public static final Set<OrderStatus> FINAL = EnumSet.of(CANCELLED, RETURN_REJECTED, REFUND_COMPLETED);

    private OrderStatusTransitions() {
    }

    private static void allow(OrderStatus from, OrderStatus to, Permission permission) {
        STATUS_MOVES.computeIfAbsent(from, ignored -> new EnumMap<>(OrderStatus.class)).put(to, permission);
    }

    /** The permission needed to move an order from {@code from} to {@code to} via PATCH /status; empty if never allowed. */
    public static Optional<Permission> permissionForStatusChange(OrderStatus from, OrderStatus to) {
        return Optional.ofNullable(STATUS_MOVES.getOrDefault(from, Map.of()).get(to));
    }
}
