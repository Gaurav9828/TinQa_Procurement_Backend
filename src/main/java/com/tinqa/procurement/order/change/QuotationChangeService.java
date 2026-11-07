package com.tinqa.procurement.order.change;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.service.OrderEditor;
import com.tinqa.procurement.order.payment.PaymentPlan;
import com.tinqa.procurement.order.tax.QuotationTax;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dealers' changes to their own quotations. A dealer never edits a quotation directly: each change is a request that a
 * Dealer Manager of either level (DEALER_QUOTATION_CHANGE_APPROVE) approves or rejects. While a request is open the
 * quotation is frozen for everyone, so what the approver sees is exactly what will change.
 */
@Service
@RequiredArgsConstructor
public class QuotationChangeService {

    /** additionalInfo key holding the dealer's own remarks on a quotation */
    public static final String DEALER_NOTE = "dealerNote";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final OrderChangeRequestRepository changeRequestRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderEditor orderEditor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Records a dealer's proposed change to a quotation. The caller has locked the order and checked it is the
     * dealer's own.
     */
    @Transactional
    public OrderChangeDTOs.Response request(Order order, User dealerUser, OrderChangeDTOs.Terms proposed, String note) {
        if (order.getOrderStatus() != OrderStatus.QUOTATION) {
            throw ApiErrors.conflict(ErrorCodes.ORDER_NOT_EDITABLE, "Order " + order.getOrderNumber() + " is "
                    + order.getOrderStatus().label() + ", so it can no longer be changed. Only quotations can be changed.");
        }
        ensureNotPending(order);

        OrderChangeDTOs.Terms current = currentTerms(order);
        OrderChangeDTOs.Terms changes = new OrderChangeDTOs.Terms();
        OrderChangeDTOs.Terms previous = new OrderChangeDTOs.Terms();
        if (proposed.getItemId() != null && !proposed.getItemId().equals(current.getItemId())) {
            Item item = orderEditor.findItem(proposed.getItemId());
            if (!Boolean.TRUE.equals(item.getIsActive())) {
                throw ApiErrors.fieldError("itemId", "Item " + item.getName() + " is no longer available.");
            }
            changes.setItemId(item.getId());
            changes.setItemName(item.getName());
            previous.setItemId(current.getItemId());
            previous.setItemName(current.getItemName());
        }
        if (differs(proposed.getOrderQuantity(), current.getOrderQuantity())) {
            changes.setOrderQuantity(proposed.getOrderQuantity());
            previous.setOrderQuantity(current.getOrderQuantity());
        }
        String unitType = OptionalText.blankToNull(proposed.getUnitType());
        if (unitType != null && !unitType.equals(current.getUnitType())) {
            changes.setUnitType(unitType);
            previous.setUnitType(current.getUnitType());
        }
        if (differs(proposed.getUnitPrice(), current.getUnitPrice())) {
            changes.setUnitPrice(proposed.getUnitPrice());
            previous.setUnitPrice(current.getUnitPrice());
        }
        if (differs(proposed.getShipmentPrice(), current.getShipmentPrice())) {
            changes.setShipmentPrice(proposed.getShipmentPrice());
            previous.setShipmentPrice(current.getShipmentPrice());
        }
        if (proposed.getExpectedDelivery() != null && !proposed.getExpectedDelivery().equals(current.getExpectedDelivery())) {
            OrderEditor.ensureExpectedDeliveryNotBeforeOrder(order.getOrderDate(), proposed.getExpectedDelivery());
            changes.setExpectedDelivery(proposed.getExpectedDelivery());
            previous.setExpectedDelivery(current.getExpectedDelivery());
        }
        if (differs(proposed.getAdvancePercent(), current.getAdvancePercent())) {
            changes.setAdvancePercent(proposed.getAdvancePercent());
            previous.setAdvancePercent(current.getAdvancePercent());
        }
        if (differs(proposed.getMidtermPercent(), current.getMidtermPercent())) {
            changes.setMidtermPercent(proposed.getMidtermPercent());
            previous.setMidtermPercent(current.getMidtermPercent());
        }
        if (changes.getAdvancePercent() != null || changes.getMidtermPercent() != null) {
            // The plan as it would be once this change is applied
            PaymentPlan.validate(Objects.requireNonNullElse(changes.getAdvancePercent(), current.getAdvancePercent()),
                    Objects.requireNonNullElse(changes.getMidtermPercent(), current.getMidtermPercent()));
        }
        if (proposed.getExpectedPaymentMethod() != null && proposed.getExpectedPaymentMethod() != current.getExpectedPaymentMethod()) {
            changes.setExpectedPaymentMethod(proposed.getExpectedPaymentMethod());
            previous.setExpectedPaymentMethod(current.getExpectedPaymentMethod());
        }
        String proposedNote = proposed.getNote() == null ? null : proposed.getNote().trim();
        if (proposedNote != null && !proposedNote.equals(Objects.requireNonNullElse(current.getNote(), ""))) {
            changes.setNote(proposedNote);
            previous.setNote(current.getNote());
        }

        if (proposed.getTaxBreakup() != null) {
            // Checked against the taxable value the quotation would have once this change is applied
            BigDecimal taxable = Objects.requireNonNullElse(changes.getUnitPrice(), current.getUnitPrice())
                    .multiply(Objects.requireNonNullElse(changes.getOrderQuantity(), current.getOrderQuantity()))
                    .add(Objects.requireNonNullElse(changes.getShipmentPrice(), current.getShipmentPrice()));
            Map<String, Object> proposedTax = QuotationTax.normalise(proposed.getTaxBreakup(), taxable);
            Map<String, Object> currentTax = Objects.requireNonNullElse(order.getTaxBreakup(), Map.of());
            if (!QuotationTax.sameTerms(proposedTax, QuotationTax.recalculate(currentTax, taxable))) {
                changes.setTaxBreakup(proposedTax);
                previous.setTaxBreakup(currentTax);
            }
        }

        Map<String, Object> changeMap = toMap(changes);
        if (changeMap.isEmpty()) {
            throw ApiErrors.conflict(ErrorCodes.ORDER_CHANGE_EMPTY, "Nothing would change: the values you sent are the quotation's current ones.");
        }

        OrderChangeRequest saved;
        try {
            saved = changeRequestRepository.saveAndFlush(OrderChangeRequest.builder()
                    .orderId(order.getId())
                    .requestedBy(dealerUser.getId())
                    .requestedAt(clock.instant())
                    .changes(changeMap)
                    .previous(toMap(previous))
                    .note(OptionalText.blankToNull(note))
                    .status(OrderChangeStatus.PENDING)
                    .build());
        } catch (DataIntegrityViolationException exception) {
            // Another request for this order was opened at the same moment
            throw pendingConflict(order);
        }

        notificationService.createForPermission(Permission.DEALER_QUOTATION_CHANGE_APPROVE, dealerUser.getId(),
                "Dealer quotation change awaiting approval",
                "Dealer " + order.getDealer().getName() + " asked to change quotation " + order.getOrderNumber() + " ("
                        + describe(changes) + "). The quotation stays as it is until someone approves the change.",
                NotificationLinks.APPROVALS);
        return toResponse(saved, order, users(List.of(saved)));
    }

