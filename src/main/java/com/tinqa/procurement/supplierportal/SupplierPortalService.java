package com.tinqa.procurement.supplierportal;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.supplier.login.SupplierLoginService;
import com.tinqa.procurement.supplier.repository.SupplierRepository;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.change.OrderChangeDTOs;
import com.tinqa.procurement.order.payment.SupplierPaymentDTOs;
import com.tinqa.procurement.order.payment.SupplierPaymentService;
import com.tinqa.procurement.order.payment.PaymentPlan;
import com.tinqa.procurement.order.change.OrderChangeStatus;
import com.tinqa.procurement.order.change.QuotationChangeService;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.repository.OrderRepository;
import com.tinqa.procurement.order.repository.OrderStatusHistoryRepository;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything a supplier does from their supplier login. The supplier is always the one the signed-in account belongs to,
 * never one named in the request, and another supplier's order looks exactly like one that doesn't exist.
 *
 * <p>A supplier is identified by their email: orders placed against any supplier record with the same email belong to
 * them. So orders raised before the supplier had a login, or against an earlier record of the same supplier, show up as
 * soon as their login is created.
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalService {

    private final SupplierPaymentService supplierPayments;
    private final SupplierLoginService supplierLoginService;
    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OrderService orderService;
    private final QuotationChangeService quotationChangeService;
    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    /** The supplier the signed-in account belongs to; every order with a supplier of the same email is theirs. */
    private record SupplierScope(Supplier supplier) {
        boolean owns(Order order) {
            Supplier orderSupplier = order.getSupplier();
            return orderSupplier.getId().equals(supplier.getId())
                    || (supplier.getEmail() != null && supplier.getEmail().equalsIgnoreCase(orderSupplier.getEmail()));
        }
    }

    @Transactional(readOnly = true)
    public SupplierPortalDTOs.Profile profile() {
        User user = currentUserProvider.getCurrentUser();
        Supplier supplier = currentSupplier(user).supplier();
        return SupplierPortalDTOs.Profile.builder()
                .supplierId(supplier.getId())
                .name(supplier.getName())
                .tradeName(supplier.getTradeName())
                .email(supplier.getEmail())
                .phoneNumber(supplier.getPhoneNumber())
                .alternatePhoneNumber(supplier.getAlternatePhoneNumber())
                .street(supplier.getStreet())
                .landmark(supplier.getLandmark())
                .city(supplier.getCity())
                .district(supplier.getDistrict())
                .state(supplier.getState())
                .country(supplier.getCountry())
                .pincode(supplier.getPincode())
                .gstin(supplier.getGstin())
                .panNumber(supplier.getPanNumber())
                .username(user.getUsername())
                .build();
    }

    /** Items a supplier can quote for: the active items in at least one of the supplier's categories. */
    @Transactional(readOnly = true)
    public List<SupplierPortalDTOs.Item> items() {
        Set<Long> categoryIds = categoryIdsOf(currentSupplier(currentUserProvider.getCurrentUser()).supplier());
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        return itemRepository.findActiveInCategories(categoryIds).stream()
                .map(item -> SupplierPortalDTOs.Item.builder()
                        .id(item.getId())
                        .name(item.getName())
                        .brand(item.getBrand())
                        .sku(item.getSku())
                        .unitOfMeasure(item.getUnitOfMeasure())
                        .mrp(item.getMrp())
                        .build())
                .toList();
    }

    /** All of the supplier's quotations and orders, newest first. */
    @Transactional(readOnly = true)
    public List<SupplierPortalDTOs.Order> orders() {
        User user = currentUserProvider.getCurrentUser();
        Supplier supplier = currentSupplier(user).supplier();
        List<Order> found = supplier.getEmail() == null
                ? orderRepository.findBySupplierId(supplier.getId())
                : orderRepository.findBySupplierEmail(supplier.getEmail());
        List<Order> orders = found.stream()
                .sorted(Comparator.comparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Order::getId, Comparator.reverseOrder()))
                .toList();
        Map<Long, OrderChangeDTOs.Response> pending = quotationChangeService.pendingByOrder(orders);
        return orders.stream().map(order -> toDto(order, user, pending.get(order.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public SupplierPortalDTOs.Order order(Long orderId) {
        User user = currentUserProvider.getCurrentUser();
        Order order = ownOrder(orderId, currentSupplier(user), false);
        List<OrderChangeDTOs.Response> changes = quotationChangeService.forOrder(order);
        OrderChangeDTOs.Response pending = changes.stream()
                .filter(change -> change.getStatus() == OrderChangeStatus.PENDING).findFirst().orElse(null);
        SupplierPortalDTOs.Order dto = toDto(order, user, pending);
        dto.setChanges(changes.stream().map(SupplierPortalDTOs.Change::of).toList());
        dto.setHistory(statusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()).stream()
                .map(step -> SupplierPortalDTOs.StatusStep.builder()
                        .fromStatus(step.getFromStatus())
                        .toStatus(step.getToStatus())
                        .changedAt(step.getChangedAt())
                        .reason(step.getReason())
                        .build())
                .toList());
        return dto;
    }

    /** What the supplier was paid for one of their orders, and any refund they owe (confirmed entries only). */
    @Transactional(readOnly = true)
    public SupplierPaymentDTOs.OrderPayments payments(Long orderId) {
        User user = currentUserProvider.getCurrentUser();
        return supplierPayments.forSupplier(ownOrder(orderId, currentSupplier(user), false));
    }

    /** A new quotation from the supplier. Like any quotation it only becomes an order once staff submit and approve it. */
    @Transactional
    public SupplierPortalDTOs.Order createQuotation(SupplierPortalDTOs.QuotationRequest request) {
        User user = currentUserProvider.getCurrentUser();
        Supplier supplier = currentSupplier(user).supplier();
        Long supplierId = supplier.getId();
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> ApiErrors.fieldError("itemId", "Item not found with id: " + request.getItemId()));
        if (!Boolean.TRUE.equals(item.getIsActive())) {
            throw ApiErrors.fieldError("itemId", "Item " + item.getName() + " is no longer available.");
        }
        requireInSupplierCategories(supplier, item);
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> info = new LinkedHashMap<>();
        String note = OptionalText.blankToNull(request.getNote());
        if (note != null) {
            info.put(QuotationChangeService.SUPPLIER_NOTE, note);
        }
        String priceChangeReason = OptionalText.blankToNull(request.getPriceChangeReason());
        if (priceChangeReason != null) {
            info.put(OrderDTOs.PRICE_CHANGE_REASON, priceChangeReason);
        }

        OrderDTOs.Response created = orderService.createOrder(OrderDTOs.CreateRequest.builder()
                .supplierId(supplierId)
                .itemId(item.getId())
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType().trim())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                .taxBreakup(request.getTaxBreakup())
                .expectedDelivery(request.getExpectedDelivery())
                .advancePercent(request.getAdvancePercent())
                .midtermPercent(request.getMidtermPercent())
                .expectedPaymentMethod(request.getExpectedPaymentMethod())
                .orderDate(today)
                .additionalInfo(info)
                .build());

        Order order = orderRepository.findById(created.getId()).orElseThrow();
        notificationService.createForPermission(Permission.PROCUREMENT_ORDER_UPDATE_STATUS, user.getId(), "New quotation from a supplier",
                "Supplier " + order.getSupplier().getName() + " raised quotation " + order.getOrderNumber() + " for "
                        + item.getName() + ". Review it and submit it for approval when it is ready.", NotificationLinks.ORDERS);
        return toDto(order, user, null);
    }

    /** Asks for a change to one of the supplier's quotations; nothing changes until a Supplier Manager approves it. */
    @Transactional
    public SupplierPortalDTOs.Change requestChange(Long orderId, SupplierPortalDTOs.ChangeRequest request) {
        User user = currentUserProvider.getCurrentUser();
        SupplierScope scope = currentSupplier(user);
        Order order = ownOrder(orderId, scope, true);
        // Switching to another item: it has to be one the supplier deals in (an unknown item is reported further on)
        Long itemId = request.getItemId();
        if (itemId != null && !itemId.equals(order.getItem().getId())) {
            itemRepository.findById(itemId).ifPresent(item -> requireInSupplierCategories(scope.supplier(), item));
        }
        OrderChangeDTOs.Terms proposed = OrderChangeDTOs.Terms.builder()
                .itemId(request.getItemId())
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                .taxBreakup(request.getTaxBreakup())
                .expectedDelivery(request.getExpectedDelivery())
                .advancePercent(request.getAdvancePercent())
                .midtermPercent(request.getMidtermPercent())
                .expectedPaymentMethod(request.getExpectedPaymentMethod())
                .note(request.getNote())
                .build();
        return SupplierPortalDTOs.Change.of(quotationChangeService.request(order, user, proposed, request.getReason()));
    }

    @Transactional
    public SupplierPortalDTOs.Change withdrawChange(Long requestId) {
        User user = currentUserProvider.getCurrentUser();
        return SupplierPortalDTOs.Change.of(quotationChangeService.withdraw(requestId, currentSupplier(user)::owns, user));
    }

    /** The supplier accepts (confirms) or rejects an order that was approved and is waiting for them. */
    @Transactional
    public SupplierPortalDTOs.Order respond(Long orderId, SupplierPortalDTOs.OrderResponseRequest request) {
        User user = currentUserProvider.getCurrentUser();
        Order order = ownOrder(orderId, currentSupplier(user), true);
        boolean accepted = request.getDecision() == SupplierPortalDTOs.OrderDecision.ACCEPT;
        orderService.processSupplierResponse(order, accepted, request.getReason());
        return toDto(orderRepository.findById(orderId).orElseThrow(), user, null);
    }

    /** The supplier marks one of their confirmed orders as shipped; staff then record its delivery. */
    @Transactional
    public SupplierPortalDTOs.Order ship(Long orderId) {
        User user = currentUserProvider.getCurrentUser();
        Order order = ownOrder(orderId, currentSupplier(user), true);
        // The order's last staff updater hears about it from the status notifier; everyone else who records deliveries here
        Long previousUpdater = order.getUpdatedBy();
        orderService.processSupplierShipment(order);
        notificationService.createForPermission(Permission.PROCUREMENT_ORDER_UPDATE_STATUS,
                previousUpdater != null ? previousUpdater : user.getId(), "Order Shipped by Supplier",
                "Supplier " + order.getSupplier().getName() + " marked order " + order.getOrderNumber() + " ("
                        + order.getItem().getName() + ") as shipped. Mark it as delivered once it arrives.", NotificationLinks.ORDERS);
        return toDto(orderRepository.findById(orderId).orElseThrow(), user, null);
    }

    private static Set<Long> categoryIdsOf(Supplier supplier) {
        return supplier.getCategories().stream().map(Category::getId).collect(Collectors.toSet());
    }

    private static void requireInSupplierCategories(Supplier supplier, Item item) {
        Set<Long> supplierCategories = categoryIdsOf(supplier);
        if (item.getCategories().stream().noneMatch(category -> supplierCategories.contains(category.getId()))) {
            throw ApiErrors.fieldError("itemId", "Item " + item.getName() + " is not in any of your categories.");
        }
    }

    private SupplierScope currentSupplier(User user) {
        Long supplierId = supplierLoginService.supplierIdOf(user.getId()).orElseThrow(SupplierPortalService::notLinked);
        return new SupplierScope(supplierRepository.findById(supplierId).orElseThrow(SupplierPortalService::notLinked));
    }

    private Order ownOrder(Long orderId, SupplierScope scope, boolean lock) {
        return (lock ? orderRepository.findByIdForUpdate(orderId) : orderRepository.findById(orderId))
                .filter(scope::owns)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
    }

    private static SupplierPortalDTOs.Order toDto(Order order, User user, OrderChangeDTOs.Response pending) {
        Object note = order.getAdditionalInfo() == null ? null : order.getAdditionalInfo().get(QuotationChangeService.SUPPLIER_NOTE);
        return SupplierPortalDTOs.Order.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .itemId(order.getItem().getId())
                .itemName(order.getItem().getName())
                .itemSku(order.getItem().getSku())
                .orderQuantity(order.getOrderQuantity())
                .unitType(order.getUnitType())
                .unitPrice(order.getUnitPrice())
                .shipmentPrice(order.getShipmentPrice())
                .totalPrice(order.getTotalPrice())
                .taxBreakup(order.getTaxBreakup())
                .advancePercent(order.getAdvancePercent())
                .midtermPercent(order.getMidtermPercent())
                .finalPercent(PaymentPlan.finalPercent(order.getAdvancePercent(), order.getMidtermPercent()))
                .expectedPaymentMethod(order.getExpectedPaymentMethod())
                .orderStatus(order.getOrderStatus())
                .orderDate(order.getOrderDate())
                .expectedDelivery(order.getExpectedDelivery())
                .actualDelivery(order.getActualDelivery())
                .createdAt(order.getCreatedAt())
                .note(note instanceof String text ? text : null)
                .raisedByYou(user.getId().equals(order.getCreatedBy()))
                .changeable(order.getOrderStatus() == OrderStatus.QUOTATION && pending == null)
                .awaitingYourResponse(order.getOrderStatus() == OrderStatus.SUPPLIER_LEVEL_PENDING)
                .shippable(order.getOrderStatus() == OrderStatus.CONFIRMED)
                .pendingChange(pending != null ? SupplierPortalDTOs.Change.of(pending) : null)
                .build();
    }

    // Only a SUPPLIER account with a supplier login reaches the portal endpoints; anything else is refused
    private static RuntimeException notLinked() {
        return ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "This account is not linked to a supplier.");
    }
}
