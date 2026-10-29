package com.tinqa.procurement.dealerportal;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.dealer.entity.Dealer;
import com.tinqa.procurement.dealer.login.DealerLoginService;
import com.tinqa.procurement.dealer.repository.DealerRepository;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.change.OrderChangeDTOs;
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
 * Everything a dealer does from their dealer login. The dealer is always the one the signed-in account belongs to,
 * never one named in the request, and another dealer's order looks exactly like one that doesn't exist.
 *
 * <p>A dealer is identified by their email: orders placed against any dealer record with the same email belong to
 * them. So orders raised before the dealer had a login, or against an earlier record of the same dealer, show up as
 * soon as their login is created.
 */
@Service
@RequiredArgsConstructor
public class DealerPortalService {

    private final DealerLoginService dealerLoginService;
    private final DealerRepository dealerRepository;
    private final ItemRepository itemRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository statusHistoryRepository;
    private final OrderService orderService;
    private final QuotationChangeService quotationChangeService;
    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    /** The dealer the signed-in account belongs to; every order with a dealer of the same email is theirs. */
    private record DealerScope(Dealer dealer) {
        boolean owns(Order order) {
            Dealer orderDealer = order.getDealer();
            return orderDealer.getId().equals(dealer.getId())
                    || (dealer.getEmail() != null && dealer.getEmail().equalsIgnoreCase(orderDealer.getEmail()));
        }
    }

    @Transactional(readOnly = true)
    public DealerPortalDTOs.Profile profile() {
        User user = currentUserProvider.getCurrentUser();
        Dealer dealer = currentDealer(user).dealer();
        return DealerPortalDTOs.Profile.builder()
                .dealerId(dealer.getId())
                .name(dealer.getName())
                .tradeName(dealer.getTradeName())
                .email(dealer.getEmail())
                .phoneNumber(dealer.getPhoneNumber())
                .alternatePhoneNumber(dealer.getAlternatePhoneNumber())
                .street(dealer.getStreet())
                .landmark(dealer.getLandmark())
                .city(dealer.getCity())
                .district(dealer.getDistrict())
                .state(dealer.getState())
                .country(dealer.getCountry())
                .pincode(dealer.getPincode())
                .gstin(dealer.getGstin())
                .panNumber(dealer.getPanNumber())
                .username(user.getUsername())
                .build();
    }

    /** Items a dealer can quote for: the active items in at least one of the dealer's categories. */
    @Transactional(readOnly = true)
    public List<DealerPortalDTOs.Item> items() {
        Set<Long> categoryIds = categoryIdsOf(currentDealer(currentUserProvider.getCurrentUser()).dealer());
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        return itemRepository.findActiveInCategories(categoryIds).stream()
                .map(item -> DealerPortalDTOs.Item.builder()
                        .id(item.getId())
                        .name(item.getName())
                        .brand(item.getBrand())
                        .sku(item.getSku())
                        .unitOfMeasure(item.getUnitOfMeasure())
                        .mrp(item.getMrp())
                        .build())
                .toList();
    }

