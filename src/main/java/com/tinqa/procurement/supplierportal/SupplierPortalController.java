package com.tinqa.procurement.supplierportal;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.order.payment.SupplierPaymentDTOs;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Supplier logins only (SUPPLIER_PORTAL group); every call is limited to the signed-in supplier's own records
@RestController
@RequestMapping("/v1/supplier-portal")
@RequiredArgsConstructor
public class SupplierPortalController {

    private final SupplierPortalService supplierPortalService;

    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Profile>> profile() {
        return ApiResponses.ok(supplierPortalService.profile(), "Supplier profile retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_QUOTATION_SUBMIT)
    @GetMapping("/items")
    public ResponseEntity<ApiResponse<List<SupplierPortalDTOs.Item>>> items() {
        return ApiResponses.ok(supplierPortalService.items(), "Items retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<SupplierPortalDTOs.Order>>> orders() {
        return ApiResponses.ok(supplierPortalService.orders(), "Orders retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Order>> order(@PathVariable Long orderId) {
        return ApiResponses.ok(supplierPortalService.order(orderId), "Order retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/orders/{orderId}/payments")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.OrderPayments>> payments(@PathVariable Long orderId) {
        return ApiResponses.ok(supplierPortalService.payments(orderId), "Order payments retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_ORDER_RESPOND)
    @PostMapping("/orders/{orderId}/response")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Order>> respond(@PathVariable Long orderId,
                                                                       @Valid @RequestBody SupplierPortalDTOs.OrderResponseRequest request) {
        SupplierPortalDTOs.Order order = supplierPortalService.respond(orderId, request);
        return ApiResponses.ok(order, request.getDecision() == SupplierPortalDTOs.OrderDecision.ACCEPT
                ? "Order " + order.getOrderNumber() + " confirmed"
                : "Order " + order.getOrderNumber() + " rejected");
    }

    @RequiresPermission(Permission.SUPPLIER_ORDER_SHIP)
    @PostMapping("/orders/{orderId}/ship")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Order>> ship(@PathVariable Long orderId) {
        SupplierPortalDTOs.Order order = supplierPortalService.ship(orderId);
        return ApiResponses.ok(order, "Order " + order.getOrderNumber() + " marked as shipped");
    }

    @RequiresPermission(Permission.SUPPLIER_QUOTATION_SUBMIT)
    @PostMapping("/quotations")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Order>> createQuotation(
            @Valid @RequestBody SupplierPortalDTOs.QuotationRequest request) {
        return ApiResponses.created(supplierPortalService.createQuotation(request), "Quotation created successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_QUOTATION_CHANGE_REQUEST)
    @PostMapping("/quotations/{orderId}/change-requests")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Change>> requestChange(@PathVariable Long orderId,
                                                                              @Valid @RequestBody SupplierPortalDTOs.ChangeRequest request) {
        return ApiResponses.created(supplierPortalService.requestChange(orderId, request),
                "Change sent for approval. Your quotation stays as it is until a supplier manager approves it.");
    }

    @RequiresPermission(Permission.SUPPLIER_QUOTATION_CHANGE_REQUEST)
    @PatchMapping("/change-requests/{requestId}/withdraw")
    public ResponseEntity<ApiResponse<SupplierPortalDTOs.Change>> withdrawChange(@PathVariable Long requestId) {
        return ApiResponses.ok(supplierPortalService.withdrawChange(requestId), "Change withdrawn");
    }
}
