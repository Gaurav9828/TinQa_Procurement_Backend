package com.tinqa.procurement.order.service.impl;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.dealer.repository.DealerRepository;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.notification.dto.NotificationResponse;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final DealerRepository dealerRepository;
    private final ItemRepository itemRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public OrderDTOs.Response createOrder(OrderDTOs.CreateRequest request, Long currentUserId) {
        Dealer dealer = dealerRepository.findById(request.getDealerId())
                .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with id: " + request.getDealerId()));

        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + request.getItemId()));

        String orderNumber = "ORD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        BigDecimal totalPrice = request.getUnitPrice().multiply(request.getOrderQuantity()).add(request.getShipmentPrice());

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .dealer(dealer)
                .item(item)
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                .totalPrice(totalPrice)
                .taxBreakup(request.getTaxBreakup())
                .orderStatus(OrderStatus.PENDING)
                .expectedDelivery(request.getExpectedDelivery())
                .orderDate(request.getOrderDate())
                .additionalInfo(request.getAdditionalInfo())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderDTOs.Response updateOrder(Long orderId, OrderDTOs.UpdateRequest request, Long currentUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        if (order.getOrderStatus() == OrderStatus.DELIVERED || order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new BadRequestException("Cannot update order in " + order.getOrderStatus() + " status");
        }

        if (request.getDealerId() != null && !request.getDealerId().equals(order.getDealer().getId())) {
            Dealer dealer = dealerRepository.findById(request.getDealerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Dealer not found with id: " + request.getDealerId()));
            order.setDealer(dealer);
        }

        if (request.getItemId() != null && !request.getItemId().equals(order.getItem().getId())) {
            Item item = itemRepository.findById(request.getItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + request.getItemId()));
            order.setItem(item);
        }

        if (request.getOrderQuantity() != null) {
            order.setOrderQuantity(request.getOrderQuantity());
        }
        if (request.getUnitType() != null) {
            order.setUnitType(request.getUnitType());
        }
        if (request.getUnitPrice() != null) {
            order.setUnitPrice(request.getUnitPrice());
        }
        if (request.getShipmentPrice() != null) {
            order.setShipmentPrice(request.getShipmentPrice());
        }

        // Recalculate total price
        BigDecimal totalPrice = order.getUnitPrice().multiply(order.getOrderQuantity()).add(order.getShipmentPrice());
        order.setTotalPrice(totalPrice);

        if (request.getTaxBreakup() != null) {
            order.setTaxBreakup(request.getTaxBreakup());
        }
        if (request.getExpectedDelivery() != null) {
            order.setExpectedDelivery(request.getExpectedDelivery());
        }
        if (request.getOrderDate() != null) {
            order.setOrderDate(request.getOrderDate());
        }
        if (request.getAdditionalInfo() != null) {
            order.setAdditionalInfo(request.getAdditionalInfo());
        }

        order.setUpdatedBy(currentUserId);
        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderDTOs.Response updateOrderStatus(Long orderId, OrderDTOs.UpdateStatusRequest request, Long currentUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User entry not found with id: " + currentUserId));

        if(!order.getUpdatedBy().equals(currentUserId)){
            String title = "";
            String message = "";
            switch(request.getStatus()){
                case OrderStatus.DEALER_LEVEL_PENDING:{
                    title = "Order Approved";
                    message = "Order " + order.getOrderNumber() + " approved by ID: " + user.getUsername() + ". Now Pending from Dealer `" +
                            order.getDealer().getName() + "`";
                    break;
                }
                case OrderStatus.CANCELLED: {
                    title = "Order Cancelled";
                    message = "Order " + order.getOrderNumber() + " cancelled by ID: " + user.getUsername() + ".";
                    break;
                }
            }

            notificationService.createForUser(order.getUpdatedBy(), title, message);
        }
        order.setOrderStatus(request.getStatus());
        order.setUpdatedBy(currentUserId);
        order.setActualDelivery((Objects.nonNull(request.getActualDelivery()) && request.getStatus() == OrderStatus.DELIVERED) ?
                request.getActualDelivery() : null);
        return mapToResponse(orderRepository.save(order));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDTOs.Response getOrderById(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
        return mapToResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersByDealerId(Long dealerId) {
        if (!dealerRepository.existsById(dealerId)) {
            throw new ResourceNotFoundException("Dealer not found with id: " + dealerId);
        }
        return orderRepository.findByDealerId(dealerId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByOrderStatus(status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderDTOs.Response processAdminL2Approval(Long orderId, OrderDTOs.ApprovalDecisionRequest request, Long currentUserId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User entry not found with id: " + currentUserId));

        if(!order.getUpdatedBy().equals(currentUserId)){
            String title = "";
            String message = "";
            switch(request.getDecision()){
                case OrderStatus.DEALER_LEVEL_PENDING:{
                    title = "Order Approved";
                    message = "Order " + order.getOrderNumber() + " approved by ID: " + user.getUsername() + ". Now Pending from Dealer `" +
                            order.getDealer().getName() + "`";
                    break;
                }
                case OrderStatus.CANCELLED: {
                    title = "Order Cancelled";
                    message = "Order " + order.getOrderNumber() + " cancelled by ID: " + user.getUsername() + ". Reason: " + request.getRejectionReason();
                    break;
                }
            }

            notificationService.createForUser(order.getUpdatedBy(), title, message);
        }
        order.setOrderStatus(request.getDecision());
        order.setUpdatedBy(currentUserId);
        return mapToResponse(orderRepository.save(order));
    }

    private OrderDTOs.Response mapToResponse(Order order) {
        return OrderDTOs.Response.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .dealerId(order.getDealer().getId())
                .dealerName(order.getDealer().getName())
                .itemId(order.getItem().getId())
                .itemName(order.getItem().getName())
                .orderQuantity(order.getOrderQuantity())
                .unitType(order.getUnitType())
                .unitPrice(order.getUnitPrice())
                .totalPrice(order.getTotalPrice())
                .shipmentPrice(order.getShipmentPrice())
                .taxBreakup(order.getTaxBreakup())
                .orderStatus(order.getOrderStatus())
                .expectedDelivery(order.getExpectedDelivery())
                .actualDelivery(order.getActualDelivery())
                .orderDate(order.getOrderDate())
                .additionalInfo(order.getAdditionalInfo())
                .createdAt(order.getCreatedAt())
                .createdBy(order.getCreatedBy())
                .build();
    }
}