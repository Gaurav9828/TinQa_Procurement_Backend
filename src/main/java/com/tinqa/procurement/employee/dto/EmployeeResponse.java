package com.tinqa.procurement.employee.dto;

import com.tinqa.procurement.employee.constants.EmployeeConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeResponse {

    private Long id;
    private Long userId;
    private String username;
    private String employeeCode;
    private String firstName;
    private String middleName;
    private String lastName;
    private String displayName;
    private LocalDate dateOfBirth;
    private String gender;
    private String designation;
    private String department;
    private String employmentType;
    private LocalDate joiningDate;
    private LocalDate leavingDate;
    private BigDecimal salaryAmount;
    private String salaryCurrency;
    private String phone;
    private String alternatePhone;
    private String personalEmail;
    private String workEmail;
    private EmployeeConstants status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}