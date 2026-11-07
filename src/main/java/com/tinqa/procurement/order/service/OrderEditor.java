package com.tinqa.procurement.order.service;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.supplier.repository.SupplierRepository;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.payment.PaymentPlan;
import com.tinqa.procurement.order.tax.QuotationTax;
import com.tinqa.procurement.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Applies an edit to an order's terms with the rules every edit follows, whether an admin makes it directly or it is
 * a supplier's change that was just approved. The caller decides whether the order may be edited at all and locks it.
 */
@Component
@RequiredArgsConstructor
public class OrderEditor {

    // No stock entry is being edited when an order's quantity changes
    private static final long NO_STOCK_ENTRY = -1L;

    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;

    /** Copies every non-null field of the request onto the order, then recalculates its total and GST. */
    public void apply(Order order, OrderDTOs.UpdateRequest request) {
        if (request.getSupplierId() != null && !request.getSupplierId().equals(order.getSupplier().getId())) {
            order.setSupplier(findSupplier(request.getSupplierId()));
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
        if (request.getExpectedDelivery() != null) order.setExpectedDelivery(request.getExpectedDelivery());
        if (request.getOrderDate() != null) order.setOrderDate(request.getOrderDate());
        if (request.getAdditionalInfo() != null) order.setAdditionalInfo(request.getAdditionalInfo());
        if (request.getAdvancePercent() != null || request.getMidtermPercent() != null) {
            // Either part may change alone; together they still leave the final part 0 % or more
            BigDecimal advance = request.getAdvancePercent() != null ? request.getAdvancePercent() : order.getAdvancePercent();
            BigDecimal midterm = request.getMidtermPercent() != null ? request.getMidtermPercent() : order.getMidtermPercent();
            PaymentPlan.validate(advance, midterm);
            order.setAdvancePercent(advance);
            order.setMidtermPercent(midterm);
        }
        if (request.getExpectedPaymentMethod() != null) order.setExpectedPaymentMethod(request.getExpectedPaymentMethod());
        order.recalculateTotalPrice();
        // A breakup sent is checked against the new total; a stored one follows the new total at its rate
        order.setTaxBreakup(request.getTaxBreakup() != null
                ? QuotationTax.normalise(request.getTaxBreakup(), order.getTotalPrice())
                : QuotationTax.recalculate(order.getTaxBreakup(), order.getTotalPrice()));
        ensureExpectedDeliveryNotBeforeOrder(order.getOrderDate(), order.getExpectedDelivery());
    }

    public static void ensureExpectedDeliveryNotBeforeOrder(LocalDate orderDate, LocalDate expectedDelivery) {
        if (orderDate != null && expectedDelivery != null && expectedDelivery.isBefore(orderDate)) {
            throw ApiErrors.fieldError("expectedDelivery", "Expected delivery date cannot be before the order date (" + orderDate + ")");
        }
    }

    public Supplier findSupplier(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id: " + supplierId));
    }

    public Item findItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));
    }
}
