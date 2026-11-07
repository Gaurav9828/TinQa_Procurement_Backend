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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
        Long itemBefore = order.getItem().getId();
        BigDecimal priceBefore = order.getUnitPrice();
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
        boolean priceChanged = priceBefore.compareTo(order.getUnitPrice()) != 0;
        if (priceChanged || !itemBefore.equals(order.getItem().getId())) {
            // A new price (or another item with its own MRP) is justified again, and approvers see the price it replaced
            Map<String, Object> info = Objects.requireNonNullElse(order.getAdditionalInfo(), Map.of());
            BigDecimal previous = priceChanged ? priceBefore : previousUnitPrice(info);
            order.setAdditionalInfo(justifyPrice(order.getItem(), order.getUnitPrice(), info, previous));
        }
        order.recalculateTotalPrice();
        // A breakup sent is checked against the new total; a stored one follows the new total at its rate
        order.setTaxBreakup(request.getTaxBreakup() != null
                ? QuotationTax.normalise(request.getTaxBreakup(), order.getTotalPrice())
                : QuotationTax.recalculate(order.getTaxBreakup(), order.getTotalPrice()));
        ensureExpectedDeliveryNotBeforeOrder(order.getOrderDate(), order.getExpectedDelivery());
    }

    /**
     * A unit price other than the item's MRP needs a reason ({@code priceChangeReason} in {@code info}); the MRP it was
     * compared with is kept beside it so approvers see both. At the MRP (or for an item without one) neither key is
     * kept. {@code previousPrice} is the unit price quoted before this one, kept for approvers; null clears it.
     */
    public static Map<String, Object> justifyPrice(Item item, BigDecimal unitPrice, Map<String, Object> info, BigDecimal previousPrice) {
        Map<String, Object> result = new LinkedHashMap<>(info);
        result.remove(OrderDTOs.PRICE_CHANGE_REASON);
        result.remove(OrderDTOs.QUOTED_MRP);
        result.remove(OrderDTOs.PREVIOUS_UNIT_PRICE);
        if (previousPrice != null && previousPrice.compareTo(unitPrice) != 0) {
            result.put(OrderDTOs.PREVIOUS_UNIT_PRICE, previousPrice);
        }
        BigDecimal mrp = item.getMrp();
        if (mrp == null || mrp.signum() <= 0 || mrp.compareTo(unitPrice) == 0) {
            return result;
        }
        if (!(info.get(OrderDTOs.PRICE_CHANGE_REASON) instanceof String reason) || reason.isBlank()) {
            throw ApiErrors.fieldError(OrderDTOs.PRICE_CHANGE_REASON, "Give the reason for quoting " + unitPrice.toPlainString()
                    + " instead of the item's existing price " + mrp.toPlainString() + ".");
        }
        result.put(OrderDTOs.PRICE_CHANGE_REASON, reason.trim());
        result.put(OrderDTOs.QUOTED_MRP, mrp);
        return result;
    }

    private static BigDecimal previousUnitPrice(Map<String, Object> info) {
        Object value = info.get(OrderDTOs.PREVIOUS_UNIT_PRICE);
        return value == null ? null : new BigDecimal(value.toString());
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
