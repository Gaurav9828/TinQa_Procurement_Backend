package com.tinqa.procurement.order.notification;

import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Tells the previous updater of an order what the current user changed (nobody is notified about their own change).
 */
@Component
@RequiredArgsConstructor
public class OrderStatusNotifier {

    private final NotificationService notificationService;

    /** Must be called before the order's status and updater are changed. */
    public void notifyStatusChange(Order order, OrderStatus newStatus, User actor, String reason, LocalDate actualDelivery) {
        if (order.getUpdatedBy() == null || order.getUpdatedBy().equals(actor.getId())) {
            return;
        }
        String orderLabel = "Order " + order.getOrderNumber() + " (" + Quantities.plain(order.getOrderQuantity())
                + " " + order.getUnitType() + " of " + order.getItem().getName() + " from `" + order.getDealer().getName() + "`)";
        String by = " by " + actor.getUsername();
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
                title = "Order Cancelled";
                message = orderLabel + " was cancelled" + by + reasonSuffix + ".";
            }
            default -> {
                title = "Order " + newStatus.label();
                message = orderLabel + " was moved to " + newStatus.label() + by + reasonSuffix + ".";
            }
        }
        notificationService.createForUser(order.getUpdatedBy(), title, message);
    }
}
