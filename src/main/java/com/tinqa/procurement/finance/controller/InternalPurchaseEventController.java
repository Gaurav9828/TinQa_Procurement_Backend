package com.tinqa.procurement.finance.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.finance.dto.PurchaseEventDTOs;
import com.tinqa.procurement.finance.service.PurchaseEventService;
import com.tinqa.procurement.security.config.Authorities;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Service-to-service API (authenticated by InternalApiKeyFilter): the Ecommerce service posts supplier purchases and
 * supplier refunds to the company account from these events.
 */
@RestController
@RequestMapping("/v1/internal/finance")
@PreAuthorize(Authorities.INTERNAL_SERVICE)
@RequiredArgsConstructor
public class InternalPurchaseEventController {

    private final PurchaseEventService purchaseEventService;

    @GetMapping("/purchase-events")
    public ResponseEntity<ApiResponse<PurchaseEventDTOs.PurchaseEventPage>> purchaseEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponses.ok(purchaseEventService.eventsSince(since, limit), "Purchase events retrieved");
    }
}
