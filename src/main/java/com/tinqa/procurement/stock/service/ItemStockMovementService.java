package com.tinqa.procurement.stock.service;

import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface ItemStockMovementService {

    /**
     * Applies all lines in one transaction: positive lines consume item stock (FIFO by arrival), negative lines
     * return units to the batches this source/reference consumed (LIFO), capped at what it still holds.
     * Idempotent on movementId.
     */
    ItemStockMovementDTOs.MovementResponse applyMovement(ItemStockMovementDTOs.MovementRequest request);

    /**
     * Undoes an applied movement exactly. Idempotent; an unknown movementId is recorded as reversed so that
     * a late-arriving apply with that id becomes a no-op.
     */
    ItemStockMovementDTOs.MovementResponse reverseMovement(UUID movementId, ItemStockMovementDTOs.ReverseRequest request);

    ItemStockMovementDTOs.MovementResponse getMovement(UUID movementId);

    /**
     * Returns every unit the reference currently holds to the batches it came from.
     *
     * @return the recorded movement, or null when the reference held nothing
     */
    ItemStockMovementDTOs.MovementResponse releaseReference(String source, String reference, String performedBy);

    /**
     * Consumes or returns whatever is needed for the reference to hold exactly targetUnitsByItem
     * (items not listed are returned in full). Throws INSUFFICIENT_ITEM_STOCK (409) without moving anything.
     *
     * @return the recorded movement, or null when the reference already holds the target
     */
    ItemStockMovementDTOs.MovementResponse reconcileReference(String source, String reference,
                                                              Map<Long, BigDecimal> targetUnitsByItem, String performedBy);
}
