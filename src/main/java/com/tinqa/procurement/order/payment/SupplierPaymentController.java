package com.tinqa.procurement.order.payment;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.document.dto.DocumentDownload;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Payments to suppliers against an order's payment plan, money they pay back, and refund claims. Everyone who sees
 * orders sees them. Requesting, paying and re-sending a payment, asking the supplier to pay one back and checking the
 * money they paid back need SUPPLIER_PAYMENT_RECORD (Finance Managers); approving a request (in plan order) or a
 * refund's reversal needs SUPPLIER_PAYMENT_APPROVE (a Super Admin, never the requester).
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
                request.getKind() == SupplierPaymentKind.PAYMENT ? "Payment requested; it is paid once a Super Admin approves it"
                        : "Recorded; it counts once someone else confirms it");
    }

    // The Finance Manager's queue: approved requests to pay, and payments the supplier says did not arrive
    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @GetMapping("/payments/to-make")
    public ResponseEntity<ApiResponse<List<SupplierPaymentDTOs.Entry>>> toMake() {
        return ApiResponses.ok(paymentService.toMake(), "Supplier payments to make retrieved successfully");
    }

    // The Finance Manager's queue: money suppliers say they paid back, to check it arrived. Small by design
    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @GetMapping("/payments/repayments-to-check")
    public ResponseEntity<ApiResponse<List<SupplierPaymentDTOs.Entry>>> repaymentsToCheck() {
        return ApiResponses.ok(paymentService.repaymentsToCheck(), "Supplier repayments to check retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PatchMapping("/payments/{paymentId}/repayment-check")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> checkRepayment(@PathVariable Long paymentId,
                                                                              @Valid @RequestBody SupplierPaymentDTOs.RepaymentCheckRequest request) {
        return ApiResponses.ok(paymentService.checkRepayment(paymentId, request, currentUserProvider.getCurrentUser()),
                Boolean.TRUE.equals(request.getReceived()) ? "Repayment confirmed; it counts now and is booked to the company account"
                        : "Sent back to the supplier to check and send again");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PatchMapping("/payments/{paymentId}/paid")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> markPaid(@PathVariable Long paymentId,
                                                                        @Valid @RequestBody SupplierPaymentDTOs.PaidRequest request) {
        return ApiResponses.ok(paymentService.markPaid(paymentId, request, currentUserProvider.getCurrentUser()),
                "Payment recorded; it counts once the supplier confirms they received it");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_RECORD)
    @PostMapping(value = "/payments/{paymentId}/resend", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> resend(@PathVariable Long paymentId,
                                                                      @RequestParam(required = false) String reference,
                                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                                                                      @RequestParam(required = false) String note,
                                                                      @RequestParam(required = false) MultipartFile proof) {
        return ApiResponses.ok(paymentService.resend(paymentId, reference, paidOn, note, proof, currentUserProvider.getCurrentUser()),
                "Payment sent to the supplier again");
    }

    // Always served as an attachment with the stored (validated) content type
    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/payments/{paymentId}/proofs/{documentId}")
    public ResponseEntity<Resource> proof(@PathVariable Long paymentId, @PathVariable Long documentId) {
        return attachment(paymentService.proofFile(paymentId, documentId));
    }

    public static ResponseEntity<Resource> attachment(DocumentDownload download) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
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
        SupplierPaymentDTOs.Entry reversal = paymentService.reverse(paymentId, request, currentUserProvider.getCurrentUser());
        return ApiResponses.created(reversal, reversal.getStatus() == SupplierPaymentStatus.REPAYMENT_DUE
                ? "The supplier is asked to pay it back; a Finance Manager checks it once they do"
                : "Reversal recorded; it counts once a Super Admin confirms it");
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_APPROVE)
    @PatchMapping("/payments/{paymentId}/decision")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> decide(@PathVariable Long paymentId,
                                                                      @Valid @RequestBody SupplierPaymentDTOs.DecisionRequest request) {
        SupplierPaymentDTOs.Entry entry = paymentService.decide(paymentId, request, currentUserProvider.getCurrentUser());
        return ApiResponses.ok(entry, switch (entry.getStatus()) {
            case TO_PAY -> "Payment request approved; a Finance Manager pays it now";
            case APPROVED -> "Payment entry confirmed";
            default -> "Payment entry rejected";
        });
    }

    @RequiresPermission(Permission.SUPPLIER_PAYMENT_APPROVE)
    @PatchMapping("/{orderId}/refund-claim")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Summary>> adjustClaim(@PathVariable Long orderId,
                                                                             @Valid @RequestBody SupplierPaymentDTOs.ClaimAdjustRequest request) {
        return ApiResponses.ok(paymentService.adjustClaim(orderId, request, currentUserProvider.getCurrentUser()), "Refund claim updated");
    }
}
