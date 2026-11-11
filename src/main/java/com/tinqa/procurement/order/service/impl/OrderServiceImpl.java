package com.tinqa.procurement.order.service.impl;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.common.util.ReferenceNumberGenerator;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.supplier.repository.SupplierRepository;
import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.order.change.QuotationChangeService;
import com.tinqa.procurement.order.change.SupplierQuotedTerms;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.entity.OrderStatusHistory;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.invoice.OrderInvoiceService;
import com.tinqa.procurement.order.mapper.OrderMapper;
import com.tinqa.procurement.order.notification.OrderStatusNotifier;
import com.tinqa.procurement.order.payment.SupplierPaymentService;
import com.tinqa.procurement.order.payment.PaymentPlan;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.repository.OrderStatusHistoryRepository;
import com.tinqa.procurement.order.service.OrderEditor;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.order.tax.QuotationTax;
import com.tinqa.procurement.order.workflow.OrderStatusTransitions;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final StockRepository stockRepository;
    private final UserRepository userRepository;
    private final OrderEditor orderEditor;
    private final QuotationChangeService quotationChangeService;
    private final OrderInvoiceService orderInvoiceService;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final PurchasePostingRecorder purchasePostings;
    private final SupplierPaymentService supplierPayments;
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
        Supplier supplier = orderEditor.findSupplier(request.getSupplierId());
        Item item = orderEditor.findItem(request.getItemId());
        OrderEditor.ensureExpectedDeliveryNotBeforeOrder(request.getOrderDate(), request.getExpectedDelivery());
        // No midterm part: only an advance and the final payment
        BigDecimal midtermPercent = request.getMidtermPercent() != null ? request.getMidtermPercent() : BigDecimal.ZERO;
        PaymentPlan.validate(request.getAdvancePercent(), midtermPercent);

        Order order = Order.builder()
                .orderNumber(referenceNumbers.orderNumber())
                .supplier(supplier)
                .item(item)
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                .advancePercent(request.getAdvancePercent())
                .midtermPercent(midtermPercent)
                .expectedPaymentMethod(request.getExpectedPaymentMethod())
                // Optional JSON details are stored as {} when not sent (the columns are NOT NULL)
                .orderStatus(OrderStatus.QUOTATION)
                .expectedDelivery(request.getExpectedDelivery())
                .orderDate(request.getOrderDate())
                .additionalInfo(OrderEditor.justifyPrice(item, request.getUnitPrice(), orEmpty(request.getAdditionalInfo()), null))
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        order.recalculateTotalPrice();
        order.setTaxBreakup(QuotationTax.normalise(request.getTaxBreakup(), order.getTotalPrice()));

        Order saved = orderRepository.save(order);
        recordHistory(saved, null, OrderStatus.QUOTATION, currentUserId, null, null);
        return respond(saved);
    }

    @Override
    @Transactional
    public OrderDTOs.Response updateOrder(Long orderId, OrderDTOs.UpdateRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        // Locked: stock entries are checked against this order's quantity under the same lock
        Order order = lockOrder(orderId);
        ensureEditable(order, actor);
        // A supplier's change waiting for approval was made against the current terms: decide it first
        quotationChangeService.ensureNotPending(order);
        orderEditor.apply(order, request);

        order.setUpdatedBy(actor.getId());
        return respond(orderRepository.save(order));
    }

    @Override
    @Transactional
    public OrderDTOs.Response updateOrderStatus(Long orderId, OrderDTOs.UpdateStatusRequest request) {
        User actor = currentUserProvider.getCurrentUser();
        // Locked: a concurrent change waits, then sees the new status and is rejected if its move no longer applies
        Order order = lockOrder(orderId);
        OrderStatus from = order.getOrderStatus();
        OrderStatus to = request.getStatus();

        if (from == OrderStatus.PENDING || to == OrderStatus.SUPPLIER_LEVEL_PENDING) {
            throw invalidTransition(order, to, " Pending orders are approved or rejected through the approval decision.");
        }
        Permission needed = OrderStatusTransitions.permissionForStatusChange(from, to)
                .orElseThrow(() -> invalidTransition(order, to, ""));
        currentAccess.require(needed, "move order " + order.getOrderNumber() + " from " + from.label() + " to " + to.label());
        // Submitting or cancelling a quotation would leave a supplier's waiting change undecided
        if (from == OrderStatus.QUOTATION) {
            quotationChangeService.ensureNotPending(order);
        }
        // Staff may confirm for a supplier, but not agree on their behalf to changes TinQa made to what they quoted
        if (from == OrderStatus.SUPPLIER_LEVEL_PENDING && to == OrderStatus.CONFIRMED) {
            List<SupplierQuotedTerms.TermChange> changed = SupplierQuotedTerms.changes(order);
            if (!changed.isEmpty()) {
                throw ApiErrors.conflict(ErrorCodes.ORDER_NEEDS_SUPPLIER_CONSENT, "TinQa changed what the supplier quoted on order "
                        + order.getOrderNumber() + " (" + SupplierQuotedTerms.labels(changed)
                        + "), so only the supplier can accept it, in their login, after seeing the changes.");
            }
        }

        String reason = OptionalText.blankToNull(request.getReason());
        if (reason == null && OrderStatusTransitions.REASON_REQUIRED.contains(to)) {
            throw ApiErrors.fieldError("reason", "A reason is required to move an order to " + to.label() + ".");
        }

        LocalDate actualDelivery = null;
        if (to == OrderStatus.DELIVERED) {
            actualDelivery = validatedActualDelivery(order, request.getActualDelivery());
            // The supplier's invoice must match the quotation, or an approver accepts it with a reason
            String invoiceNote = orderInvoiceService.acceptForDelivery(order, request.getInvoiceOverrideReason(), actor);
            if (invoiceNote != null) {
                reason = reason == null ? invoiceNote : reason + " · " + invoiceNote;
            }
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
            throw invalidTransition(order, to, " An approval decision is either Supplier level pending (approve) or Cancelled (reject).");
        }
        // Maker-checker: whoever raised the order, or last edited it, cannot also approve or reject it
        if (actor.getId().equals(order.getCreatedBy()) || actor.getId().equals(order.getUpdatedBy())) {
            throw ApiErrors.forbidden(ErrorCodes.ORDER_APPROVAL_SELF_DECISION,
                    "You raised or last edited order " + order.getOrderNumber() + ", so another Admin L2 has to approve or reject it.");
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
    @Transactional
    public OrderDTOs.Response processSupplierResponse(Order order, boolean accepted, String reason) {
        User actor = currentUserProvider.getCurrentUser();
        OrderStatus to = accepted ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED;
        if (order.getOrderStatus() != OrderStatus.SUPPLIER_LEVEL_PENDING) {
            throw invalidTransition(order, to, " Only orders awaiting your confirmation can be accepted or rejected.");
        }
        String rejectionReason = OptionalText.blankToNull(reason);
        if (!accepted && rejectionReason == null) {
            throw ApiErrors.fieldError("reason", "A reason is required to reject an order.");
        }
        // On accepting, the reason is what the supplier agreed to (TinQa's changes to their quotation), if anything
        return changeStatus(order, OrderStatus.SUPPLIER_LEVEL_PENDING, to, actor, accepted ? OptionalText.blankToNull(reason) : rejectionReason, null);
    }

    @Override
    @Transactional
    public OrderDTOs.Response processSupplierShipment(Order order) {
        User actor = currentUserProvider.getCurrentUser();
        if (order.getOrderStatus() != OrderStatus.CONFIRMED) {
            throw invalidTransition(order, OrderStatus.SHIPPED, " Only confirmed orders can be marked as shipped.");
        }
        return changeStatus(order, OrderStatus.CONFIRMED, OrderStatus.SHIPPED, actor, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDTOs.Response getOrderById(Long orderId) {
        return respond(orderRepository.findById(orderId).orElseThrow(() -> orderNotFound(orderId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getAllOrders() {
        return toResponses(orderRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersBySupplierId(Long supplierId) {
        if (!supplierRepository.existsById(supplierId)) {
            throw supplierNotFound(supplierId);
        }
        return toResponses(orderRepository.findBySupplierId(supplierId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersByStatus(OrderStatus status) {
        return toResponses(orderRepository.findByOrderStatus(status));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersByStatuses(Collection<OrderStatus> statuses) {
        return toResponses(orderRepository.findByOrderStatusInOrderByIdAsc(statuses));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDTOs.Response> getOrdersAwaitingStock() {
        return toResponses(orderRepository.findByOrderStatusWithoutStock(OrderStatus.DELIVERED));
    }

    // Notifies the previous updater, then records the move in the order and its history
    private OrderDTOs.Response changeStatus(Order order, OrderStatus from, OrderStatus to, User actor, String reason,
                                            LocalDate actualDelivery) {
        statusNotifier.notifyStatusChange(order, to, actor, reason, actualDelivery);
        order.setOrderStatus(to);
        order.setUpdatedBy(actor.getId());
        Order saved = orderRepository.save(order);
        recordHistory(saved, from, to, actor.getId(), reason, actualDelivery);
        // Purchases are booked from stock, not on delivery; a refund gives back whatever was booked
        if (to == OrderStatus.REFUND_COMPLETED) {
            purchasePostings.refund(saved);
        }
        // Whatever was paid to the supplier for a cancelled or returned order is due back from them
        if (to == OrderStatus.CANCELLED || to == OrderStatus.REFUND_COMPLETED) {
            supplierPayments.reconcile(saved);
        }
        return respond(saved);
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

    private OrderDTOs.Response respond(Order order) {
        return toResponses(List.of(order)).getFirst();
    }

    // Marks quotations a supplier raised themselves, and those with a supplier's change waiting for approval
    private List<OrderDTOs.Response> toResponses(List<Order> orders) {
        Set<Long> pendingChange = quotationChangeService.ordersWithPendingChange(orders.stream().map(Order::getId).toList());
        Set<Long> supplierAccounts = userRepository.findAllById(orders.stream().map(Order::getCreatedBy).collect(Collectors.toSet()))
                .stream().filter(user -> user.getRole() == Role.SUPPLIER).map(User::getId).collect(Collectors.toSet());
        return orders.stream().map(order -> {
            OrderDTOs.Response response = orderMapper.toResponse(order);
            response.setSupplierChangePending(pendingChange.contains(order.getId()));
            response.setRaisedBySupplier(supplierAccounts.contains(order.getCreatedBy()));
            return response;
        }).toList();
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> orderNotFound(orderId));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value != null ? value : new LinkedHashMap<>();
    }

    private static ResourceNotFoundException orderNotFound(Long orderId) {
        return new ResourceNotFoundException("Order not found with id: " + orderId);
    }

    private static ResourceNotFoundException supplierNotFound(Long supplierId) {
        return new ResourceNotFoundException("Supplier not found with id: " + supplierId);
    }
}