    /** The dealer takes back their own open request. */
    @Transactional
    public OrderChangeDTOs.Response withdraw(Long requestId, Predicate<Order> ownedByDealer, User dealerUser) {
        OrderChangeRequest request = changeRequestRepository.findByIdForUpdate(requestId).orElseThrow(() -> requestNotFound(requestId));
        Order order = orderRepository.findById(request.getOrderId()).orElseThrow(() -> requestNotFound(requestId));
        // Someone else's request looks the same as one that doesn't exist
        if (!ownedByDealer.test(order)) {
            throw requestNotFound(requestId);
        }
        ensureStillPending(request);
        request.setStatus(OrderChangeStatus.WITHDRAWN);
        request.setDecidedBy(dealerUser.getId());
        request.setDecidedAt(clock.instant());
        OrderChangeRequest saved = changeRequestRepository.save(request);
        return toResponse(saved, order, users(List.of(saved)));
    }

    @Transactional
    public OrderChangeDTOs.Response decide(Long requestId, OrderChangeDTOs.DecisionRequest decision, User approver) {
        OrderChangeRequest request = changeRequestRepository.findByIdForUpdate(requestId).orElseThrow(() -> requestNotFound(requestId));
        ensureStillPending(request);
        Order order = orderRepository.findByIdForUpdate(request.getOrderId()).orElseThrow(() -> requestNotFound(requestId));
        boolean approved = decision.getDecision() == OrderChangeDTOs.Decision.APPROVED;
        String reason = OptionalText.blankToNull(decision.getReason());
        if (!approved && reason == null) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject a dealer's change.");
        }

