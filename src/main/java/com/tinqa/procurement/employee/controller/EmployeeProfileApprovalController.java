package com.tinqa.procurement.employee.controller;
import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalRequest;
import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalResponse;
import com.tinqa.procurement.approval.service.EmployeeProfileApprovalService;
import com.tinqa.procurement.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/profile-approvals")
@RequiredArgsConstructor
public class EmployeeProfileApprovalController {

    private final EmployeeProfileApprovalService approvalService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<List<EmployeeProfileApprovalResponse>>>
    getPendingRequests(
            HttpServletRequest httpRequest) {

        List<EmployeeProfileApprovalResponse> response =
                approvalService.getPendingRequests();

        return ResponseEntity.ok(
                ApiResponse.<List<EmployeeProfileApprovalResponse>>builder()
                        .success(true)
                        .message("Pending profile approvals fetched successfully")
                        .data(response)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<EmployeeProfileApprovalResponse>>
    getPendingRequest(
            @PathVariable Long requestId,
            HttpServletRequest httpRequest) {

        EmployeeProfileApprovalResponse response =
                approvalService.getPendingRequest(requestId);

        return ResponseEntity.ok(
                ApiResponse.<EmployeeProfileApprovalResponse>builder()
                        .success(true)
                        .message("Profile approval request fetched successfully")
                        .data(response)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }

    @PutMapping("/{requestId}")
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<EmployeeProfileApprovalResponse>>
    processRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody EmployeeProfileApprovalRequest request,
            HttpServletRequest httpRequest) {

        EmployeeProfileApprovalResponse response =
                approvalService.processRequest(
                        requestId,
                        request
                );

        String message =
                "APPROVE".equals(request.getDecision())
                        ? "Profile Changes Approved Successfully"
                        : "Profile Changes Rejected Successfully";

        return ResponseEntity.ok(
                ApiResponse.<EmployeeProfileApprovalResponse>builder()
                        .success(true)
                        .message(message)
                        .data(response)
                        .path(httpRequest.getRequestURI())
                        .build()
        );
    }
}