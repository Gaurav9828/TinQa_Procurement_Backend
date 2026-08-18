package com.tinqa.procurement.order.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.enums.OrderStatus;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> createOrder(
            @Valid @RequestBody OrderDTOs.CreateRequest request,
            HttpServletRequest servletRequest) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        OrderDTOs.Response data = orderService.createOrder(request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<OrderDTOs.Response>builder()
                        .success(true)
                        .message("Order created successfully")
                        .data(data)
                        .path(servletRequest.getRequestURI())
                        .build());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> updateOrder(
            @PathVariable Long id,
            @Valid @RequestBody OrderDTOs.UpdateRequest request,
            HttpServletRequest servletRequest) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        OrderDTOs.Response data = orderService.updateOrder(id, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.<OrderDTOs.Response>builder()
                .success(true)
                .message("Order updated successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderDTOs.UpdateStatusRequest request,
            HttpServletRequest servletRequest) {
        Long currentUserId = currentUserProvider.getCurrentUser().getId();
        OrderDTOs.Response data = orderService.updateOrderStatus(id, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.<OrderDTOs.Response>builder()
                .success(true)
                .message("Order status updated successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<OrderDTOs.Response>> getOrderById(
            @PathVariable Long id,
            HttpServletRequest servletRequest) {
        OrderDTOs.Response data = orderService.getOrderById(id);
        return ResponseEntity.ok(ApiResponse.<OrderDTOs.Response>builder()
                .success(true)
                .message("Order retrieved successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getAllOrders(
            HttpServletRequest servletRequest) {
        List<OrderDTOs.Response> data = orderService.getAllOrders();
        return ResponseEntity.ok(ApiResponse.<List<OrderDTOs.Response>>builder()
                .success(true)
                .message("Orders retrieved successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping("/dealer/{dealerId}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getOrdersByDealerId(
            @PathVariable Long dealerId,
            HttpServletRequest servletRequest) {
        List<OrderDTOs.Response> data = orderService.getOrdersByDealerId(dealerId);
        return ResponseEntity.ok(ApiResponse.<List<OrderDTOs.Response>>builder()
                .success(true)
                .message("Orders retrieved for dealer successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }

    @GetMapping("/status/{status}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getOrdersByStatus(
            @PathVariable OrderStatus status,
            HttpServletRequest servletRequest) {
        List<OrderDTOs.Response> data = orderService.getOrdersByStatus(status);
        return ResponseEntity.ok(ApiResponse.<List<OrderDTOs.Response>>builder()
                .success(true)
                .message("Orders retrieved by status successfully")
                .data(data)
                .path(servletRequest.getRequestURI())
                .build());
    }
}