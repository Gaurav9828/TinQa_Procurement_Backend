package com.tinqa.procurement.order.notification;

import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.dealer.login.DealerLoginService;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Tells the previous updater of an order what the current user changed, and the order's dealer when they have a
 * dealer login (nobody is notified about their own change).
 */
@Component
@RequiredArgsConstructor
public class OrderStatusNotifier {

    private final NotificationService notificationService;
    private final DealerLoginService dealerLoginService;

    /** Must be called before the order's status and updater are changed. */
    public void notifyStatusChange(Order order, OrderStatus newStatus, User actor, String reason, LocalDate actualDelivery) {
        Long dealerUser = dealerLoginService.activeUserIdOf(order.getDealer().getId()).orElse(null);
        if (dealerUser != null && !dealerUser.equals(actor.getId())) {
            notifyDealer(dealerUser, order, newStatus, reason, actualDelivery);
        }
        // The dealer already heard about it in their own words
        if (order.getUpdatedBy() == null || order.getUpdatedBy().equals(actor.getId()) || order.getUpdatedBy().equals(dealerUser)) {
            return;
        }
        String orderLabel = "Order " + order.getOrderNumber() + " (" + Quantities.plain(order.getOrderQuantity())
                + " " + order.getUnitType() + " of " + order.getItem().getName() + " from `" + order.getDealer().getName() + "`)";
        boolean byDealer = actor.getRole() == Role.DEALER;
        String by = byDealer ? " by the dealer (" + actor.getUsername() + ")" : " by " + actor.getUsername();
        String reasonSuffix = reason != null && !reason.isBlank() ? ". Reason: " + reason.trim() : "";

        String title;
        String message;
        switch (newStatus) {
            case PENDING -> {
                title = "Order Submitted for Approval";
                message = orderLabel + " was submitted for Admin L2 approval" + by + ".";
            }
            case DEALER_LEVEL_PENDING -> {
                title = "Order Approved";
                message = orderLabel + " was approved" + by + ". Now pending from the dealer.";
            }
            case CONFIRMED -> {
                title = "Order Confirmed";
                message = orderLabel + " was confirmed" + by + ".";
            }
            case SHIPPED -> {
                title = "Order Shipped";
                message = orderLabel + " was marked as shipped" + by + ".";
            }
            case DELIVERED -> {
                title = "Order Delivered";
                message = orderLabel + " was marked as delivered" + by
                        + (actualDelivery != null ? " on " + actualDelivery : "") + ". Stock can now be created from it.";
            }
            case CANCELLED -> {
                title = byDealer ? "Order Rejected by Dealer" : "Order Cancelled";
                message = orderLabel + (byDealer ? " was rejected" : " was cancelled") + by + reasonSuffix + ".";
            }
            default -> {
                title = "Order " + newStatus.label();
                message = orderLabel + " was moved to " + newStatus.label() + by + reasonSuffix + ".";
            }
        }
        notificationService.createForUser(order.getUpdatedBy(), title, message, NotificationLinks.ORDERS);
    }

    // Worded for the dealer: no internal approval levels or staff names
    private void notifyDealer(Long dealerUser, Order order, OrderStatus newStatus, String reason, LocalDate actualDelivery) {
        String number = order.getOrderNumber();
        String reasonSuffix = reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : "";
        String title;
        String message;
        switch (newStatus) {
            case PENDING -> {
                title = "Quotation under review";
                message = "Your quotation " + number + " was accepted for review.";
            }
            case DEALER_LEVEL_PENDING -> {
                title = "Order approved";
                message = "Order " + number + " was approved and is awaiting final confirmation.";
            }
            case CONFIRMED -> {
                title = "Order confirmed";
                message = "Order " + number + " is confirmed.";
            }
            case SHIPPED -> {
                title = "Order shipped";
                message = "Order " + number + " was marked as shipped.";
            }
            case DELIVERED -> {
                title = "Order delivered";
                message = "Order " + number + " was received" + (actualDelivery != null ? " on " + actualDelivery : "")
                        + ". Its bill is ready to print.";
            }
            case CANCELLED -> {
                // Still a quotation to the dealer until it is approved
                boolean quotation = order.getOrderStatus() == OrderStatus.QUOTATION || order.getOrderStatus() == OrderStatus.PENDING;
                title = quotation ? "Quotation cancelled" : "Order cancelled";
                message = (quotation ? "Quotation " : "Order ") + number + " was cancelled." + reasonSuffix;
            }
            default -> {
                title = "Order " + newStatus.label().toLowerCase();
                message = "Order " + number + " moved to " + newStatus.label().toLowerCase() + "." + reasonSuffix;
            }
        }
        String link = newStatus == OrderStatus.PENDING ? NotificationLinks.DEALER_QUOTATIONS : NotificationLinks.DEALER_ORDERS;
        notificationService.createForUser(dealerUser, title, message, link);
    }
}
