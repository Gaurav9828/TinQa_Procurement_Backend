package com.tinqa.procurement.order.change;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Suppliers' quotation changes, as the staff who review them see them
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class QuotationChangeController {

    private final QuotationChangeService quotationChangeService;
    private final CurrentUserProvider currentUserProvider;

    // The approval queue (status=PENDING), or every request
    @RequiresPermission(Permission.SUPPLIER_QUOTATION_CHANGE_APPROVE)
    @GetMapping("/change-requests")
    public ResponseEntity<ApiResponse<List<OrderChangeDTOs.Response>>> getChangeRequests(
            @RequestParam(required = false) OrderChangeStatus status) {
        return ApiResponses.ok(quotationChangeService.list(status), "Quotation changes retrieved successfully");
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/{orderId}/change-requests")
    public ResponseEntity<ApiResponse<List<OrderChangeDTOs.Response>>> getOrderChangeRequests(@PathVariable Long orderId) {
        return ApiResponses.ok(quotationChangeService.forOrder(orderId), "Quotation changes retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_QUOTATION_CHANGE_APPROVE)
    @PatchMapping("/change-requests/{requestId}/decision")
    public ResponseEntity<ApiResponse<OrderChangeDTOs.Response>> decide(@PathVariable Long requestId,
                                                                        @Valid @RequestBody OrderChangeDTOs.DecisionRequest request) {
        OrderChangeDTOs.Response response = quotationChangeService.decide(requestId, request, currentUserProvider.getCurrentUser());
        return ApiResponses.ok(response, response.getStatus() == OrderChangeStatus.APPROVED
                ? "Change approved and applied to the quotation" : "Change rejected");
    }
}
