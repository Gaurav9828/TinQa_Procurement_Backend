package com.tinqa.procurement.stock.controller;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.order.service.OrderService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import com.tinqa.procurement.stock.dto.StockDTOs;
import com.tinqa.procurement.stock.service.ItemCostService;
import com.tinqa.procurement.stock.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/stocks")
@RequiredArgsConstructor
public class StockController {

    private static final String AVAILABILITY_RETRIEVED = "Item stock availability retrieved successfully";

    private final StockService stockService;
    private final OrderService orderService;
    private final ItemCostService itemCostService;

    @RequiresPermission(Permission.STOCK_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<StockDTOs.Response>> createStockFromOrder(@Valid @RequestBody StockDTOs.CreateFromOrderRequest request) {
        return ApiResponses.created(stockService.createStockFromOrder(request),
                "Stock created from Order successfully and submitted for Admin L2 approval");
    }

    // Delivered purchase orders not yet received into stock. Gated by STOCK_CREATE rather than PROCUREMENT_ORDER_VIEW,
    // so warehouse staff can receive deliveries without seeing the rest of procurement
    @RequiresPermission(Permission.STOCK_CREATE)
    @GetMapping("/receivable-orders")
    public ResponseEntity<ApiResponse<List<OrderDTOs.Response>>> getReceivableOrders() {
        return ApiResponses.ok(orderService.getOrdersAwaitingStock(), "Delivered orders awaiting stock retrieved successfully");
    }

    @RequiresPermission(Permission.STOCK_ADJUST)
    @PostMapping("/{id}/add-quantity")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> addStockQuantity(@PathVariable Long id,
                                                                            @Valid @RequestBody StockDTOs.QuantityAdjustmentRequest request) {
        return ApiResponses.ok(stockService.addStockQuantity(id, request), "Stock quantity incremented successfully");
    }

    @RequiresPermission(Permission.STOCK_ADJUST)
    @PostMapping("/{id}/reduce-quantity")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> reduceStockQuantity(@PathVariable Long id,
                                                                               @Valid @RequestBody StockDTOs.QuantityAdjustmentRequest request) {
        return ApiResponses.ok(stockService.reduceStockQuantity(id, request), "Stock quantity reduced successfully");
    }

    @RequiresPermission(Permission.STOCK_APPROVE)
    @PostMapping("/{id}/approval")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> processAdminL2Approval(@PathVariable Long id,
                                                                                  @Valid @RequestBody StockDTOs.ApprovalDecisionRequest request) {
        return ApiResponses.ok(stockService.processAdminL2Approval(id, request), "Stock L2 approval status updated");
    }

    @RequiresPermission(Permission.STOCK_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> updateStock(@PathVariable Long id,
                                                                       @Valid @RequestBody StockDTOs.UpdateRequest request) {
        return ApiResponses.ok(stockService.updateStock(id, request), "Stock updated successfully");
    }

    @RequiresPermission(Permission.STOCK_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StockDTOs.Response>> getStockById(@PathVariable Long id) {
        return ApiResponses.ok(stockService.getStockById(id), "Stock details retrieved successfully");
    }

    @RequiresPermission(Permission.STOCK_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<List<StockDTOs.Response>>> getAllStocks(@RequestParam(required = false) ApprovalStatus status) {
        List<StockDTOs.Response> stocks = status != null ? stockService.getAllStocksByStatus(status) : stockService.getAllStocks();
        return ApiResponses.ok(stocks, "Stock list retrieved successfully");
    }

    // Bulk totals for pickers: ?itemIds=7,8,9 limits to those items (default: all active items)
    @RequiresPermission(Permission.STOCK_VIEW)
    @GetMapping("/items/availability")
    public ResponseEntity<ApiResponse<List<StockDTOs.ItemAvailabilitySummary>>> getItemsAvailability(
            @RequestParam(required = false) List<Long> itemIds,
            @RequestParam(defaultValue = "false") boolean inStockOnly) {
        return ApiResponses.ok(stockService.getItemsAvailability(itemIds, inStockOnly), AVAILABILITY_RETRIEVED);
    }

    // Landed purchase cost of one unit, for product cost sheets
    @RequiresPermission(Permission.PRODUCT_COST_VIEW)
    @GetMapping("/items/{itemId}/cost")
    public ResponseEntity<ApiResponse<StockDTOs.ItemCostResponse>> getItemCost(@PathVariable Long itemId) {
        return ApiResponses.ok(itemCostService.getItemCost(itemId), "Item cost retrieved successfully");
    }

    @RequiresPermission(Permission.STOCK_VIEW)
    @GetMapping("/items/{itemId}/availability")
    public ResponseEntity<ApiResponse<StockDTOs.ItemAvailabilityResponse>> getItemAvailability(@PathVariable Long itemId) {
        return ApiResponses.ok(stockService.getItemAvailability(itemId), AVAILABILITY_RETRIEVED);
    }
}
