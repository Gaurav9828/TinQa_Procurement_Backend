package com.tinqa.procurement.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class CreateEmployeeRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "System email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "First name is required")
    private String firstName;

    private String middleName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    private String displayName;

    @NotNull(message = "Date of birth is required")
    private LocalDate dateOfBirth;

    @NotBlank(message = "Gender is required")
    private String gender;

    @NotBlank(message = "Designation is required")
    private String designation;

    @NotBlank(message = "Department is required")
    private String department;

    @NotBlank(message = "Employment type is required")
    private String employmentType;

    @NotNull(message = "Joining date is required")
    private LocalDate joiningDate;

    private BigDecimal salaryAmount;

    private String salaryCurrency;

    @NotBlank(message = "Phone number is required")
    private String phone;

    private String alternatePhone;

    @Email(message = "Invalid personal email format")
    private String personalEmail;

    // Optional user role override; defaults to EMPLOYEE if omitted
    private String role;
}