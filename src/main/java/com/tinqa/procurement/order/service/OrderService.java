package com.tinqa.procurement.order.service;

import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;

import java.util.Collection;
import java.util.List;

/**
 * Purchase orders and their forward-only status workflow. Every change is made as the signed-in user.
 */
public interface OrderService {

    OrderDTOs.Response createOrder(OrderDTOs.CreateRequest request);

    OrderDTOs.Response updateOrder(Long orderId, OrderDTOs.UpdateRequest request);

    OrderDTOs.Response updateOrderStatus(Long orderId, OrderDTOs.UpdateStatusRequest request);

    /** Admin L2 decision on a PENDING order: SUPPLIER_LEVEL_PENDING (approve) or CANCELLED (reject). */
    OrderDTOs.Response processAdminL2Approval(Long orderId, OrderDTOs.ApprovalDecisionRequest request);

    /**
     * The supplier's answer to an order awaiting them: CONFIRMED (accept) or CANCELLED (reject, with a reason).
     * The caller has locked the order and checked it belongs to the signed-in supplier.
     */
    OrderDTOs.Response processSupplierResponse(Order order, boolean accepted, String reason);

    /**
     * The supplier marks a CONFIRMED order as SHIPPED.
     * The caller has locked the order and checked it belongs to the signed-in supplier.
     */
    OrderDTOs.Response processSupplierShipment(Order order);

    OrderDTOs.Response getOrderById(Long orderId);

    List<OrderDTOs.Response> getAllOrders();

    List<OrderDTOs.Response> getOrdersBySupplierId(Long supplierId);

    List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status);

    /** Orders in any of the statuses, oldest first. */
    List<OrderDTOs.Response> getOrdersByStatuses(Collection<OrderStatus> statuses);

    /** Delivered orders that have no stock entry yet: what the warehouse can still receive into stock. */
    List<OrderDTOs.Response> getOrdersAwaitingStock();
}
