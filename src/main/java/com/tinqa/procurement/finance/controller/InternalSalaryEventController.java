package com.tinqa.procurement.finance.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.finance.dto.SalaryEventDTOs;
import com.tinqa.procurement.finance.service.SalaryEventService;
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
 * Service-to-service API (authenticated by InternalApiKeyFilter): the Ecommerce service posts confirmed salary
 * payments to the company account from these events.
 */
@RestController
@RequestMapping("/v1/internal/finance")
@PreAuthorize(Authorities.INTERNAL_SERVICE)
@RequiredArgsConstructor
public class InternalSalaryEventController {

    private final SalaryEventService salaryEventService;

    @GetMapping("/salary-events")
    public ResponseEntity<ApiResponse<SalaryEventDTOs.SalaryEventPage>> salaryEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponses.ok(salaryEventService.eventsSince(since, limit), "Salary events retrieved");
    }
}
