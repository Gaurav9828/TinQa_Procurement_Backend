package com.tinqa.procurement.approval.summary;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.config.Authorities;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The Approvals Hub badges: one call for every queue this service holds. Open to any signed-in account, because
 * each queue is filtered by its own permissions (someone who decides nothing gets an empty map).
 */
@RestController
@RequestMapping("/v1/approvals")
@PreAuthorize(Authorities.AUTHENTICATED)
@RequiredArgsConstructor
public class ApprovalSummaryController {

    private final ApprovalSummaryService summaryService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Long>>> summary() {
        return ApiResponses.ok(summaryService.summary(), "Approval queue counts fetched successfully");
    }
}
