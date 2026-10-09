package com.tinqa.procurement.stock.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import com.tinqa.procurement.stock.service.ItemStockMovementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Service-to-service API (authenticated by InternalApiKeyFilter, not by admin JWTs).
 */
@RestController
@RequestMapping("/v1/internal/item-stock/movements")
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
@RequiredArgsConstructor
public class InternalItemStockMovementController {

    private final ItemStockMovementService movementService;

    @PostMapping
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> applyMovement(
            @Valid @RequestBody ItemStockMovementDTOs.MovementRequest request,
            HttpServletRequest servletRequest) {
        ItemStockMovementDTOs.MovementResponse data = movementService.applyMovement(request);
        return ResponseEntity.ok(ApiResponse.<ItemStockMovementDTOs.MovementResponse>builder()
                .success(true)
                .message("Item stock movement applied")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @PostMapping("/{movementId}/reverse")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> reverseMovement(
            @PathVariable UUID movementId,
            @Valid @RequestBody(required = false) ItemStockMovementDTOs.ReverseRequest request,
            HttpServletRequest servletRequest) {
        ItemStockMovementDTOs.MovementResponse data = movementService.reverseMovement(movementId, request);
        return ResponseEntity.ok(ApiResponse.<ItemStockMovementDTOs.MovementResponse>builder()
                .success(true)
                .message("Item stock movement reversed")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping("/{movementId}")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> getMovement(
            @PathVariable UUID movementId,
            HttpServletRequest servletRequest) {
        ItemStockMovementDTOs.MovementResponse data = movementService.getMovement(movementId);
        return ResponseEntity.ok(ApiResponse.<ItemStockMovementDTOs.MovementResponse>builder()
                .success(true)
                .message("Item stock movement retrieved")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }
}
