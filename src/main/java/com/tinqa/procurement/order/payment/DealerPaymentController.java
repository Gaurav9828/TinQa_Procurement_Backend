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
 * Payments to dealers against an order's payment plan, refunds they pay back, and refund claims. Everyone who sees
 * orders sees them; recording needs DEALER_PAYMENT_RECORD and confirming DEALER_PAYMENT_APPROVE (someone else).
 */
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class DealerPaymentController {

    private final DealerPaymentService paymentService;
    private final CurrentUserProvider currentUserProvider;

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/{orderId}/payments")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.OrderPayments>> forOrder(@PathVariable Long orderId) {
        return ApiResponses.ok(paymentService.forOrder(orderId), "Order payments retrieved successfully");
    }

    @RequiresPermission(Permission.DEALER_PAYMENT_RECORD)
    @PostMapping("/{orderId}/payments")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.Entry>> record(@PathVariable Long orderId,
                                                                      @Valid @RequestBody DealerPaymentDTOs.RecordRequest request) {
        return ApiResponses.created(paymentService.record(orderId, request, currentUserProvider.getCurrentUser()),
                "Recorded; it counts once someone else confirms it");
    }

    // The Approvals Hub queue: only waiting entries are listed
    @RequiresPermission(Permission.DEALER_PAYMENT_APPROVE)
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<List<DealerPaymentDTOs.Entry>>> waiting(
            @RequestParam(defaultValue = "PENDING_APPROVAL") DealerPaymentStatus status) {
        if (status != DealerPaymentStatus.PENDING_APPROVAL) {
            throw ApiErrors.fieldError("status", "Only entries waiting for confirmation (PENDING_APPROVAL) are listed here; an order's own entries are under its payments.");
        }
        return ApiResponses.ok(paymentService.waiting(), "Dealer payments waiting for confirmation retrieved successfully");
    }

    @RequiresPermission(Permission.DEALER_PAYMENT_RECORD)
    @PutMapping("/payments/{paymentId}")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.Entry>> update(@PathVariable Long paymentId,
                                                                      @Valid @RequestBody DealerPaymentDTOs.RecordRequest request) {
        return ApiResponses.ok(paymentService.update(paymentId, request, currentUserProvider.getCurrentUser()), "Payment entry corrected");
    }

    @RequiresPermission(Permission.DEALER_PAYMENT_RECORD)
    @PatchMapping("/payments/{paymentId}/withdraw")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.Entry>> withdraw(@PathVariable Long paymentId) {
        return ApiResponses.ok(paymentService.withdraw(paymentId, currentUserProvider.getCurrentUser()), "Payment entry withdrawn");
    }

    @RequiresPermission(Permission.DEALER_PAYMENT_RECORD)
    @PostMapping("/payments/{paymentId}/reversal")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.Entry>> reverse(@PathVariable Long paymentId,
                                                                       @Valid @RequestBody DealerPaymentDTOs.ReverseRequest request) {
        return ApiResponses.created(paymentService.reverse(paymentId, request, currentUserProvider.getCurrentUser()),
                "Reversal recorded; it counts once someone else confirms it");
    }

    @RequiresPermission(Permission.DEALER_PAYMENT_APPROVE)
    @PatchMapping("/payments/{paymentId}/decision")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.Entry>> decide(@PathVariable Long paymentId,
                                                                      @Valid @RequestBody DealerPaymentDTOs.DecisionRequest request) {
        DealerPaymentDTOs.Entry entry = paymentService.decide(paymentId, request, currentUserProvider.getCurrentUser());
        return ApiResponses.ok(entry, entry.getStatus() == DealerPaymentStatus.APPROVED ? "Payment entry confirmed" : "Payment entry rejected");
    }

    @RequiresPermission(Permission.DEALER_PAYMENT_APPROVE)
    @PatchMapping("/{orderId}/refund-claim")
    public ResponseEntity<ApiResponse<DealerPaymentDTOs.Summary>> adjustClaim(@PathVariable Long orderId,
                                                                             @Valid @RequestBody DealerPaymentDTOs.ClaimAdjustRequest request) {
        return ApiResponses.ok(paymentService.adjustClaim(orderId, request, currentUserProvider.getCurrentUser()), "Refund claim updated");
    }
}
