package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * What a unit of an item costs, for the cost sheet of the storefront products built from it (Ecommerce product
 * costing). Read from the purchase orders the item's stock was received against.
 */
@Service
@RequiredArgsConstructor
public class ItemCostService {

    private final ItemRepository itemRepository;
    private final StockRepository stockRepository;

    @Transactional(readOnly = true)
    public StockDTOs.ItemCostResponse getItemCost(Long itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));
        // Same entries, in the same order, as stock is consumed from
        List<Stock> consumable = stockRepository.findConsumableStock(itemId, ApprovalStatus.APPROVED, OrderStatus.DELIVERED);

        BigDecimal units = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        for (Stock stock : consumable) {
            units = units.add(stock.getAvailableUnits());
            value = value.add(landedUnitCost(stock.getOrder()).multiply(stock.getAvailableUnits()));
        }
        List<Stock> latest = stockRepository.findLatestByItem(itemId, ApprovalStatus.APPROVED, PageRequest.of(0, 1));
        Stock last = latest.isEmpty() ? null : latest.getFirst();

        return StockDTOs.ItemCostResponse.builder()
                .itemId(item.getId())
                .itemName(item.getName())
                .unitOfMeasure(item.getUnitOfMeasure())
                .nextUnitCost(consumable.isEmpty() ? null : rupees(landedUnitCost(consumable.getFirst().getOrder())))
                .averageUnitCost(units.signum() == 0 ? null : value.divide(units, 2, RoundingMode.HALF_UP))
                .unitsInStock(units)
                .lastUnitCost(last == null ? null : rupees(landedUnitCost(last.getOrder())))
                .lastReceivedOn(last == null ? null : last.getDateOfArrival())
                .lastOrderNumber(last == null ? null : last.getOrder().getOrderNumber())
                .build();
    }

    /** The order's unit price plus its shipment spread over the ordered quantity (GST excluded). */
    static BigDecimal landedUnitCost(Order order) {
        BigDecimal unitPrice = order.getUnitPrice() == null ? BigDecimal.ZERO : order.getUnitPrice();
        BigDecimal shipment = order.getShipmentPrice() == null ? BigDecimal.ZERO : order.getShipmentPrice();
        BigDecimal quantity = order.getOrderQuantity();
        if (shipment.signum() == 0 || quantity == null || quantity.signum() <= 0) {
            return unitPrice;
        }
        return unitPrice.add(shipment.divide(quantity, 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal rupees(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
