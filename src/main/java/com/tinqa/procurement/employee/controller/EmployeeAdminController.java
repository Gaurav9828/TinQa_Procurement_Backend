package com.tinqa.procurement.employee.controller;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.employee.constants.EmployeeConstants;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import com.tinqa.procurement.employee.service.EmployeeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/v1/admin/employees")
@RequiredArgsConstructor
public class EmployeeAdminController {

    private final EmployeeService employeeService;

    // 1. Create Employee (ADMIN_L1 and ADMIN_L2)
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<EmployeeResponse>> createEmployee(
            @Valid @RequestBody CreateEmployeeRequest request,
            HttpServletRequest httpServletRequest
    ) {
        EmployeeResponse response = employeeService.createEmployee(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<EmployeeResponse>builder()
                        .success(true)
                        .message("Employee created successfully")
                        .data(response)
                        .timestamp(Instant.now())
                        .path(httpServletRequest.getRequestURI())
                        .build()
        );
    }

    // 2. Employee List with Filter & Pagination (ADMIN_L1 and ADMIN_L2)
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<Page<EmployeeResponse>>> getAllEmployees(
            @RequestParam(required = false) EmployeeConstants status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            HttpServletRequest httpServletRequest
    ) {
        Page<EmployeeResponse> page = employeeService.getAllEmployees(status, search, pageable);

        return ResponseEntity.ok(
                ApiResponse.<Page<EmployeeResponse>>builder()
                        .success(true)
                        .message("Employee list retrieved successfully")
                        .data(page)
                        .timestamp(Instant.now())
                        .path(httpServletRequest.getRequestURI())
                        .build()
        );
    }

    // 3. Find Employee by ID (ADMIN_L1 and ADMIN_L2)
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<EmployeeResponse>> getEmployeeById(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest
    ) {
        EmployeeResponse response = employeeService.getEmployeeById(id);

        return ResponseEntity.ok(
                ApiResponse.<EmployeeResponse>builder()
                        .success(true)
                        .message("Employee details retrieved successfully")
                        .data(response)
                        .timestamp(Instant.now())
                        .path(httpServletRequest.getRequestURI())
                        .build()
        );
    }

    // 4. Update Employee (ADMIN_L1 and ADMIN_L2)
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<EmployeeResponse>> updateEmployee(
            @PathVariable Long id,
            @Valid @RequestBody UpdateEmployeeRequest request,
            HttpServletRequest httpServletRequest
    ) {
        EmployeeResponse response = employeeService.updateEmployee(id, request);

        return ResponseEntity.ok(
                ApiResponse.<EmployeeResponse>builder()
                        .success(true)
                        .message("Employee updated successfully")
                        .data(response)
                        .timestamp(Instant.now())
                        .path(httpServletRequest.getRequestURI())
                        .build()
        );
    }

    // 5. Request Employee Deletion -> Sets status to WAITING_FOR_DELETION (ADMIN_L1 & ADMIN_L2)
    @PatchMapping("/{id}/request-deletion")
    @PreAuthorize("hasAnyRole('ADMIN_L1', 'ADMIN_L2')")
    public ResponseEntity<ApiResponse<EmployeeResponse>> requestEmployeeDeletion(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest
    ) {
        EmployeeResponse response = employeeService.requestEmployeeDeletion(id);

        return ResponseEntity.ok(
                ApiResponse.<EmployeeResponse>builder()
                        .success(true)
                        .message("Employee status set to WAITING_FOR_DELETION")
                        .data(response)
                        .timestamp(Instant.now())
                        .path(httpServletRequest.getRequestURI())
                        .build()
        );
    }

    // 6. Final Deletion -> Permanently removes employee (STRICTLY ADMIN_L2 ONLY)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN_L2')")
    public ResponseEntity<ApiResponse<Void>> finalizeDeleteEmployee(
            @PathVariable Long id,
            HttpServletRequest httpServletRequest
    ) {
        employeeService.finalizeDeleteEmployee(id);

        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .success(true)
                        .message("Employee permanently deleted successfully")
                        .data(null)
                        .timestamp(Instant.now())
                        .path(httpServletRequest.getRequestURI())
                        .build()
        );
    }
}