package com.tinqa.procurement.order.service;

import com.tinqa.procurement.order.dto.OrderDTOs;
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

    OrderDTOs.Response getOrderById(Long orderId);

    List<OrderDTOs.Response> getAllOrders();

    List<OrderDTOs.Response> getOrdersByDealerId(Long dealerId);

    List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status);
}
