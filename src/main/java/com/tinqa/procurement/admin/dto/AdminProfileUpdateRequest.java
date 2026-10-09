package com.tinqa.procurement.admin.dto;

import com.tinqa.procurement.common.validation.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class AdminProfileUpdateRequest {

    @Size(max = 200, message = "Display name cannot exceed 200 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "Display name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String displayName;

    @Pattern(regexp = ValidationPatterns.PHONE, message = "Primary phone " + ValidationPatterns.PHONE_MESSAGE)
    private String primaryPhone;

    @Pattern(regexp = ValidationPatterns.PHONE, message = "Alternate phone " + ValidationPatterns.PHONE_MESSAGE)
    private String alternatePhone;

    @Size(max = 150, message = "Personal email cannot exceed 150 characters")
    @Pattern(regexp = ValidationPatterns.EMAIL, message = "Personal email " + ValidationPatterns.EMAIL_MESSAGE)
    private String personalEmail;

    @BirthDate(message = "Date of birth must be a past date for a person aged 18 to 100")
    private LocalDate dateOfBirth;

    @Size(max = 100, message = "First name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "First name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String firstName;

    @Size(max = 100, message = "Middle name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "Middle name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String middleName;

    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.PERSON_NAME, message = "Last name " + ValidationPatterns.PERSON_NAME_MESSAGE)
    private String lastName;

    @Size(max = 100, message = "Department cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Department " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
    private String department;

    @Size(max = 100, message = "Designation cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Designation " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE)
    private String designation;

    @Pattern(regexp = ValidationPatterns.GENDER, message = "Gender must be MALE, FEMALE or OTHER")
    private String gender;

    @Pattern(regexp = ValidationPatterns.EMPLOYMENT_TYPE, message = "Employment type must be FULL_TIME, PART_TIME, CONTRACT, INTERN, CONSULTANT or TEMPORARY")
    private String employmentType;

    @Size(max = 30, message = "Status cannot exceed 30 characters")
    @Pattern(regexp = ValidationPatterns.CODE, message = "Status " + ValidationPatterns.CODE_MESSAGE)
    private String status;
}