package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payments to suppliers against an order's payment plan, refunds they pay back, and refund claims. Everyone who sees
 * orders sees them; recording needs SUPPLIER_PAYMENT_RECORD and confirming SUPPLIER_PAYMENT_APPROVE (someone else).
 */
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class SupplierPaymentController {

    private final SupplierPaymentService paymentService;
    private final CurrentUserProvider currentUserProvider;

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/{orderId}/payments")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.OrderPayments>> forOrder(@PathVariable Long orderId) {
        return ApiResponses.ok(paymentService.forOrder(orderId), "Order payments retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PostMapping("/{orderId}/payments")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> record(@PathVariable Long orderId,
                                                                      @Valid @RequestBody SupplierPaymentDTOs.RecordRequest request) {
        return ApiResponses.created(paymentService.record(orderId, request, currentUserProvider.getCurrentUser()),
                "Recorded; it counts once someone else confirms it");
    }

    // The Approvals Hub queue: only waiting entries are listed
    @RequiresPermission(Permission.SUPPLIER_PAYMENT_APPROVE)
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<List<SupplierPaymentDTOs.Entry>>> waiting(
            @RequestParam(defaultValue = "PENDING_APPROVAL") SupplierPaymentStatus status) {
        if (status != SupplierPaymentStatus.PENDING_APPROVAL) {
            throw ApiErrors.fieldError("status", "Only entries waiting for confirmation (PENDING_APPROVAL) are listed here; an order's own entries are under its payments.");
        }
        return ApiResponses.ok(paymentService.waiting(), "Supplier payments waiting for confirmation retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PutMapping("/payments/{paymentId}")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> update(@PathVariable Long paymentId,
                                                                      @Valid @RequestBody SupplierPaymentDTOs.RecordRequest request) {
        return ApiResponses.ok(paymentService.update(paymentId, request, currentUserProvider.getCurrentUser()), "Payment entry corrected");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PatchMapping("/payments/{paymentId}/withdraw")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> withdraw(@PathVariable Long paymentId) {
        return ApiResponses.ok(paymentService.withdraw(paymentId, currentUserProvider.getCurrentUser()), "Payment entry withdrawn");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PostMapping("/payments/{paymentId}/reversal")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> reverse(@PathVariable Long paymentId,
                                                                       @Valid @RequestBody SupplierPaymentDTOs.ReverseRequest request) {
        return ApiResponses.created(paymentService.reverse(paymentId, request, currentUserProvider.getCurrentUser()),
                "Reversal recorded; it counts once someone else confirms it");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_APPROVE)
    @PatchMapping("/payments/{paymentId}/decision")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> decide(@PathVariable Long paymentId,
                                                                      @Valid @RequestBody SupplierPaymentDTOs.DecisionRequest request) {
        SupplierPaymentDTOs.Entry entry = paymentService.decide(paymentId, request, currentUserProvider.getCurrentUser());
        return ApiResponses.ok(entry, entry.getStatus() == SupplierPaymentStatus.APPROVED ? "Payment entry confirmed" : "Payment entry rejected");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_APPROVE)
    @PatchMapping("/{orderId}/refund-claim")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Summary>> adjustClaim(@PathVariable Long orderId,
                                                                             @Valid @RequestBody SupplierPaymentDTOs.ClaimAdjustRequest request) {
        return ApiResponses.ok(paymentService.adjustClaim(orderId, request, currentUserProvider.getCurrentUser()), "Refund claim updated");
    }
}
