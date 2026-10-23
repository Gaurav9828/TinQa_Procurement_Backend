package com.tinqa.procurement.stock.repository;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.stock.entity.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    interface ItemUnitsTotal {
        Long getItemId();

        BigDecimal getTotalUnits();
    }

    // Units (passed + defected) already recorded against an order, ignoring rejected entries and one entry being edited
    @Query("""
            SELECT COALESCE(SUM(s.unitsPassedTest + s.defectedUnits), 0)
            FROM Stock s
            WHERE s.order.orderNumber = :orderNumber
              AND s.approvalStatus <> :rejected
              AND s.id <> :excludeStockId
            """)
    BigDecimal sumRecordedUnitsForOrder(@Param("orderNumber") String orderNumber,
                                        @Param("excludeStockId") Long excludeStockId,
                                        @Param("rejected") ApprovalStatus rejected);
    List<Stock> findByApprovalStatus(ApprovalStatus approvalStatus);

    /** What waits for Admin L2: new entries, and approved entries whose counts were changed since. */
    @Query("""
            SELECT s FROM Stock s
            WHERE s.approvalStatus = :pending
               OR (s.approvalStatus = :approved AND s.approvedUnitsPassed IS NOT NULL
                   AND (s.unitsPassedTest <> s.approvedUnitsPassed OR s.defectedUnits <> s.approvedDefectedUnits))
            ORDER BY s.id
            """)
    List<Stock> findAwaitingApproval(@Param("pending") ApprovalStatus pending, @Param("approved") ApprovalStatus approved);
    Optional<Stock> findFirstByOrderOrderNumber(String orderNumber);

    /**
     * Stock entries that can be consumed for an item, oldest arrival first:
     * active, approved, from a delivered order and with units left.
     */
    @Query("""
            SELECT s FROM Stock s JOIN s.order o
            WHERE s.item.id = :itemId
              AND s.isActive = true
              AND s.approvalStatus = :approvalStatus
              AND o.orderStatus = :orderStatus
              AND s.availableUnits > 0
            ORDER BY s.dateOfArrival ASC, s.id ASC
            """)
    List<Stock> findConsumableStock(@Param("itemId") Long itemId,
                                    @Param("approvalStatus") ApprovalStatus approvalStatus,
                                    @Param("orderStatus") OrderStatus orderStatus);

    // Same as findConsumableStock, but row-locks the entries so concurrent consumers cannot oversell
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM Stock s JOIN s.order o
            WHERE s.item.id = :itemId
              AND s.isActive = true
              AND s.approvalStatus = :approvalStatus
              AND o.orderStatus = :orderStatus
              AND s.availableUnits > 0
            ORDER BY s.dateOfArrival ASC, s.id ASC
            """)
    List<Stock> findConsumableStockForUpdate(@Param("itemId") Long itemId,
                                             @Param("approvalStatus") ApprovalStatus approvalStatus,
                                             @Param("orderStatus") OrderStatus orderStatus);

    // Row lock for read-modify-write changes to one stock entry
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.id = :id")
    Optional<Stock> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.id IN :ids")
    List<Stock> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    // Consumable units per item, same rules as findConsumableStock
    @Query("""
            SELECT s.item.id AS itemId, SUM(s.availableUnits) AS totalUnits
            FROM Stock s JOIN s.order o
            WHERE s.isActive = true
              AND s.approvalStatus = :approvalStatus
              AND o.orderStatus = :orderStatus
              AND s.availableUnits > 0
            GROUP BY s.item.id
            """)
    List<ItemUnitsTotal> sumConsumableUnitsByItem(@Param("approvalStatus") ApprovalStatus approvalStatus,
                                                  @Param("orderStatus") OrderStatus orderStatus);

    @Query("""
            SELECT s.item.id AS itemId, SUM(s.availableUnits) AS totalUnits
            FROM Stock s JOIN s.order o
            WHERE s.isActive = true
              AND s.approvalStatus = :approvalStatus
              AND o.orderStatus = :orderStatus
              AND s.availableUnits > 0
              AND s.item.id IN :itemIds
            GROUP BY s.item.id
            """)
    List<ItemUnitsTotal> sumConsumableUnitsByItem(@Param("itemIds") Collection<Long> itemIds,
                                                  @Param("approvalStatus") ApprovalStatus approvalStatus,
                                                  @Param("orderStatus") OrderStatus orderStatus);

    // The item's most recently received approved stock (its latest purchase price)
    @Query("""
            SELECT s FROM Stock s JOIN FETCH s.order o
            WHERE s.item.id = :itemId AND s.approvalStatus = :approvalStatus
            ORDER BY s.dateOfArrival DESC, s.id DESC
            """)
    List<Stock> findLatestByItem(@Param("itemId") Long itemId, @Param("approvalStatus") ApprovalStatus approvalStatus,
                                 Pageable pageable);
}