        if (approved) {
            if (order.getOrderStatus() != OrderStatus.QUOTATION) {
                throw ApiErrors.conflict(ErrorCodes.ORDER_NOT_EDITABLE, "Order " + order.getOrderNumber() + " is "
                        + order.getOrderStatus().label() + ", so the dealer's change can no longer be applied. Reject it instead.");
            }
            OrderChangeDTOs.Terms changes = terms(request.getChanges());
            orderEditor.apply(order, OrderDTOs.UpdateRequest.builder()
                    .itemId(changes.getItemId())
                    .orderQuantity(changes.getOrderQuantity())
                    .unitType(changes.getUnitType())
                    .unitPrice(changes.getUnitPrice())
                    .shipmentPrice(changes.getShipmentPrice())
                    .taxBreakup(changes.getTaxBreakup())
                    .expectedDelivery(changes.getExpectedDelivery())
                    .advancePercent(changes.getAdvancePercent())
                    .midtermPercent(changes.getMidtermPercent())
                    .expectedPaymentMethod(changes.getExpectedPaymentMethod())
                    .build());
            if (changes.getNote() != null) {
                Map<String, Object> info = new LinkedHashMap<>(Objects.requireNonNullElse(order.getAdditionalInfo(), Map.of()));
                info.put(DEALER_NOTE, changes.getNote());
                order.setAdditionalInfo(info);
            }
            order.setUpdatedBy(approver.getId());
            orderRepository.save(order);
        }

        request.setStatus(approved ? OrderChangeStatus.APPROVED : OrderChangeStatus.REJECTED);
        request.setDecidedBy(approver.getId());
        request.setDecidedAt(clock.instant());
        request.setDecisionReason(reason);
        OrderChangeRequest saved = changeRequestRepository.save(request);

