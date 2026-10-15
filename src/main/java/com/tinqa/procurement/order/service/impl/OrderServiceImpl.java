package com.tinqa.procurement.order.service.impl;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.common.util.ReferenceNumberGenerator;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.dealer.repository.DealerRepository;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.entity.OrderStatusHistory;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.mapper.OrderMapper;
import com.tinqa.procurement.order.notification.OrderStatusNotifier;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.repository.OrderStatusHistoryRepository;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.order.workflow.OrderStatusTransitions;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    // No stock entry is being edited when an order's quantity changes
    private static final long NO_STOCK_ENTRY = -1L;

    private final OrderRepository orderRepository;
    private final DealerRepository dealerRepository;
    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final OrderStatusNotifier statusNotifier;
    private final OrderMapper orderMapper;
    private final ReferenceNumberGenerator referenceNumbers;
    private final Clock clock;

    @Override
    @Transactional
    public OrderDTOs.Response createOrder(OrderDTOs.CreateRequest request) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Dealer dealer = findDealer(request.getDealerId());
        Item item = findItem(request.getItemId());
        ensureExpectedDeliveryNotBeforeOrder(request.getOrderDate(), request.getExpectedDelivery());

        Order order = Order.builder()
                .orderNumber(referenceNumbers.orderNumber())
                .dealer(dealer)
                .item(item)
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                // Optional JSON details are stored as {} when not sent (the columns are NOT NULL)
                .taxBreakup(orEmpty(request.getTaxBreakup()))
                .orderStatus(OrderStatus.QUOTATION)
                .expectedDelivery(request.getExpectedDelivery())
                .orderDate(request.getOrderDate())
                .additionalInfo(orEmpty(request.getAdditionalInfo()))
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        order.recalculateTotalPrice();

        Order saved = orderRepository.save(order);
        recordHistory(saved, null, OrderStatus.QUOTATION, currentUserId, null, null);
        return orderMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public OrderDTOs.Response updateOrder(Long orderId, OrderDTOs.UpdateRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        // Locked: stock entries are checked against this order's quantity under the same lock
        Order order = lockOrder(orderId);
        ensureEditable(order, actor);

        if (request.getDealerId() != null && !request.getDealerId().equals(order.getDealer().getId())) {
            order.setDealer(findDealer(request.getDealerId()));
        }
        if (request.getItemId() != null && !request.getItemId().equals(order.getItem().getId())) {
            order.setItem(findItem(request.getItemId()));
        }
        if (request.getOrderQuantity() != null) {
            BigDecimal received = stockRepository.sumRecordedUnitsForOrder(order.getOrderNumber(), NO_STOCK_ENTRY, ApprovalStatus.REJECTED);
            if (request.getOrderQuantity().compareTo(received) < 0) {
                throw ApiErrors.fieldError("orderQuantity", "Order quantity cannot be less than the "
                        + Quantities.plain(received) + " " + order.getUnitType() + " already received into stock");
            }
            order.setOrderQuantity(request.getOrderQuantity());
        }
        if (request.getUnitType() != null) order.setUnitType(request.getUnitType());
        if (request.getUnitPrice() != null) order.setUnitPrice(request.getUnitPrice());
        if (request.getShipmentPrice() != null) order.setShipmentPrice(request.getShipmentPrice());
        if (request.getTaxBreakup() != null) order.setTaxBreakup(request.getTaxBreakup());
        if (request.getExpectedDelivery() != null) order.setExpectedDelivery(request.getExpectedDelivery());
        if (request.getOrderDate() != null) order.setOrderDate(request.getOrderDate());
        if (request.getAdditionalInfo() != null) order.setAdditionalInfo(request.getAdditionalInfo());
        order.recalculateTotalPrice();
        ensureExpectedDeliveryNotBeforeOrder(order.getOrderDate(), order.getExpectedDelivery());

        order.setUpdatedBy(actor.getId());
        return orderMapper.toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderDTOs.Response updateOrderStatus(Long orderId, OrderDTOs.UpdateStatusRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        // Locked: a concurrent change waits, then sees the new status and is rejected if its move no longer applies
        Order order = lockOrder(orderId);
        OrderStatus from = order.getOrderStatus();
        OrderStatus to = request.getStatus();

        if (from == OrderStatus.PENDING || to == OrderStatus.DEALER_LEVEL_PENDING) {
            throw invalidTransition(order, to, " Pending orders are approved or rejected through the approval decision.");
        }
        Permission needed = OrderStatusTransitions.permissionForStatusChange(from, to)
                .orElseThrow(() -> invalidTransition(order, to, ""));
        currentAccess.require(needed, "move order " + order.getOrderNumber() + " from " + from.label() + " to " + to.label());

        String reason = OptionalText.blankToNull(request.getReason());
        if (reason == null && OrderStatusTransitions.REASON_REQUIRED.contains(to)) {
            throw ApiErrors.fieldError("reason", "A reason is required to move an order to " + to.label() + ".");
        }

        LocalDate actualDelivery = null;
        if (to == OrderStatus.DELIVERED) {
            actualDelivery = validatedActualDelivery(order, request.getActualDelivery());
            order.setActualDelivery(actualDelivery);
        }

        // Stock is created from delivered orders; how a return adjusts that stock is not decided yet
        if (to == OrderStatus.RETURN_REQUESTED) {
            stockRepository.findFirstByOrderOrderNumber(order.getOrderNumber()).ifPresent(stock -> {
                throw ApiErrors.conflict(ErrorCodes.ORDER_STOCKED, "Order " + order.getOrderNumber() + " has stock entry "
                        + stock.getStockIdentityNumber() + ", so a return can't be requested for it yet.");
            });
        }

        return changeStatus(order, from, to, actor, reason, actualDelivery);
    }

    @Override
    @Transactional
    public OrderDTOs.Response processAdminL2Approval(Long orderId, OrderDTOs.ApprovalDecisionRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        Order order = lockOrder(orderId);
        OrderStatus to = request.getDecision();

        currentAccess.require(OrderStatusTransitions.APPROVAL_PERMISSION, "approve or reject an order");
        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw invalidTransition(order, to, " Only pending orders can be approved or rejected.");
        }
        if (!OrderStatusTransitions.APPROVAL_DECISIONS.contains(to)) {
            throw invalidTransition(order, to, " An approval decision is either Dealer level pending (approve) or Cancelled (reject).");
        }

        String reason = null;
        if (to == OrderStatus.CANCELLED) {
            reason = OptionalText.blankToNull(request.getRejectionReason());
            if (reason == null) {
                throw ApiErrors.fieldError("rejectionReason", "Rejection reason is required to reject an order.");
            }
        }

        return changeStatus(order, OrderStatus.PENDING, to, actor, reason, null);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDTOs.Response getOrderById(Long orderId) {
        return orderMapper.toResponse(orderRepository.findById(orderId).orElseThrow(() -> orderNotFound(orderId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getAllOrders() {
        return toResponses(orderRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersByDealerId(Long dealerId) {
        if (!dealerRepository.existsById(dealerId)) {
            throw dealerNotFound(dealerId);
        }
        return toResponses(orderRepository.findByDealerId(dealerId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status) {
        return toResponses(orderRepository.findByOrderStatus(status));
    }

    // Notifies the previous updater, then records the move in the order and its history
    private OrderDTOs.Response changeStatus(Order order, OrderStatus from, OrderStatus to, User actor, String reason,
                                            LocalDate actualDelivery) {
        statusNotifier.notifyStatusChange(order, to, actor, reason, actualDelivery);
        order.setOrderStatus(to);
        order.setUpdatedBy(actor.getId());
        Order saved = orderRepository.save(order);
        recordHistory(saved, from, to, actor.getId(), reason, actualDelivery);
        return orderMapper.toResponse(saved);
    }

    private LocalDate validatedActualDelivery(Order order, LocalDate actualDelivery) {
        if (actualDelivery == null) {
            throw ApiErrors.fieldError("actualDelivery", "Actual delivery date is required to mark an order as delivered.");
        }
        if (actualDelivery.isAfter(LocalDate.now(clock))) {
            throw ApiErrors.fieldError("actualDelivery", "Actual delivery date cannot be in the future");
        }
        if (actualDelivery.isBefore(order.getOrderDate())) {
            throw ApiErrors.fieldError("actualDelivery", "Actual delivery date cannot be before the order date (" + order.getOrderDate() + ")");
        }
        return actualDelivery;
    }

    // Edits are allowed while the order is a quotation, or by an order approver while it waits for approval
    private void ensureEditable(Order order, User actor) {
        OrderStatus status = order.getOrderStatus();
        if (status == OrderStatus.PENDING) {
            currentAccess.require(OrderStatusTransitions.APPROVAL_PERMISSION, "edit an order that is waiting for approval");
        } else if (status != OrderStatus.QUOTATION) {
            throw ApiErrors.conflict(ErrorCodes.ORDER_NOT_EDITABLE, "Order " + order.getOrderNumber() + " can't be edited because it is "
                    + status.label() + ". Orders can only be edited as a quotation, or by Admin L2 while pending approval.");
        }
    }

    private static ApiException invalidTransition(Order order, OrderStatus to, String hint) {
        return new ApiException("Order " + order.getOrderNumber() + " can't move from " + order.getOrderStatus().label()
                + " to " + to.label() + "." + hint, ErrorCodes.INVALID_STATUS_TRANSITION, HttpStatus.CONFLICT);
    }

    private void ensureExpectedDeliveryNotBeforeOrder(LocalDate orderDate, LocalDate expectedDelivery) {
        if (orderDate != null && expectedDelivery != null && expectedDelivery.isBefore(orderDate)) {
            throw ApiErrors.fieldError("expectedDelivery", "Expected delivery date cannot be before the order date (" + orderDate + ")");
        }
    }

    private void recordHistory(Order order, OrderStatus from, OrderStatus to, Long actorId, String reason, LocalDate actualDelivery) {
        statusHistoryRepository.save(OrderStatusHistory.builder()
                .orderId(order.getId())
                .fromStatus(from)
                .toStatus(to)
                .changedBy(actorId)
                .reason(reason)
                .actualDelivery(actualDelivery)
                .changedAt(clock.instant())
                .build());
    }

    private List<OrderDTOs.Response> toResponses(List<Order> orders) {
        return orders.stream().map(orderMapper::toResponse).toList();
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> orderNotFound(orderId));
    }

    private Dealer findDealer(Long dealerId) {
        return dealerRepository.findById(dealerId).orElseThrow(() -> dealerNotFound(dealerId));
    }

    private Item findItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value != null ? value : new LinkedHashMap<>();
    }

    private static ResourceNotFoundException orderNotFound(Long orderId) {
        return new ResourceNotFoundException("Order not found with id: " + orderId);
    }

    private static ResourceNotFoundException dealerNotFound(Long dealerId) {
        return new ResourceNotFoundException("Dealer not found with id: " + dealerId);
    }
}
