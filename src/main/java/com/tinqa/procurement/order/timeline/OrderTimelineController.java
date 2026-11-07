package com.tinqa.procurement.order.timeline;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An order's flow history for everyone who sees orders (the supplier's own view is in the supplier portal). */
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderTimelineController {

    private final OrderTimelineService timelineService;

    @RequiresPermission(Permission.PROCUREMENT_ORDER_VIEW)
    @GetMapping("/{orderId}/timeline")
    public ResponseEntity<ApiResponse<OrderTimelineDTOs.Timeline>> timeline(@PathVariable Long orderId) {
        return ApiResponses.ok(timelineService.forStaff(orderId), "Order timeline retrieved successfully");
    }
}