    /** All of the dealer's quotations and orders, newest first. */
    @Transactional(readOnly = true)
    public List<DealerPortalDTOs.Order> orders() {
        User user = currentUserProvider.getCurrentUser();
        Dealer dealer = currentDealer(user).dealer();
        List<Order> found = dealer.getEmail() == null
                ? orderRepository.findByDealerId(dealer.getId())
                : orderRepository.findByDealerEmail(dealer.getEmail());
        List<Order> orders = found.stream()
                .sorted(Comparator.comparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Order::getId, Comparator.reverseOrder()))
                .toList();
        Map<Long, OrderChangeDTOs.Response> pending = quotationChangeService.pendingByOrder(orders);
        return orders.stream().map(order -> toDto(order, user, pending.get(order.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public DealerPortalDTOs.Order order(Long orderId) {
        User user = currentUserProvider.getCurrentUser();
        Order order = ownOrder(orderId, currentDealer(user), false);
        List<OrderChangeDTOs.Response> changes = quotationChangeService.forOrder(order);
        OrderChangeDTOs.Response pending = changes.stream()
                .filter(change -> change.getStatus() == OrderChangeStatus.PENDING).findFirst().orElse(null);
        DealerPortalDTOs.Order dto = toDto(order, user, pending);
        dto.setChanges(changes.stream().map(DealerPortalDTOs.Change::of).toList());
        dto.setHistory(statusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()).stream()
                .map(step -> DealerPortalDTOs.StatusStep.builder()
                        .fromStatus(step.getFromStatus())
                        .toStatus(step.getToStatus())
                        .changedAt(step.getChangedAt())
                        .reason(step.getReason())
                        .build())
                .toList());
        return dto;
    }

    /** A new quotation from the dealer. Like any quotation it only becomes an order once staff submit and approve it. */
    @Transactional
    public DealerPortalDTOs.Order createQuotation(DealerPortalDTOs.QuotationRequest request) {
        User user = currentUserProvider.getCurrentUser();
        Dealer dealer = currentDealer(user).dealer();
        Long dealerId = dealer.getId();
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> ApiErrors.fieldError("itemId", "Item not found with id: " + request.getItemId()));
        if (!Boolean.TRUE.equals(item.getIsActive())) {
            throw ApiErrors.fieldError("itemId", "Item " + item.getName() + " is no longer available.");
        }
        requireInDealerCategories(dealer, item);
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> info = new LinkedHashMap<>();
        String note = OptionalText.blankToNull(request.getNote());
        if (note != null) {
            info.put(QuotationChangeService.DEALER_NOTE, note);
        }
        String priceChangeReason = OptionalText.blankToNull(request.getPriceChangeReason());
        if (priceChangeReason != null) {
            info.put(OrderDTOs.PRICE_CHANGE_REASON, priceChangeReason);
        }

        OrderDTOs.Response created = orderService.createOrder(OrderDTOs.CreateRequest.builder()
                .dealerId(dealerId)
                .itemId(item.getId())
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType().trim())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                .taxBreakup(request.getTaxBreakup())
                .expectedDelivery(request.getExpectedDelivery())
                .orderDate(today)
                .additionalInfo(info)
                .build());

        Order order = orderRepository.findById(created.getId()).orElseThrow();
        notificationService.createForPermission(Permission.PROCUREMENT_ORDER_UPDATE_STATUS, user.getId(), "New quotation from a dealer",
                "Dealer " + order.getDealer().getName() + " raised quotation " + order.getOrderNumber() + " for "
                        + item.getName() + ". Review it and submit it for approval when it is ready.", NotificationLinks.ORDERS);
        return toDto(order, user, null);
    }

    /** Asks for a change to one of the dealer's quotations; nothing changes until a Dealer Manager approves it. */
    @Transactional
    public DealerPortalDTOs.Change requestChange(Long orderId, DealerPortalDTOs.ChangeRequest request) {
        User user = currentUserProvider.getCurrentUser();
        DealerScope scope = currentDealer(user);
        Order order = ownOrder(orderId, scope, true);
        // Switching to another item: it has to be one the dealer deals in (an unknown item is reported further on)
        Long itemId = request.getItemId();
        if (itemId != null && !itemId.equals(order.getItem().getId())) {
            itemRepository.findById(itemId).ifPresent(item -> requireInDealerCategories(scope.dealer(), item));
        }
        OrderChangeDTOs.Terms proposed = OrderChangeDTOs.Terms.builder()
                .itemId(request.getItemId())
                .orderQuantity(request.getOrderQuantity())
                .unitType(request.getUnitType())
                .unitPrice(request.getUnitPrice())
                .shipmentPrice(request.getShipmentPrice())
                .taxBreakup(request.getTaxBreakup())
                .expectedDelivery(request.getExpectedDelivery())
                .note(request.getNote())
                .build();
        return DealerPortalDTOs.Change.of(quotationChangeService.request(order, user, proposed, request.getReason()));
    }

