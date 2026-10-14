package com.tinqa.procurement.productapproval.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import com.tinqa.procurement.productapproval.service.ProductApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Admin L2 approval of products created in the Ecommerce service. The UI activates the product in Ecommerce
 * only after a request here is APPROVED.
 */
@RestController
@RequestMapping("/v1/product-approvals")
@RequiredArgsConstructor
public class ProductApprovalController {

    private final ProductApprovalService approvalService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<ProductApprovalDTOs.Response>> submit(
            @Valid @RequestBody ProductApprovalDTOs.SubmitRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ok(approvalService.submit(request), "Product submitted for Admin L2 approval", httpRequest));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<List<ProductApprovalDTOs.Response>>> getByStatus(
            @RequestParam(required = false) ProductApprovalStatus status,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ok(approvalService.getByStatus(status), "Product approval requests retrieved", httpRequest));
    }

    @GetMapping("/latest")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<List<ProductApprovalDTOs.Response>>> getLatestForProducts(
            @RequestParam(required = false) String productIds,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ok(approvalService.getLatestForProducts(productIds),
                "Latest product approval requests retrieved", httpRequest));
    }

    @GetMapping("/product/{ecommerceProductId}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<List<ProductApprovalDTOs.Response>>> getHistory(
            @PathVariable Long ecommerceProductId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ok(approvalService.getHistory(ecommerceProductId),
                "Product approval history retrieved", httpRequest));
    }

    @PatchMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<ProductApprovalDTOs.Response>> decide(
            @PathVariable Long id,
            @Valid @RequestBody ProductApprovalDTOs.DecisionRequest request,
            HttpServletRequest httpRequest) {
        ProductApprovalDTOs.Response data = approvalService.decide(id, request);
        return ResponseEntity.ok(ok(data, "Product " + data.getStatus().name().toLowerCase() + " successfully", httpRequest));
    }

    @PatchMapping("/{id}/applied")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<ProductApprovalDTOs.Response>> markApplied(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ok(approvalService.markApplied(id), "Approved product changes marked as applied", httpRequest));
    }

    private <T> ApiResponse<T> ok(T data, String message, HttpServletRequest httpRequest) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(Instant.now())
                .path(httpRequest.getRequestURI())
                .build();
    }
}
