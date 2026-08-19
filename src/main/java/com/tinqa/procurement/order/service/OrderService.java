package com.tinqa.procurement.order.service;

import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.enums.OrderStatus;

import java.util.List;

public interface OrderService {

    OrderDTOs.Response createOrder(OrderDTOs.CreateRequest request, Long currentUserId);

    OrderDTOs.Response updateOrder(Long orderId, OrderDTOs.UpdateRequest request, Long currentUserId);

    OrderDTOs.Response updateOrderStatus(Long orderId, OrderDTOs.UpdateStatusRequest request, Long currentUserId);

    OrderDTOs.Response processAdminL2Approval(Long orderId, OrderDTOs.ApprovalDecisionRequest request, Long currentUserId);

    OrderDTOs.Response getOrderById(Long orderId);

    List<OrderDTOs.Response> getAllOrders();

    List<OrderDTOs.Response> getOrdersByDealerId(Long dealerId);

    List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status);
}