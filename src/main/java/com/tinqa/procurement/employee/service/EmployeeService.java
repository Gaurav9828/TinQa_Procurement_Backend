package com.tinqa.procurement.employee.service;

import com.tinqa.procurement.employee.constants.EmployeeConstants;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.employee.dto.EmployeeResponse;
import com.tinqa.procurement.employee.dto.UpdateEmployeeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EmployeeService {

    EmployeeResponse createEmployee(CreateEmployeeRequest request);

    Page<EmployeeResponse> getAllEmployees(EmployeeConstants status, String search, Pageable pageable);

    EmployeeResponse getEmployeeById(Long id);

    EmployeeResponse updateEmployee(Long id, UpdateEmployeeRequest request);

    EmployeeResponse requestEmployeeDeletion(Long id);

    void finalizeDeleteEmployee(Long id);
}