    @Transactional
    public DealerPortalDTOs.Change withdrawChange(Long requestId) {
        User user = currentUserProvider.getCurrentUser();
        return DealerPortalDTOs.Change.of(quotationChangeService.withdraw(requestId, currentDealer(user)::owns, user));
    }

    /** The dealer accepts (confirms) or rejects an order that was approved and is waiting for them. */
    @Transactional
    public DealerPortalDTOs.Order respond(Long orderId, DealerPortalDTOs.OrderResponseRequest request) {
        User user = currentUserProvider.getCurrentUser();
        Order order = ownOrder(orderId, currentDealer(user), true);
        boolean accepted = request.getDecision() == DealerPortalDTOs.OrderDecision.ACCEPT;
        orderService.processDealerResponse(order, accepted, request.getReason());
        return toDto(orderRepository.findById(orderId).orElseThrow(), user, null);
    }

    /** The dealer marks one of their confirmed orders as shipped; staff then record its delivery. */
    @Transactional
    public DealerPortalDTOs.Order ship(Long orderId) {
        User user = currentUserProvider.getCurrentUser();
        Order order = ownOrder(orderId, currentDealer(user), true);
        // The order's last staff updater hears about it from the status notifier; everyone else who records deliveries here
        Long previousUpdater = order.getUpdatedBy();
        orderService.processDealerShipment(order);
        notificationService.createForPermission(Permission.PROCUREMENT_ORDER_UPDATE_STATUS,
                previousUpdater != null ? previousUpdater : user.getId(), "Order Shipped by Dealer",
                "Dealer " + order.getDealer().getName() + " marked order " + order.getOrderNumber() + " ("
                        + order.getItem().getName() + ") as shipped. Mark it as delivered once it arrives.", NotificationLinks.ORDERS);
        return toDto(orderRepository.findById(orderId).orElseThrow(), user, null);
    }

    private static Set<Long> categoryIdsOf(Dealer dealer) {
        return dealer.getCategories().stream().map(Category::getId).collect(Collectors.toSet());
    }

    private static void requireInDealerCategories(Dealer dealer, Item item) {
        Set<Long> dealerCategories = categoryIdsOf(dealer);
        if (item.getCategories().stream().noneMatch(category -> dealerCategories.contains(category.getId()))) {
            throw ApiErrors.fieldError("itemId", "Item " + item.getName() + " is not in any of your categories.");
        }
    }

    private DealerScope currentDealer(User user) {
        Long dealerId = dealerLoginService.dealerIdOf(user.getId()).orElseThrow(DealerPortalService::notLinked);
        return new DealerScope(dealerRepository.findById(dealerId).orElseThrow(DealerPortalService::notLinked));
    }

    private Order ownOrder(Long orderId, DealerScope scope, boolean lock) {
        return (lock ? orderRepository.findByIdForUpdate(orderId) : orderRepository.findById(orderId))
                .filter(scope::owns)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
    }

    private static DealerPortalDTOs.Order toDto(Order order, User user, OrderChangeDTOs.Response pending) {
        Object note = order.getAdditionalInfo() == null ? null : order.getAdditionalInfo().get(QuotationChangeService.DEALER_NOTE);
        return DealerPortalDTOs.Order.builder()
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
                .orderStatus(order.getOrderStatus())
                .orderDate(order.getOrderDate())
                .expectedDelivery(order.getExpectedDelivery())
                .actualDelivery(order.getActualDelivery())
                .createdAt(order.getCreatedAt())
                .note(note instanceof String text ? text : null)
                .raisedByYou(user.getId().equals(order.getCreatedBy()))
                .changeable(order.getOrderStatus() == OrderStatus.QUOTATION && pending == null)
                .awaitingYourResponse(order.getOrderStatus() == OrderStatus.DEALER_LEVEL_PENDING)
                .shippable(order.getOrderStatus() == OrderStatus.CONFIRMED)
                .pendingChange(pending != null ? DealerPortalDTOs.Change.of(pending) : null)
                .build();
    }

    // Only a DEALER account with a dealer login reaches the portal endpoints; anything else is refused
    private static RuntimeException notLinked() {
        return ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "This account is not linked to a dealer.");
    }
}
