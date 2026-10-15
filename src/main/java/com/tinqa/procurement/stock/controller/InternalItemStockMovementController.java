package com.tinqa.procurement.stock.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.config.Authorities;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import com.tinqa.procurement.stock.service.ItemStockMovementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service API (authenticated by InternalApiKeyFilter, not by admin JWTs).
 */
@RestController
@RequestMapping("/v1/internal/item-stock/movements")
@PreAuthorize(Authorities.INTERNAL_SERVICE)
@RequiredArgsConstructor
public class InternalItemStockMovementController {

    private final ItemStockMovementService movementService;

    @PostMapping
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> applyMovement(
            @Valid @RequestBody ItemStockMovementDTOs.MovementRequest request) {
        return ApiResponses.ok(movementService.applyMovement(request), "Item stock movement applied");
    }

    @PostMapping("/{movementId}/reverse")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> reverseMovement(
            @PathVariable UUID movementId,
            @Valid @RequestBody(required = false) ItemStockMovementDTOs.ReverseRequest request) {
        return ApiResponses.ok(movementService.reverseMovement(movementId, request), "Item stock movement reversed");
    }

    @GetMapping("/{movementId}")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> getMovement(@PathVariable UUID movementId) {
        return ApiResponses.ok(movementService.getMovement(movementId), "Item stock movement retrieved");
    }
}
