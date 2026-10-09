package com.tinqa.procurement.employee.dto;

import com.tinqa.procurement.common.validation.*;
import jakarta.validation.constraints.*;
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

    @Size(max = 100, message = "First name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "First name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String firstName;
    @Size(max = 100, message = "Middle name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "Middle name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String middleName;
    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "Last name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String lastName;
    @Size(max = 200, message = "Display name cannot exceed 200 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "Display name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String displayName;
    @BirthDate(message = "Date of birth must be a past date for a person aged 18 to 100")
    private LocalDate dateOfBirth;
    @Pattern(regexp = ValidationPatterns.GENDER, message = "Gender must be MALE, FEMALE or OTHER")
    private String gender;
    @Size(max = 100, message = "Designation cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Designation " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
    private String designation;
    @Size(max = 100, message = "Department cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Department " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
    private String department;
    @Pattern(regexp = ValidationPatterns.EMPLOYMENT_TYPE, message = "Employment type must be FULL_TIME, PART_TIME, CONTRACT, INTERN, CONSULTANT or TEMPORARY")
    private String employmentType;
    @PastOrPresent(message = "Joining date cannot be in the future")
    private LocalDate joiningDate;
    private LocalDate leavingDate;
    @PositiveOrZero(message = "Salary amount cannot be negative")
    @MaxAmount(message = "Salary amount cannot exceed " + ValidationPatterns.MAX_AMOUNT_LABEL + " and can have at most 2 decimal places")
    private BigDecimal salaryAmount;
    @ValidCurrency(message = "Salary currency must be a 3-letter ISO currency code, e.g. INR")
    private String salaryCurrency;
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Phone number " + ValidationPatterns.PHONE_MESSAGE)
    private String phone;
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Alternate phone number " + ValidationPatterns.PHONE_MESSAGE)
    private String alternatePhone;

    @Size(max = 150, message = "Personal email cannot exceed 150 characters")
    @Pattern(regexp = ValidationPatterns.EMAIL, message = "Personal email " + ValidationPatterns.EMAIL_MESSAGE)
    private String personalEmail;

    private EmployeeConstants status;
}