package com.tinqa.procurement.stock.repository;

import com.tinqa.procurement.stock.entity.ItemStockMovement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ItemStockMovementRepository extends JpaRepository<ItemStockMovement, Long> {

    Optional<ItemStockMovement> findByMovementId(UUID movementId);

    /**
     * Serialises every movement of one source/reference (e.g. one Ecommerce product) for the rest of the
     * transaction, so the units it holds are read and changed consistently.
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended('item-stock-ref:' || :source || ':' || :reference, 0)) AS text)",
            nativeQuery = true)
    String lockReference(@Param("source") String source, @Param("reference") String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ItemStockMovement m WHERE m.movementId = :movementId")
    Optional<ItemStockMovement> findByMovementIdForUpdate(@Param("movementId") UUID movementId);
}
