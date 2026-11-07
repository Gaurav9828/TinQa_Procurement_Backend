package com.tinqa.procurement.order.mapper;

import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.payment.PaymentPlan;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public OrderDTOs.Response toResponse(Order order) {
        return OrderDTOs.Response.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .dealerId(order.getDealer().getId())
                .dealerName(order.getDealer().getName())
                .itemId(order.getItem().getId())
                .itemName(order.getItem().getName())
                .orderQuantity(order.getOrderQuantity())
                .unitType(order.getUnitType())
                .unitPrice(order.getUnitPrice())
                .totalPrice(order.getTotalPrice())
                .shipmentPrice(order.getShipmentPrice())
                .taxBreakup(order.getTaxBreakup())
                .advancePercent(order.getAdvancePercent())
                .midtermPercent(order.getMidtermPercent())
                .finalPercent(PaymentPlan.finalPercent(order.getAdvancePercent(), order.getMidtermPercent()))
                .expectedPaymentMethod(order.getExpectedPaymentMethod())
                .orderStatus(order.getOrderStatus())
                .expectedDelivery(order.getExpectedDelivery())
                .actualDelivery(order.getActualDelivery())
                .orderDate(order.getOrderDate())
                .additionalInfo(order.getAdditionalInfo())
                .createdAt(order.getCreatedAt())
                .createdBy(order.getCreatedBy())
                .build();
    }
}
