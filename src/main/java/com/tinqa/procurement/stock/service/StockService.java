package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.entity.Stock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Stock received from delivered orders, its Admin L2 approval, and consumption by products and other services.
 * Admin-facing operations act as the signed-in user; consumption methods take the user id explicitly because
 * service-to-service movements have none (null).
 */
public interface StockService {

    StockDTOs.Response createStockFromOrder(StockDTOs.CreateFromOrderRequest request);

    StockDTOs.Response addStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request);

    StockDTOs.Response reduceStockQuantity(Long id, StockDTOs.QuantityAdjustmentRequest request);

    StockDTOs.Response processAdminL2Approval(Long id, StockDTOs.ApprovalDecisionRequest request);

    StockDTOs.Response updateStock(Long id, StockDTOs.UpdateRequest request);

    StockDTOs.Response getStockById(Long id);

    List<StockDTOs.Response> getAllStocks();

    List<StockDTOs.Response> getAllStocksByStatus(ApprovalStatus status);

    StockDTOs.ItemAvailabilityResponse getItemAvailability(Long itemId);

    List<StockDTOs.ItemAvailabilitySummary> getItemsAvailability(List<Long> itemIds, boolean inStockOnly);

    /**
     * Takes the quantity out of the item's consumable stock that has no barcode (received before barcodes were
     * recorded), oldest arrival first. Pieces with a barcode are only used by naming it: see {@link #consumeUnits}.
     * Throws INSUFFICIENT_STOCK (409) without changing anything when there is not enough.
     *
     * @return units taken from each stock entry, in consumption order
     */
    Map<Stock, BigDecimal> consumeItemStock(Item item, BigDecimal quantity, Long currentUserId);

    /**
     * Takes identified pieces (by their barcodes' stock entries, keyed by stock id) out of stock. Every entry must be
     * consumable and hold them; throws INSUFFICIENT_ITEM_STOCK (409) without changing anything otherwise.
     */
    Map<Stock, BigDecimal> consumeUnits(Map<Long, BigDecimal> unitsByStockId, Long currentUserId);

    /** Row-locks the item's consumable stock and returns its available units that have no barcode. */
    BigDecimal lockUntrackedConsumableUnits(Long itemId);

    /** Puts previously consumed units back on their stock entries (keyed by stock id). */
    void restoreStock(Map<Long, BigDecimal> unitsByStockId, Long currentUserId);

    /** Row-locks the item's consumable stock and returns the units available, for checks before consuming. */
    BigDecimal lockConsumableUnits(Long itemId);

    /**
     * Takes previously returned units back off their stock entries (keyed by stock id).
     * Throws REVERSAL_NOT_POSSIBLE (409) without changing anything if an entry no longer has them.
     */
    void takeBackStock(Map<Long, BigDecimal> unitsByStockId, Long currentUserId);
}
