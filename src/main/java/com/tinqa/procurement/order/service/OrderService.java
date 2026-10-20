package com.tinqa.procurement.order.service;

import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;

import java.util.List;

/**
 * Purchase orders and their forward-only status workflow. Every change is made as the signed-in user.
 */
public interface OrderService {

    OrderDTOs.Response createOrder(OrderDTOs.CreateRequest request);

    OrderDTOs.Response updateOrder(Long orderId, OrderDTOs.UpdateRequest request);

    OrderDTOs.Response updateOrderStatus(Long orderId, OrderDTOs.UpdateStatusRequest request);

    /** Admin L2 decision on a PENDING order: DEALER_LEVEL_PENDING (approve) or CANCELLED (reject). */
    OrderDTOs.Response processAdminL2Approval(Long orderId, OrderDTOs.ApprovalDecisionRequest request);

    /**
     * The dealer's answer to an order awaiting them: CONFIRMED (accept) or CANCELLED (reject, with a reason).
     * The caller has locked the order and checked it belongs to the signed-in dealer.
     */
    OrderDTOs.Response processDealerResponse(Order order, boolean accepted, String reason);

    /**
     * The dealer marks a CONFIRMED order as SHIPPED.
     * The caller has locked the order and checked it belongs to the signed-in dealer.
     */
    OrderDTOs.Response processDealerShipment(Order order);

    OrderDTOs.Response getOrderById(Long orderId);

    List<OrderDTOs.Response> getAllOrders();

    List<OrderDTOs.Response> getOrdersByDealerId(Long dealerId);

    List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status);

    /** Delivered orders that have no stock entry yet: what the warehouse can still receive into stock. */
    List<OrderDTOs.Response> getOrdersAwaitingStock();
}
