package com.tinqa.procurement.employee.dto;

import com.tinqa.procurement.employee.constants.EmployeeConstants;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEmployeeRequest {

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

    @Email(message = "Invalid personal email format")
    private String personalEmail;

    private EmployeeConstants status;
}