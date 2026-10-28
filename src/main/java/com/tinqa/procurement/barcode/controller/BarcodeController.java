package com.tinqa.procurement.barcode.controller;

import com.tinqa.procurement.barcode.dto.BarcodeDTOs;
import com.tinqa.procurement.barcode.enums.BarcodeRequestStatus;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.service.BarcodeService;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
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

/**
 * Barcodes of every piece of item stock and every product unit. They are entered with the stock or product change
 * that creates the units (see /v1/stocks and the Ecommerce product endpoints); here they are traced, listed, and
 * corrected through Super Admin decisions.
 */
@RestController
@RequestMapping("/v1/barcodes")
@RequiredArgsConstructor
public class BarcodeController {

    private final BarcodeService barcodeService;

    @RequiresPermission(Permission.BARCODE_VIEW)
    @GetMapping("/trace/{code}")
    public ResponseEntity<ApiResponse<BarcodeDTOs.Trace>> trace(@PathVariable String code) {
        return ApiResponses.ok(barcodeService.trace(code), "Barcode traced successfully");
    }

    // ?stockId=: a stock entry's units; ?itemId=&status=: an item's units with that status (IN_STOCK by default)
    @RequiresPermission(Permission.BARCODE_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<List<BarcodeDTOs.Unit>>> list(@RequestParam(required = false) Long stockId,
                                                                    @RequestParam(required = false) Long itemId,
                                                                    @RequestParam(required = false) BarcodeStatus status) {
        return ApiResponses.ok(barcodeService.list(stockId, itemId, status), "Barcodes retrieved successfully");
    }

    @RequiresPermission(Permission.BARCODE_VIEW)
    @GetMapping("/products/{ecommerceProductId}")
    public ResponseEntity<ApiResponse<List<BarcodeDTOs.ProductUnit>>> productUnits(@PathVariable Long ecommerceProductId) {
        return ApiResponses.ok(barcodeService.productUnits(ecommerceProductId), "Product unit barcodes retrieved successfully");
    }

    @RequiresPermission(Permission.BARCODE_REQUEST)
    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<BarcodeDTOs.RequestResponse>> createRequest(@Valid @RequestBody BarcodeDTOs.CreateRequest request) {
        BarcodeDTOs.RequestResponse data = barcodeService.createRequest(request);
        return ApiResponses.created(data, data.getStatus() == BarcodeRequestStatus.APPROVED
                ? "Barcode change applied" : "Barcode request sent to a Super Admin for approval");
    }

    // Requests with the given status (default PENDING), oldest first
    @RequiresPermission(Permission.BARCODE_APPROVE)
    @GetMapping("/requests")
    public ResponseEntity<ApiResponse<List<BarcodeDTOs.RequestResponse>>> getRequests(
            @RequestParam(required = false) BarcodeRequestStatus status) {
        return ApiResponses.ok(barcodeService.getRequests(status), "Barcode requests retrieved successfully");
    }

    @RequiresPermission(Permission.BARCODE_APPROVE)
    @PatchMapping("/requests/{id}/decision")
    public ResponseEntity<ApiResponse<BarcodeDTOs.RequestResponse>> decide(@PathVariable Long id,
                                                                          @Valid @RequestBody BarcodeDTOs.DecisionRequest request) {
        BarcodeDTOs.RequestResponse data = barcodeService.decide(id, request);
        return ApiResponses.ok(data, data.getStatus() == BarcodeRequestStatus.APPROVED
                ? "Barcode request approved" : "Barcode request rejected");
    }
}
