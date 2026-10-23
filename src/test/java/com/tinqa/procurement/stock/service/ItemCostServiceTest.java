package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.order.entity.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemCostServiceTest {

    private static Order order(String unitPrice, String shipment, String quantity) {
        return Order.builder().unitPrice(new BigDecimal(unitPrice)).shipmentPrice(new BigDecimal(shipment))
                .orderQuantity(new BigDecimal(quantity)).build();
    }

    @Test
    void theShipmentIsSpreadOverTheOrderedUnits() {
        assertEquals(0, new BigDecimal("125").compareTo(ItemCostService.landedUnitCost(order("100", "250", "10"))));
    }

    @Test
    void withoutShipmentTheUnitPriceIsTheCost() {
        assertEquals(0, new BigDecimal("100").compareTo(ItemCostService.landedUnitCost(order("100", "0", "10"))));
    }
}
