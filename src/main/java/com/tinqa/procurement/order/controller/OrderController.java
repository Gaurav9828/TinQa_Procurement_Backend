package com.tinqa.procurement.order.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

// Which admin level may make each status move is decided by the order workflow in the service
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private static final String STATUS_UPDATED = "Order status updated successfully";

    private final OrderService orderService;

    @RequiresPermission(Permission.PROCUREMENT_ORDER_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> createOrder(@Valid @RequestBody OrderDTOs.CreateRequest request) {
        return ApiResponses.created(orderService.createOrder(request), "Order created successfully");
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> updateOrder(@PathVariable Long id,
                                                                       @Valid @RequestBody OrderDTOs.UpdateRequest request) {
        return ApiResponses.ok(orderService.updateOrder(id, request), "Order updated successfully");
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_UPDATE_STATUS)
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> updateOrderStatus(@PathVariable Long id,
                                                                             @Valid @RequestBody OrderDTOs.UpdateStatusRequest request) {
        return ApiResponses.ok(orderService.updateOrderStatus(id, request), STATUS_UPDATED);
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_APPROVE)
    @PatchMapping("/{id}/approval")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> processAdminL2Approval(@PathVariable Long id,
                                                                                  @Valid @RequestBody OrderDTOs.ApprovalDecisionRequest request) {
        return ApiResponses.ok(orderService.processAdminL2Approval(id, request), STATUS_UPDATED);
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> getOrderById(@PathVariable Long id) {
        return ApiResponses.ok(orderService.getOrderById(id), "Order retrieved successfully");
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    // ?status=QUOTATION&status=PENDING lists only those statuses, oldest first (one call for the Approvals hub's queues)
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getAllOrders(
            @RequestParam(name = "status", required = false) Set<OrderStatus> statuses) {
        List<OrderDTOs.Response> orders = statuses == null || statuses.isEmpty()
                ? orderService.getAllOrders()
                : orderService.getOrdersByStatuses(statuses);
        return ApiResponses.ok(orders, "Orders retrieved successfully");
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/supplier/{supplierId}")
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getOrdersBySupplierId(@PathVariable Long supplierId) {
        return ApiResponses.ok(orderService.getOrdersBySupplierId(supplierId), "Orders retrieved for supplier successfully");
    }

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getOrdersByStatus(@PathVariable OrderStatus status) {
        return ApiResponses.ok(orderService.getOrdersByStatus(status), "Orders retrieved by status successfully");
    }
}
