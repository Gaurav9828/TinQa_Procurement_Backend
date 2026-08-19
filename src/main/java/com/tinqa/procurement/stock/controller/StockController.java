package com.tinqa.procurement.stock.controller;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.document.constant.DocumentStatus;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.service.StockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/v1/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> createStockFromOrder(
            @Valid @RequestBody StockDTOs.CreateFromOrderRequest request,
            HttpServletRequest servletRequest) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        StockDTOs.Response data = stockService.createStockFromOrder(request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<StockDTOs.Response>builder()
                        .success(true)
                        .message("Stock created from Order successfully and submitted for Admin L2 approval")
                        .data(data)
                        .path(servletRequest.getRequestURI())
                        .build());
    }

    @PostMapping("/{id}/add-quantity")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> addStockQuantity(
            @PathVariable Long id,
            @Valid @RequestBody StockDTOs.QuantityAdjustmentRequest request,
            HttpServletRequest servletRequest) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        StockDTOs.Response data = stockService.addStockQuantity(id, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.<StockDTOs.Response>builder()
                .success(true)
                .message("Stock quantity incremented successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @PostMapping("/{id}/reduce-quantity")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> reduceStockQuantity(
            @PathVariable Long id,
            @Valid @RequestBody StockDTOs.QuantityAdjustmentRequest request,
            HttpServletRequest servletRequest) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        StockDTOs.Response data = stockService.reduceStockQuantity(id, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.<StockDTOs.Response>builder()
                .success(true)
                .message("Stock quantity reduced successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @PostMapping("/{id}/approval")
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> processAdminL2Approval(
            @PathVariable Long id,
            @Valid @RequestBody StockDTOs.ApprovalDecisionRequest request,
            HttpServletRequest servletRequest) {
        Long adminUserId = currentUserProvider.getCurrentUser().getId();
        StockDTOs.Response data = stockService.processAdminL2Approval(id, request, adminUserId);
        return ResponseEntity.ok(ApiResponse.<StockDTOs.Response>builder()
                .success(true)
                .message("Stock L2 approval status updated")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> getStockById(
            @PathVariable Long id,
            HttpServletRequest servletRequest) {
        StockDTOs.Response data = stockService.getStockById(id);
        return ResponseEntity.ok(ApiResponse.<StockDTOs.Response>builder()
                .success(true)
                .message("Stock details retrieved successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<StockDTOs.Response>>> getAllStocks(
            @RequestParam(required = false) ApprovalStatus status,
            HttpServletRequest servletRequest) {
        List<StockDTOs.Response> data = Objects.nonNull(status) ? stockService.getAllStocksByStatus(status) : stockService.getAllStocks();

        return ResponseEntity.ok(ApiResponse.<List<StockDTOs.Response>>builder()
                .success(true)
                .message("Stock list retrieved successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> updateStock(
            @PathVariable Long id,
            @Valid @RequestBody StockDTOs.UpdateRequest request,
            HttpServletRequest servletRequest) { // Or extract from SecurityContext
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        StockDTOs.Response response = stockService.updateStock(id, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.<StockDTOs.Response>builder()
                .success(true)
                .message("Stock list retrieved successfully")
                .data(response)
                .path(servletRequest.getRequestURI())
                .build());
    }
}