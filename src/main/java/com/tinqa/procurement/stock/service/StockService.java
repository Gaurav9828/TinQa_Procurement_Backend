package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.dto.StockDTOs;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface StockService {

    StockDTOs.Response createStockFromOrder(StockDTOs.CreateFromOrderRequest request, Long currentUserId);

    StockDTOs.Response addStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request, Long currentUserId);

    StockDTOs.Response reduceStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request, Long currentUserId);

    StockDTOs.Response processAdminL2Approval(Long id, StockDTOs.ApprovalDecisionRequest request, Long adminUserId);

    StockDTOs.Response getStockById(Long id);

    List<StockDTOs.Response> getAllStocks();

    List<StockDTOs.Response> getAllStocksByStatus(ApprovalStatus status);

    StockDTOs.Response updateStock(Long id, StockDTOs.UpdateRequest request, Long currentUserId);

    StockDTOs.ItemAvailabilityResponse getItemAvailability(Long itemId);

    /**
     * Takes the quantity out of the item's consumable stock, oldest arrival first.
     * Throws INSUFFICIENT_STOCK (409) without changing anything when there is not enough.
     *
     * @return units taken from each stock entry, in consumption order
     */
    Map<Stock, BigDecimal> consumeItemStock(Item item, BigDecimal quantity, Long currentUserId);

    // Puts previously consumed units back on their stock entries (keyed by stock id)
    void restoreStock(Map<Long, BigDecimal> unitsByStockId, Long currentUserId);

    // Row-locks the item's consumable stock and returns the units available, for checks before consuming
    BigDecimal lockConsumableUnits(Long itemId);

    /**
     * Takes previously returned units back off their stock entries (keyed by stock id).
     * Throws REVERSAL_NOT_POSSIBLE (409) without changing anything if an entry no longer has them.
     */
    void takeBackStock(Map<Long, BigDecimal> unitsByStockId, Long currentUserId);

    List<StockDTOs.ItemAvailabilitySummary> getItemsAvailability(List<Long> itemIds, boolean inStockOnly);
}