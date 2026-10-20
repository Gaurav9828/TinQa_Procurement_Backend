package com.tinqa.procurement.employee.service;

import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeApprovalDecisionRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface EmployeeService {

    EmployeeResponse createEmployee(CreateEmployeeRequest request);

    Page<EmployeeResponse> getAllEmployees(EmployeeStatus status, String search, Pageable pageable);

    EmployeeResponse getEmployeeById(Long id);

    EmployeeResponse updateEmployee(Long id, UpdateEmployeeRequest request);

    EmployeeResponse requestEmployeeDeletion(Long id);

    // Soft delete: the record is kept with status DELETED and its account disabled
    EmployeeResponse finalizeDeleteEmployee(Long id);

    // New employees waiting for Admin L2, oldest first
    List<EmployeeResponse> getPendingApprovals();

    EmployeeResponse decideApproval(Long id, EmployeeApprovalDecisionRequest request);
}