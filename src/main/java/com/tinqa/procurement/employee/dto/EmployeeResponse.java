package com.tinqa.procurement.employee.dto;

import com.tinqa.procurement.employee.enums.EmployeeStatus;
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
    private String designationLabel;
    // The account's admin level (ADMIN_L1 / ADMIN_L2), or DEALER / INSPECTOR for mobile accounts
    private String role;
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
    private String panNumber;
    private String houseNumber;
    private String street;
    private String landmark;
    private String district;
    private String state;
    private String pincode;
    private String googleMapsUrl;
    private EmployeeStatus status;
    // Who created the employee and, for employees that needed approval, the Admin L2 decision
    private Long createdBy;
    private String createdByUsername;
    private Long approvalDecidedBy;
    private String approvalDecidedByUsername;
    private LocalDateTime approvalDecidedAt;
    private String approvalRejectionReason;
    // Set once the employee is deleted (status DELETED)
    private Long deletedBy;
    private String deletedByUsername;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}