package com.tinqa.procurement.dealerportal;

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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Dealer logins only (DEALER_PORTAL group); every call is limited to the signed-in dealer's own records
@RestController
@RequestMapping("/v1/dealer-portal")
@RequiredArgsConstructor
public class DealerPortalController {

    private final DealerPortalService dealerPortalService;

    @RequiresPermission(Permission.DEALER_PORTAL_VIEW)
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Profile>> profile() {
        return ApiResponses.ok(dealerPortalService.profile(), "Dealer profile retrieved successfully");
    }

    @RequiresPermission(Permission.DEALER_QUOTATION_SUBMIT)
    @GetMapping("/items")
    public ResponseEntity<ApiResponse<List<DealerPortalDTOs.Item>>> items() {
        return ApiResponses.ok(dealerPortalService.items(), "Items retrieved successfully");
    }

    @RequiresPermission(Permission.DEALER_PORTAL_VIEW)
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<DealerPortalDTOs.Order>>> orders() {
        return ApiResponses.ok(dealerPortalService.orders(), "Orders retrieved successfully");
    }

    @RequiresPermission(Permission.DEALER_PORTAL_VIEW)
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Order>> order(@PathVariable Long orderId) {
        return ApiResponses.ok(dealerPortalService.order(orderId), "Order retrieved successfully");
    }

    @RequiresPermission(Permission.DEALER_ORDER_RESPOND)
    @PostMapping("/orders/{orderId}/response")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Order>> respond(@PathVariable Long orderId,
                                                                       @Valid @RequestBody DealerPortalDTOs.OrderResponseRequest request) {
        DealerPortalDTOs.Order order = dealerPortalService.respond(orderId, request);
        return ApiResponses.ok(order, request.getDecision() == DealerPortalDTOs.OrderDecision.ACCEPT
                ? "Order " + order.getOrderNumber() + " confirmed"
                : "Order " + order.getOrderNumber() + " rejected");
    }

    @RequiresPermission(Permission.DEALER_ORDER_SHIP)
    @PostMapping("/orders/{orderId}/ship")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Order>> ship(@PathVariable Long orderId) {
        DealerPortalDTOs.Order order = dealerPortalService.ship(orderId);
        return ApiResponses.ok(order, "Order " + order.getOrderNumber() + " marked as shipped");
    }

    @RequiresPermission(Permission.DEALER_QUOTATION_SUBMIT)
    @PostMapping("/quotations")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Order>> createQuotation(
            @Valid @RequestBody DealerPortalDTOs.QuotationRequest request) {
        return ApiResponses.created(dealerPortalService.createQuotation(request), "Quotation created successfully");
    }

    @RequiresPermission(Permission.DEALER_QUOTATION_CHANGE_REQUEST)
    @PostMapping("/quotations/{orderId}/change-requests")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Change>> requestChange(@PathVariable Long orderId,
                                                                              @Valid @RequestBody DealerPortalDTOs.ChangeRequest request) {
        return ApiResponses.created(dealerPortalService.requestChange(orderId, request),
                "Change sent for approval. Your quotation stays as it is until a dealer manager approves it.");
    }

    @RequiresPermission(Permission.DEALER_QUOTATION_CHANGE_REQUEST)
    @PatchMapping("/change-requests/{requestId}/withdraw")
    public ResponseEntity<ApiResponse<DealerPortalDTOs.Change>> withdrawChange(@PathVariable Long requestId) {
        return ApiResponses.ok(dealerPortalService.withdrawChange(requestId), "Change withdrawn");
    }
}
