package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.stock.dto.StockDTOs;

import java.util.List;

public interface StockService {

    StockDTOs.Response createStockFromOrder(StockDTOs.CreateFromOrderRequest request, Long currentUserId);

    StockDTOs.Response addStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request, Long currentUserId);

    StockDTOs.Response reduceStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request, Long currentUserId);

    StockDTOs.Response processAdminL2Approval(Long id, StockDTOs.ApprovalDecisionRequest request, Long adminUserId);

    StockDTOs.Response getStockById(Long id);

    List<StockDTOs.Response> getAllStocks();

    StockDTOs.Response updateStock(Long id, StockDTOs.UpdateRequest request, Long currentUserId);
}