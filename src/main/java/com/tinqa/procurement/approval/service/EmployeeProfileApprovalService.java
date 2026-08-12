package com.tinqa.procurement.approval.service;

import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalResponse;
import com.tinqa.procurement.approval.dto.EmployeeProfileApprovalRequest;

import java.util.List;

public interface EmployeeProfileApprovalService {

    List<EmployeeProfileApprovalResponse> getPendingRequests();

    EmployeeProfileApprovalResponse getPendingRequest(Long requestId);

    EmployeeProfileApprovalResponse processRequest(
            Long requestId,
            EmployeeProfileApprovalRequest request
    );
}