        notificationService.createForUser(request.getRequestedBy(),
                approved ? "Quotation change approved" : "Quotation change rejected",
                "Your change to quotation " + order.getOrderNumber() + " was " + (approved ? "approved and applied." : "rejected.")
                        + (reason != null ? " Reason: " + reason : ""), NotificationLinks.DEALER_QUOTATIONS);
        return toResponse(saved, order, users(List.of(saved)));
    }

    @Transactional(readOnly = true)
    public List<OrderChangeDTOs.Response> list(OrderChangeStatus status) {
        List<OrderChangeRequest> requests = status == null
                ? changeRequestRepository.findAll()
                : changeRequestRepository.findByStatusOrderByRequestedAtAsc(status);
        Map<Long, Order> orders = orderRepository.findAllById(requests.stream().map(OrderChangeRequest::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<Long, User> users = users(requests);
        return requests.stream().map(request -> toResponse(request, orders.get(request.getOrderId()), users)).toList();
    }

    /** Every request made for one order, newest first. */
    @Transactional(readOnly = true)
    public List<OrderChangeDTOs.Response> forOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
        return forOrder(order);
    }

    /** Same, for an order the caller already loaded in its transaction. */
    @Transactional(readOnly = true)
    public List<OrderChangeDTOs.Response> forOrder(Order order) {
        List<OrderChangeRequest> requests = changeRequestRepository.findByOrderIdOrderByRequestedAtDesc(order.getId());
        Map<Long, User> users = users(requests);
        return requests.stream().map(request -> toResponse(request, order, users)).toList();
    }

    /** The open request of each of these orders that has one, by order id. */
    @Transactional(readOnly = true)
    public Map<Long, OrderChangeDTOs.Response> pendingByOrder(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        Map<Long, Order> byId = orders.stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        List<OrderChangeRequest> requests = changeRequestRepository.findByOrderIdInAndStatus(byId.keySet(), OrderChangeStatus.PENDING);
        Map<Long, User> users = users(requests);
        return requests.stream().collect(Collectors.toMap(OrderChangeRequest::getOrderId,
                request -> toResponse(request, byId.get(request.getOrderId()), users)));
    }

    @Transactional(readOnly = true)
    public Set<Long> ordersWithPendingChange(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Set.of();
        }
        return changeRequestRepository.findByOrderIdInAndStatus(orderIds, OrderChangeStatus.PENDING).stream()
                .map(OrderChangeRequest::getOrderId).collect(Collectors.toSet());
    }

    /** Refuses to touch a quotation while a dealer's change to it waits for a decision. */
    public void ensureNotPending(Order order) {
        if (changeRequestRepository.existsByOrderIdAndStatus(order.getId(), OrderChangeStatus.PENDING)) {
            throw pendingConflict(order);
        }
    }

    private static ApiException pendingConflict(Order order) {
        return ApiErrors.conflict(ErrorCodes.ORDER_CHANGE_PENDING, "Quotation " + order.getOrderNumber()
                + " has a dealer's change waiting for approval. It can't be changed until that change is approved, rejected or withdrawn.");
    }

    private static void ensureStillPending(OrderChangeRequest request) {
        if (request.getStatus() != OrderChangeStatus.PENDING) {
            throw ApiErrors.conflict(ErrorCodes.ORDER_CHANGE_NOT_PENDING,
                    "This change was already " + request.getStatus().name().toLowerCase() + ".");
        }
    }

    private OrderChangeDTOs.Terms currentTerms(Order order) {
        Object note = order.getAdditionalInfo() == null ? null : order.getAdditionalInfo().get(DEALER_NOTE);
        return OrderChangeDTOs.Terms.builder()
                .itemId(order.getItem().getId())
                .itemName(order.getItem().getName())
                .orderQuantity(order.getOrderQuantity())
                .unitType(order.getUnitType())
                .unitPrice(order.getUnitPrice())
                .shipmentPrice(order.getShipmentPrice())
                .taxBreakup(order.getTaxBreakup())
                .expectedDelivery(order.getExpectedDelivery())
                .advancePercent(order.getAdvancePercent())
                .midtermPercent(order.getMidtermPercent())
                .expectedPaymentMethod(order.getExpectedPaymentMethod())
                .note(note instanceof String text ? text : null)
                .build();
    }

    private OrderChangeDTOs.Response toResponse(OrderChangeRequest request, Order order, Map<Long, User> users) {
        OrderChangeDTOs.Terms changes = terms(request.getChanges());
        OrderChangeDTOs.Terms previous = terms(request.getPrevious());
        BigDecimal proposedTotal = null;
        if (order != null && request.getStatus() == OrderChangeStatus.PENDING) {
            BigDecimal quantity = Objects.requireNonNullElse(changes.getOrderQuantity(), order.getOrderQuantity());
            BigDecimal unitPrice = Objects.requireNonNullElse(changes.getUnitPrice(), order.getUnitPrice());
            BigDecimal shipment = Objects.requireNonNullElse(changes.getShipmentPrice(), order.getShipmentPrice());
            proposedTotal = unitPrice.multiply(quantity).add(shipment);
        }
        return OrderChangeDTOs.Response.builder()
                .id(request.getId())
                .orderId(request.getOrderId())
                .orderNumber(order != null ? order.getOrderNumber() : null)
                .orderStatus(order != null ? order.getOrderStatus() : null)
                .dealerId(order != null ? order.getDealer().getId() : null)
                .dealerName(order != null ? order.getDealer().getName() : null)
                .changes(changes)
                .previous(previous)
                .proposedTotalPrice(proposedTotal)
                .currentTotalPrice(order != null ? order.getTotalPrice() : null)
                .note(request.getNote())
                .status(request.getStatus())
                .requestedByUsername(usernameOf(users, request.getRequestedBy()))
                .requestedAt(request.getRequestedAt())
                .decidedByUsername(usernameOf(users, request.getDecidedBy()))
                .decidedAt(request.getDecidedAt())
                .decisionReason(request.getDecisionReason())
                .build();
    }

    private Map<Long, User> users(List<OrderChangeRequest> requests) {
        Set<Long> ids = requests.stream().flatMap(request -> Stream.of(request.getRequestedBy(), request.getDecidedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static String usernameOf(Map<Long, User> users, Long userId) {
        User user = userId == null ? null : users.get(userId);
        return user != null ? user.getUsername() : null;
    }

    // Only the fields that are set, so a stored request shows exactly what was proposed
    private Map<String, Object> toMap(OrderChangeDTOs.Terms terms) {
        Map<String, Object> map = new LinkedHashMap<>(objectMapper.convertValue(terms, MAP));
        map.values().removeIf(Objects::isNull);
        return map;
    }

    private OrderChangeDTOs.Terms terms(Map<String, Object> map) {
        return objectMapper.convertValue(Objects.requireNonNullElse(map, Map.of()), OrderChangeDTOs.Terms.class);
    }

    private static boolean differs(BigDecimal proposed, BigDecimal current) {
        return proposed != null && (current == null || proposed.compareTo(current) != 0);
    }

    // "quantity, unit price" etc. for the approvers' notification
    private static String describe(OrderChangeDTOs.Terms changes) {
        Stream.Builder<String> fields = Stream.builder();
        if (changes.getItemId() != null) fields.add("item");
        if (changes.getOrderQuantity() != null) fields.add("quantity");
        if (changes.getUnitType() != null) fields.add("unit");
        if (changes.getUnitPrice() != null) fields.add("unit price");
        if (changes.getShipmentPrice() != null) fields.add("shipment price");
        if (changes.getTaxBreakup() != null) fields.add("GST breakup");
        if (changes.getExpectedDelivery() != null) fields.add("expected delivery");
        if (changes.getAdvancePercent() != null || changes.getMidtermPercent() != null) fields.add("payment plan");
        if (changes.getExpectedPaymentMethod() != null) fields.add("payment method");
        if (changes.getNote() != null) fields.add("remarks");
        return fields.build().collect(Collectors.joining(", "));
    }

    private static ResourceNotFoundException requestNotFound(Long requestId) {
        return new ResourceNotFoundException("Quotation change not found with id: " + requestId);
    }
}
