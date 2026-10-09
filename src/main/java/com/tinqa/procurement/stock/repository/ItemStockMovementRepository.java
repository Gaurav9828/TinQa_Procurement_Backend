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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ItemStockMovement m WHERE m.movementId = :movementId")
    Optional<ItemStockMovement> findByMovementIdForUpdate(@Param("movementId") UUID movementId);
}
