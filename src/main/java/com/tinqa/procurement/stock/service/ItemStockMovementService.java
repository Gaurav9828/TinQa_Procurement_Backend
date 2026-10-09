package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;

import java.util.UUID;

public interface ItemStockMovementService {

    /**
     * Applies all lines in one transaction: positive lines consume item stock (FIFO by arrival), negative lines
     * return units to the batches this source/reference consumed (LIFO). Idempotent on movementId.
     */
    ItemStockMovementDTOs.MovementResponse applyMovement(ItemStockMovementDTOs.MovementRequest request);

    /**
     * Undoes an applied movement exactly. Idempotent; an unknown movementId is recorded as reversed so that
     * a late-arriving apply with that id becomes a no-op.
     */
    ItemStockMovementDTOs.MovementResponse reverseMovement(UUID movementId, ItemStockMovementDTOs.ReverseRequest request);

    ItemStockMovementDTOs.MovementResponse getMovement(UUID movementId);
}
