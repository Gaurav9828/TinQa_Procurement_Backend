package com.tinqa.procurement.barcode.repository;

import com.tinqa.procurement.barcode.entity.Barcode;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BarcodeRepository extends JpaRepository<Barcode, Long> {

    interface StockCount {
        Long getStockId();

        Long getUnits();
    }

    // The live unit carrying a code (a voided registration no longer holds it)
    @EntityGraph(attributePaths = {"item", "stock", "fittedInto"})
    @Query("SELECT b FROM Barcode b WHERE b.code = :code AND b.status <> com.tinqa.procurement.barcode.enums.BarcodeStatus.VOID")
    Optional<Barcode> findLiveByCode(@Param("code") String code);

    // Most recent registration of a code, live or not, for tracing a code that was voided
    @EntityGraph(attributePaths = {"item", "stock", "fittedInto"})
    List<Barcode> findByCodeOrderByIdDesc(String code, Pageable pageable);

    @Query("SELECT b.code FROM Barcode b WHERE b.code IN :codes AND b.status <> com.tinqa.procurement.barcode.enums.BarcodeStatus.VOID")
    List<String> findLiveCodes(@Param("codes") Collection<String> codes);

    // Row-locks the live units carrying the codes, so two changes can never use the same unit
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Barcode b WHERE b.code IN :codes AND b.status <> com.tinqa.procurement.barcode.enums.BarcodeStatus.VOID ORDER BY b.id")
    List<Barcode> findLiveByCodesForUpdate(@Param("codes") Collection<String> codes);

    // Read-only: the live units carrying the codes
    @Query("SELECT b FROM Barcode b WHERE b.code IN :codes AND b.status <> com.tinqa.procurement.barcode.enums.BarcodeStatus.VOID ORDER BY b.id")
    List<Barcode> findLiveByCodes(@Param("codes") Collection<String> codes);

    // A product's units in one status, oldest first (uses idx_barcodes_product)
    List<Barcode> findByUnitTypeAndEcommerceProductIdAndStatusOrderByIdAsc(BarcodeUnitType unitType, Long ecommerceProductId,
                                                                            BarcodeStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Barcode b WHERE b.id IN :ids ORDER BY b.id")
    List<Barcode> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    // Read-only: the codes of a product's units in the given statuses, oldest first
    @Query("SELECT b.code FROM Barcode b WHERE b.unitType = :unitType AND b.ecommerceProductId = :productId AND b.status IN :statuses ORDER BY b.id")
    List<String> findProductUnitCodes(@Param("unitType") BarcodeUnitType unitType, @Param("productId") Long productId,
                                      @Param("statuses") Collection<BarcodeStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Barcode b WHERE b.unitType = :unitType AND b.ecommerceProductId = :productId AND b.status IN :statuses ORDER BY b.id")
    List<Barcode> findProductUnitsForUpdate(@Param("unitType") BarcodeUnitType unitType, @Param("productId") Long productId,
                                            @Param("statuses") Collection<BarcodeStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Barcode b WHERE b.fittedInto.id IN :unitIds AND b.status = com.tinqa.procurement.barcode.enums.BarcodeStatus.FITTED ORDER BY b.id")
    List<Barcode> findPartsForUpdate(@Param("unitIds") Collection<Long> unitIds);

    long countByStockIdAndStatusIn(Long stockId, Collection<BarcodeStatus> statuses);

    long countByUnitTypeAndEcommerceProductIdAndStatusIn(BarcodeUnitType unitType, Long ecommerceProductId,
                                                         Collection<BarcodeStatus> statuses);

    // Units in each stock entry with the status (entries without any are absent)
    @Query("""
            SELECT b.stock.id AS stockId, COUNT(b) AS units
            FROM Barcode b
            WHERE b.stock.id IN :stockIds AND b.status = :status
            GROUP BY b.stock.id
            """)
    List<StockCount> countByStock(@Param("stockIds") Collection<Long> stockIds, @Param("status") BarcodeStatus status);

    @EntityGraph(attributePaths = {"item", "stock", "fittedInto"})
    List<Barcode> findByStockIdOrderByIdAsc(Long stockId);

    @EntityGraph(attributePaths = {"item", "stock", "fittedInto"})
    List<Barcode> findByUnitTypeAndEcommerceProductIdOrderByIdAsc(BarcodeUnitType unitType, Long ecommerceProductId);

    @EntityGraph(attributePaths = {"item", "stock", "fittedInto"})
    List<Barcode> findByItemIdAndStatusOrderByIdAsc(Long itemId, BarcodeStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"item", "stock", "fittedInto"})
    List<Barcode> findByFittedIntoIdInOrderByIdAsc(Collection<Long> unitIds);
}
