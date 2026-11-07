package com.tinqa.procurement.supplierportal;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.order.payment.PaymentMethod;
import com.tinqa.procurement.order.payment.SupplierPaymentDTOs;
import com.tinqa.procurement.order.supplierreturn.SupplierReturnDTOs;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.order.payment.SupplierPaymentController;
import com.tinqa.procurement.order.timeline.OrderTimelineDTOs;
import org.springframework.core.io.Resource;
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

    // Always served as an attachment with the stored (validated) content type
    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/payments/{paymentId}/proofs/{documentId}")
    public ResponseEntity<Resource> paymentProof(@PathVariable Long paymentId, @PathVariable Long documentId) {
        return SupplierPaymentController.attachment(supplierPortalService.paymentProof(paymentId, documentId));
    }

    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/orders/{orderId}/timeline")
    public ResponseEntity<ApiResponse<OrderTimelineDTOs.Timeline>> timeline(@PathVariable Long orderId) {
        return ApiResponses.ok(supplierPortalService.timeline(orderId), "Order timeline retrieved successfully");
    }

    // A payment (any method) counts only once the supplier confirms they received it
    @RequiresPermission(Permission.SUPPLIER_PAYMENT_CONFIRM)
    @PatchMapping("/payments/{paymentId}/receipt")
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> confirmReceipt(@PathVariable Long paymentId,
                                                                              @Valid @RequestBody SupplierPaymentDTOs.ReceiptRequest request) {
        return ApiResponses.ok(supplierPortalService.confirmReceipt(paymentId, request), Boolean.TRUE.equals(request.getReceived())
                ? "Thank you: the payment is confirmed" : "Thank you: TinQa will check the payment and send it again");
    }

    // Money back to TinQa: what is asked of the supplier (a reversal, a return's repayment), paid or paid again
    @RequiresPermission(Permission.SUPPLIER_REPAYMENT_SUBMIT)
    @PostMapping(value = "/payments/{paymentId}/repayment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> repay(@PathVariable Long paymentId,
                                                                     @RequestParam(required = false) PaymentMethod method,
                                                                     @RequestParam(required = false) String reference,
                                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                                                                     @RequestParam(required = false) String note,
                                                                     @RequestParam(required = false) MultipartFile receipt) {
        return ApiResponses.ok(supplierPortalService.repay(paymentId, SupplierPaymentDTOs.RepaymentRequest.builder()
                        .method(method).reference(reference).paidOn(paidOn).note(note).build(), receipt),
                "Thank you: TinQa's Finance Manager will check it arrived");
    }

    // A refund the supplier owes on the order's claim, paid on their own
    @RequiresPermission(Permission.SUPPLIER_REPAYMENT_SUBMIT)
    @PostMapping(value = "/orders/{orderId}/repayments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SupplierPaymentDTOs.Entry>> refund(@PathVariable Long orderId,
                                                                      @RequestParam(required = false) BigDecimal amount,
                                                                      @RequestParam(required = false) PaymentMethod method,
                                                                      @RequestParam(required = false) String reference,
                                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn,
                                                                      @RequestParam(required = false) String note,
                                                                      @RequestParam(required = false) MultipartFile receipt) {
        return ApiResponses.created(supplierPortalService.refund(orderId, SupplierPaymentDTOs.RepaymentRequest.builder()
                        .amount(amount).method(method).reference(reference).paidOn(paidOn).note(note).build(), receipt),
                "Thank you: TinQa's Finance Manager will check it arrived");
    }

    @RequiresPermission(Permission.SUPPLIER_PORTAL_VIEW)
    @GetMapping("/orders/{orderId}/returns")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.OrderReturns>> returns(@PathVariable Long orderId) {
        return ApiResponses.ok(supplierPortalService.returns(orderId), "Order returns retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_RESPOND)
    @PatchMapping("/returns/{returnId}/response")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> respondToReturn(@PathVariable Long returnId,
                                                                               @Valid @RequestBody SupplierReturnDTOs.ResponseRequest request) {
        return ApiResponses.ok(supplierPortalService.respondToReturn(returnId, request), Boolean.TRUE.equals(request.getAccepted())
                ? "Return accepted" : "Return declined; TinQa will look at your reason");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_RESPOND)
    @PatchMapping("/returns/{returnId}/received")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> returnArrived(@PathVariable Long returnId,
                                                                             @Valid @RequestBody(required = false) SupplierReturnDTOs.ArrivedRequest request) {
        return ApiResponses.ok(supplierPortalService.returnArrived(returnId, request), "Thank you: the returned pieces are confirmed");
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
