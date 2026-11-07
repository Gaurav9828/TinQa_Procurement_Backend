package com.tinqa.procurement.stock.controller;

import com.tinqa.procurement.barcode.service.ProductUnitCheck;
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
@RequestMapping("/v1/internal/item-stock")
@PreAuthorize(Authorities.INTERNAL_SERVICE)
@RequiredArgsConstructor
public class InternalItemStockMovementController {

    // Ecommerce products' item stock movements; products are "product:<id>" under this source
    private static final String ECOMMERCE_PRODUCT = "ECOMMERCE_PRODUCT";

    private final ItemStockMovementService movementService;
    private final ProductUnitCheck productUnitCheck;

    @PostMapping("/movements")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> applyMovement(
            @Valid @RequestBody ItemStockMovementDTOs.MovementRequest request) {
        return ApiResponses.ok(movementService.applyMovement(request), "Item stock movement applied");
    }

    @PostMapping("/movements/{movementId}/reverse")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> reverseMovement(
            @PathVariable UUID movementId,
            @Valid @RequestBody(required = false) ItemStockMovementDTOs.ReverseRequest request) {
        return ApiResponses.ok(movementService.reverseMovement(movementId, request), "Item stock movement reversed");
    }

    @GetMapping("/movements/{movementId}")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.MovementResponse>> getMovement(@PathVariable UUID movementId) {
        return ApiResponses.ok(movementService.getMovement(movementId), "Item stock movement retrieved");
    }

    // What a product still holds, so Ecommerce can tell a released (rejected) product from one with units in stock
    @GetMapping("/products/{productId}/held")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.ProductHoldings>> productHoldings(@PathVariable Long productId) {
        return ApiResponses.ok(movementService.productHoldings(ECOMMERCE_PRODUCT, productId), "Product holdings retrieved");
    }

    // Which codes of a product-unit stock entry draft can't be used, before it is saved; reads only, locks nothing.
    // The binding check runs again when the movement is applied.
    @PostMapping("/product-units/check")
    public ResponseEntity<ApiResponse<ItemStockMovementDTOs.ProductUnitCheckResponse>> checkProductUnits(
            @Valid @RequestBody ItemStockMovementDTOs.ProductUnitCheckRequest request) {
        return ApiResponses.ok(productUnitCheck.check(request), "Product units checked");
    }
}
