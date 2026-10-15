package com.tinqa.procurement.productapproval.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import com.tinqa.procurement.productapproval.service.ProductApprovalService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Admin L2 approval of products created in the Ecommerce service. The UI activates the product in Ecommerce
 * only after a request here is APPROVED.
 */
@RestController
@RequestMapping("/v1/product-approvals")
@RequiredArgsConstructor
public class ProductApprovalController {

    private final ProductApprovalService approvalService;

    @RequiresPermission(Permission.PRODUCT_APPROVAL_SUBMIT)
    @PostMapping
    public ResponseEntity<ApiResponse<ProductApprovalDTOs.Response>> submit(@Valid @RequestBody ProductApprovalDTOs.SubmitRequest request) {
        return ApiResponses.created(approvalService.submit(request), "Product submitted for Admin L2 approval");
    }

    // Requests with the given status (default PENDING), oldest first
    @RequiresPermission(Permission.PRODUCT_APPROVAL_DECIDE)
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductApprovalDTOs.Response>>> getByStatus(
            @RequestParam(required = false) ProductApprovalStatus status) {
        return ApiResponses.ok(approvalService.getByStatus(status), "Product approval requests retrieved");
    }

    // ?productIds=1,2,3: the latest request of each product
    @RequiresPermission(Permission.PRODUCT_VIEW)
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<List<ProductApprovalDTOs.Response>>> getLatestForProducts(
            @RequestParam(required = false) String productIds) {
        return ApiResponses.ok(approvalService.getLatestForProducts(productIds), "Latest product approval requests retrieved");
    }

    @RequiresPermission(Permission.PRODUCT_VIEW)
    @GetMapping("/product/{ecommerceProductId}")
    public ResponseEntity<ApiResponse<List<ProductApprovalDTOs.Response>>> getHistory(@PathVariable Long ecommerceProductId) {
        return ApiResponses.ok(approvalService.getHistory(ecommerceProductId), "Product approval history retrieved");
    }

    @RequiresPermission(Permission.PRODUCT_APPROVAL_DECIDE)
    @PatchMapping("/{id}/decision")
    public ResponseEntity<ApiResponse<ProductApprovalDTOs.Response>> decide(@PathVariable Long id,
                                                                            @Valid @RequestBody ProductApprovalDTOs.DecisionRequest request) {
        ProductApprovalDTOs.Response data = approvalService.decide(id, request);
        return ApiResponses.ok(data, "Product " + data.getStatus().name().toLowerCase(Locale.ROOT) + " successfully");
    }

    // Confirms that approved changes were written to Ecommerce
    @RequiresPermission(Permission.PRODUCT_PUBLISH)
    @PatchMapping("/{id}/applied")
    public ResponseEntity<ApiResponse<ProductApprovalDTOs.Response>> markApplied(@PathVariable Long id) {
        return ApiResponses.ok(approvalService.markApplied(id), "Approved product changes marked as applied");
    }
}
