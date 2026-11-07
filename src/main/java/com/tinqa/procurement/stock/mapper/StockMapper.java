package com.tinqa.procurement.stock.mapper;

import com.tinqa.procurement.finance.service.PurchasePostingRecorder;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.entity.Stock;
import org.springframework.stereotype.Component;

@Component
public class StockMapper {

    // Quantity, unit and prices come from the order the stock was received against
    public StockDTOs.Response toResponse(Stock stock) {
        Order order = stock.getOrder();
        return StockDTOs.Response.builder()
                .id(stock.getId())
                .stockIdentityNumber(stock.getStockIdentityNumber())
                .batchNumber(stock.getBatchNumber())
                .orderNumber(order.getOrderNumber())
                .supplierId(stock.getSupplier().getId())
                .supplierName(stock.getSupplier().getName())
                .itemId(stock.getItem().getId())
                .itemName(stock.getItem().getName())
                .totalOrderQuantity(order.getOrderQuantity())
                .unitType(order.getUnitType())
                .unitsPassedTest(stock.getUnitsPassedTest())
                .defectedUnits(stock.getDefectedUnits())
                .availableUnits(stock.getAvailableUnits())
                .unitPrice(order.getUnitPrice())
                .totalPrice(order.getTotalPrice())
                .hasTested(stock.getHasTested())
                .additionalInfo(stock.getAdditionalInfo())
                .approvalStatus(stock.getApprovalStatus())
                .approvedBy(stock.getApprovedBy())
                .approvedAt(stock.getApprovedAt())
                .rejectionReason(stock.getRejectionReason())
                .dateOfArrival(stock.getDateOfArrival())
                .isActive(stock.getIsActive())
                .createdAt(stock.getCreatedAt())
                .createdBy(stock.getCreatedBy())
                .approvedUnitsPassed(stock.getApprovedUnitsPassed())
                .approvedDefectedUnits(stock.getApprovedDefectedUnits())
                .changeAwaitingApproval(stock.hasChangeAwaitingApproval())
                .heldUnits(stock.heldUnits())
                .bookedPurchaseValue(PurchasePostingRecorder.purchaseValue(order, stock))
                .build();
    }

    public StockDTOs.AvailableStockEntry toAvailableEntry(Stock stock) {
        return StockDTOs.AvailableStockEntry.builder()
                .stockId(stock.getId())
                .stockIdentityNumber(stock.getStockIdentityNumber())
                .batchNumber(stock.getBatchNumber())
                .orderNumber(stock.getOrder().getOrderNumber())
                .availableUnits(stock.getAvailableUnits())
                .dateOfArrival(stock.getDateOfArrival())
                .build();
    }
}
