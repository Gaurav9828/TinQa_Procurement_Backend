package com.tinqa.procurement.order.notification;

import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.supplier.login.SupplierLoginService;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.change.SupplierQuotedTerms;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.security.model.Role;
import com.tinqa.procurement.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Tells the previous updater of an order what the current user changed, and the order's supplier when they have a
 * supplier login (nobody is notified about their own change).
 */
@Component
@RequiredArgsConstructor
public class OrderStatusNotifier {

    private final NotificationService notificationService;
    private final SupplierLoginService supplierLoginService;

    /** Must be called before the order's status and updater are changed. */
    public void notifyStatusChange(Order order, OrderStatus newStatus, User actor, String reason, LocalDate actualDelivery) {
        Long supplierUser = supplierLoginService.activeUserIdOf(order.getSupplier().getId()).orElse(null);
        if (supplierUser != null && !supplierUser.equals(actor.getId())) {
            notifySupplier(supplierUser, order, newStatus, reason, actualDelivery);
        }
        // The supplier already heard about it in their own words
        if (order.getUpdatedBy() == null || order.getUpdatedBy().equals(actor.getId()) || order.getUpdatedBy().equals(supplierUser)) {
            return;
        }
        String orderLabel = "Order " + order.getOrderNumber() + " (" + Quantities.plain(order.getOrderQuantity())
                + " " + order.getUnitType() + " of " + order.getItem().getName() + " from `" + order.getSupplier().getName() + "`)";
        boolean bySupplier = actor.getRole() == Role.SUPPLIER;
        String by = bySupplier ? " by the supplier (" + actor.getUsername() + ")" : " by " + actor.getUsername();
        String reasonSuffix = reason != null && !reason.isBlank() ? ". Reason: " + reason.trim() : "";

        String title;
        String message;
        switch (newStatus) {
            case PENDING -> {
                title = "Order Submitted for Approval";
                message = orderLabel + " was submitted for Admin L2 approval" + by + ".";
            }
            case SUPPLIER_LEVEL_PENDING -> {
                title = "Order Approved";
                message = orderLabel + " was approved" + by + ". Now pending from the supplier.";
            }
            case CONFIRMED -> {
                title = "Order Confirmed";
                // reasonSuffix: what the supplier agreed to, when TinQa changed their quotation
                message = orderLabel + " was confirmed" + by + reasonSuffix + ".";
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
                title = bySupplier ? "Order Rejected by Supplier" : "Order Cancelled";
                message = orderLabel + (bySupplier ? " was rejected" : " was cancelled") + by + reasonSuffix + ".";
            }
            default -> {
                title = "Order " + newStatus.label();
                message = orderLabel + " was moved to " + newStatus.label() + by + reasonSuffix + ".";
            }
        }
        notificationService.createForUser(order.getUpdatedBy(), title, message, NotificationLinks.ORDERS);
    }

    // Worded for the supplier: no internal approval levels or staff names
    private void notifySupplier(Long supplierUser, Order order, OrderStatus newStatus, String reason, LocalDate actualDelivery) {
        String number = order.getOrderNumber();
        String reasonSuffix = reason != null && !reason.isBlank() ? " Reason: " + reason.trim() : "";
        String title;
        String message;
        switch (newStatus) {
            case PENDING -> {
                title = "Quotation under review";
                message = "Your quotation " + number + " was accepted for review.";
            }
            case SUPPLIER_LEVEL_PENDING -> {
                title = "Order approved";
                List<SupplierQuotedTerms.TermChange> changed = SupplierQuotedTerms.changes(order);
                message = "Order " + number + " was approved and is awaiting final confirmation." + (changed.isEmpty() ? ""
                        : " TinQa changed your quotation (" + SupplierQuotedTerms.labels(changed)
                        + "): check what changed in My Orders before you accept it.");
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
                // Still a quotation to the supplier until it is approved
                boolean quotation = order.getOrderStatus() == OrderStatus.QUOTATION || order.getOrderStatus() == OrderStatus.PENDING;
                title = quotation ? "Quotation cancelled" : "Order cancelled";
                message = (quotation ? "Quotation " : "Order ") + number + " was cancelled." + reasonSuffix;
            }
            default -> {
                title = "Order " + newStatus.label().toLowerCase();
                message = "Order " + number + " moved to " + newStatus.label().toLowerCase() + "." + reasonSuffix;
            }
        }
        String link = newStatus == OrderStatus.PENDING ? NotificationLinks.SUPPLIER_QUOTATIONS : NotificationLinks.SUPPLIER_ORDERS;
        notificationService.createForUser(supplierUser, title, message, link);
    }
}
