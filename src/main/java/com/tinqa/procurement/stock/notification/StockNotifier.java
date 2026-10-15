package com.tinqa.procurement.stock.notification;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.stock.entity.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Notifications of the stock approval workflow.
 */
@Component
@RequiredArgsConstructor
public class StockNotifier {

    private final NotificationService notificationService;

    /** Everyone who can approve stock, except the submitter, learns that a new stock entry waits for approval. */
    public void approvalRequired(Stock stock, Order order, User submitter) {
        String unit = order.getUnitType();
        String message = "Stock " + stock.getStockIdentityNumber() + " (batch " + stock.getBatchNumber() + ") for order "
                + order.getOrderNumber() + ": " + Quantities.plain(stock.getUnitsPassedTest()) + " " + unit + " passed and "
                + Quantities.plain(stock.getDefectedUnits()) + " " + unit + " defected of " + order.getItem().getName()
                + " from `" + order.getDealer().getName() + "`, arrived on " + stock.getDateOfArrival()
                + ". Submitted by " + submitter.getUsername() + " and waiting for your approval.";
        notificationService.createForPermission(Permission.STOCK_APPROVE, submitter.getId(), "Stock Approval Required", message);
    }

    /** The last updater of the entry learns the decision, unless they made it themselves. Call before updating it. */
    public void decided(Stock stock, User approver, ApprovalStatus decision, String rejectionReason) {
        if (stock.getUpdatedBy() == null || Objects.equals(stock.getUpdatedBy(), approver.getId())) {
            return;
        }
        boolean rejected = decision == ApprovalStatus.REJECTED;
        String title = rejected ? "Stock Cancelled" : "Stock Approved";
        String message = rejected
                ? "Stock Batch: " + stock.getBatchNumber() + " cancelled by ID: " + approver.getUsername() + ". Reason: " + rejectionReason
                : "Stock Batch " + stock.getBatchNumber() + " approved by ID: " + approver.getUsername() + ".";
        notificationService.createForUser(stock.getUpdatedBy(), title, message);
    }
}
