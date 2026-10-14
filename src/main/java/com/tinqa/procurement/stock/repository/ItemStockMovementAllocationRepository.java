package com.tinqa.procurement.stock.repository;

import com.tinqa.procurement.stock.entity.ItemStockMovementAllocation;
import com.tinqa.procurement.stock.enums.ItemStockMovementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ItemStockMovementAllocationRepository extends JpaRepository<ItemStockMovementAllocation, Long> {

    interface StockNetUnits {
        Long getStockId();

        BigDecimal getNetUnits();

        Long getLastAllocationId();
    }

    interface ItemNetUnits {
        Long getItemId();

        BigDecimal getNetUnits();
    }

    // Units a reference still holds per item (consumed minus returned, applied movements only)
    @Query("""
            SELECT a.itemId AS itemId, SUM(a.units) AS netUnits
            FROM ItemStockMovementAllocation a JOIN a.movement m
            WHERE m.source = :source
              AND m.reference = :reference
              AND m.status = :status
            GROUP BY a.itemId
            HAVING SUM(a.units) > 0
            ORDER BY a.itemId
            """)
    List<ItemNetUnits> findHeldUnitsByItem(@Param("source") String source,
                                          @Param("reference") String reference,
                                          @Param("status") ItemStockMovementStatus status);

    /**
     * Units a reference still holds per stock entry for an item (consumed minus returned, applied movements only),
     * most recently consumed first so returns go back LIFO.
     */
    @Query("""
            SELECT a.stock.id AS stockId, SUM(a.units) AS netUnits, MAX(a.id) AS lastAllocationId
            FROM ItemStockMovementAllocation a JOIN a.movement m
            WHERE m.source = :source
              AND m.reference = :reference
              AND m.status = :status
              AND a.itemId = :itemId
            GROUP BY a.stock.id
            HAVING SUM(a.units) > 0
            ORDER BY MAX(a.id) DESC
            """)
    List<StockNetUnits> findHeldUnitsByStock(@Param("source") String source,
                                             @Param("reference") String reference,
                                             @Param("itemId") Long itemId,
                                             @Param("status") ItemStockMovementStatus status);
}
