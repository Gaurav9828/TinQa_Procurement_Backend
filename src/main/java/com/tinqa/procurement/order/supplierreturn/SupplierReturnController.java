package com.tinqa.procurement.order.supplierreturn;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Returns of failed pieces to the supplier. Everyone who sees orders sees them. A Supplier Manager raises, sends again,
 * dispatches and cancels them (SUPPLIER_RETURN_REQUEST); only a Super Admin approves them (SUPPLIER_RETURN_APPROVE,
 * never the raiser). The supplier's steps are under /v1/supplier-portal.
 */
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class SupplierReturnController {

    private final SupplierReturnService returnService;
    private final CurrentUserProvider currentUserProvider;

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/{orderId}/returns")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.OrderReturns>> forOrder(@PathVariable Long orderId) {
        return ApiResponses.ok(returnService.forOrder(orderId), "Order returns retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_REQUEST)
    @PostMapping("/{orderId}/returns")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> raise(@PathVariable Long orderId,
                                                                     @Valid @RequestBody SupplierReturnDTOs.RaiseRequest request) {
        return ApiResponses.created(returnService.raise(orderId, request, currentUserProvider.getCurrentUser()),
                "Return raised; it goes to the supplier once a Super Admin approves it");
    }

    // The Super Admin's queue: only returns waiting for a decision. Small by design
    @RequiresPermission(Permission.SUPPLIER_RETURN_APPROVE)
    @GetMapping("/returns/waiting")
    public ResponseEntity<ApiResponse<List<SupplierReturnDTOs.Return>>> waiting() {
        return ApiResponses.ok(returnService.waiting(), "Returns waiting for approval retrieved successfully");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_APPROVE)
    @PatchMapping("/returns/{returnId}/decision")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> decide(@PathVariable Long returnId,
                                                                      @Valid @RequestBody SupplierReturnDTOs.DecisionRequest request) {
        SupplierReturnDTOs.Return decided = returnService.decide(returnId, request, currentUserProvider.getCurrentUser());
        return ApiResponses.ok(decided, decided.getStatus() == SupplierReturnStatus.REJECTED ? "Return rejected"
                : "Return approved and sent to the supplier");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_REQUEST)
    @PatchMapping("/returns/{returnId}/resend")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> resend(@PathVariable Long returnId,
                                                                      @Valid @RequestBody SupplierReturnDTOs.NoteRequest request) {
        return ApiResponses.ok(returnService.resend(returnId, request, currentUserProvider.getCurrentUser()), "Return sent to the supplier again");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_REQUEST)
    @PatchMapping("/returns/{returnId}/cancel")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> cancel(@PathVariable Long returnId,
                                                                      @Valid @RequestBody SupplierReturnDTOs.NoteRequest request) {
        return ApiResponses.ok(returnService.cancel(returnId, request, currentUserProvider.getCurrentUser()), "Return cancelled");
    }

    @RequiresPermission(Permission.SUPPLIER_RETURN_REQUEST)
    @PatchMapping("/returns/{returnId}/dispatch")
    public ResponseEntity<ApiResponse<SupplierReturnDTOs.Return>> dispatch(@PathVariable Long returnId,
                                                                        @Valid @RequestBody SupplierReturnDTOs.DispatchRequest request) {
        return ApiResponses.ok(returnService.dispatch(returnId, request, currentUserProvider.getCurrentUser()),
                "Dispatch recorded; the supplier confirms once the pieces arrive");
    }
}
