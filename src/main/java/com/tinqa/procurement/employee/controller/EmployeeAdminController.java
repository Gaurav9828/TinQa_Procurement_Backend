package com.tinqa.procurement.employee.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeApprovalDecisionRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.service.EmployeeService;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/employees")
@RequiredArgsConstructor
public class EmployeeAdminController {

    private final EmployeeService employeeService;

    @RequiresPermission(Permission.EMPLOYEE_CREATE)
    @PostMapping
    public ResponseEntity<ApiResponse<EmployeeResponse>> createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        return ApiResponses.created(employeeService.createEmployee(request), "Employee created successfully");
    }

    // Filter by status and/or a search term over name, code, department and phone
    @RequiresPermission(Permission.EMPLOYEE_VIEW)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<EmployeeResponse>>> getAllEmployees(
            @RequestParam(required = false) EmployeeStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ApiResponses.ok(employeeService.getAllEmployees(status, search, pageable), "Employee list retrieved successfully");
    }

    @RequiresPermission(Permission.EMPLOYEE_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeResponse>> getEmployeeById(@PathVariable Long id) {
        return ApiResponses.ok(employeeService.getEmployeeById(id), "Employee details retrieved successfully");
    }

    @RequiresPermission(Permission.EMPLOYEE_UPDATE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeResponse>> updateEmployee(@PathVariable Long id,
                                                                        @Valid @RequestBody UpdateEmployeeRequest request) {
        return ApiResponses.ok(employeeService.updateEmployee(id, request), "Employee updated successfully");
    }

    // Marks the employee WAITING_FOR_DELETION; an Admin L2 finalises the deletion
    @RequiresPermission(Permission.EMPLOYEE_REQUEST_DELETION)
    @PatchMapping("/{id}/request-deletion")
    public ResponseEntity<ApiResponse<EmployeeResponse>> requestEmployeeDeletion(@PathVariable Long id) {
        return ApiResponses.ok(employeeService.requestEmployeeDeletion(id), "Employee status set to WAITING_FOR_DELETION");
    }

    @RequiresPermission(Permission.EMPLOYEE_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> finalizeDeleteEmployee(@PathVariable Long id) {
        employeeService.finalizeDeleteEmployee(id);
        return ApiResponses.ok("Employee permanently deleted successfully");
    }

    // New employees waiting for Admin L2 approval, oldest first
    @RequiresPermission(Permission.EMPLOYEE_APPROVE)
    @GetMapping("/pending-approvals")
    public ResponseEntity<ApiResponse<List<EmployeeResponse>>> getPendingApprovals() {
        return ApiResponses.ok(employeeService.getPendingApprovals(), "Employees waiting for approval retrieved successfully");
    }

    // Approve (account enabled) or reject (account deactivated, reason kept) a new employee
    @RequiresPermission(Permission.EMPLOYEE_APPROVE)
    @PatchMapping("/{id}/approval")
    public ResponseEntity<ApiResponse<EmployeeResponse>> decideApproval(@PathVariable Long id,
                                                                        @Valid @RequestBody EmployeeApprovalDecisionRequest request) {
        EmployeeResponse response = employeeService.decideApproval(id, request);
        return ApiResponses.ok(response, request.isApproved() ? "Employee approved successfully" : "Employee rejected");
    }
}
