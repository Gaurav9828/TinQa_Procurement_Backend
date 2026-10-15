package com.tinqa.procurement.approval.controller;

import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalRequest;
import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalResponse;
import com.tinqa.procurement.approval.service.EmployeeProfileApprovalService;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/profile-approvals")
@RequiresPermission(Permission.EMPLOYEE_PROFILE_CHANGE_APPROVE)
@RequiredArgsConstructor
public class EmployeeProfileApprovalController {

    private final EmployeeProfileApprovalService approvalService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmployeeProfileApprovalResponse>>> getPendingRequests() {
        return ApiResponses.ok(approvalService.getPendingRequests(), "Pending profile approvals fetched successfully");
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<EmployeeProfileApprovalResponse>> getPendingRequest(@PathVariable Long requestId) {
        return ApiResponses.ok(approvalService.getPendingRequest(requestId), "Profile approval request fetched successfully");
    }

    @PutMapping("/{requestId}")
    public ResponseEntity<ApiResponse<EmployeeProfileApprovalResponse>> processRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody EmployeeProfileApprovalRequest request) {
        EmployeeProfileApprovalResponse response = approvalService.processRequest(requestId, request);
        return ApiResponses.ok(response, request.isApproved()
                ? "Profile Changes Approved Successfully"
                : "Profile Changes Rejected Successfully");
    }
}
