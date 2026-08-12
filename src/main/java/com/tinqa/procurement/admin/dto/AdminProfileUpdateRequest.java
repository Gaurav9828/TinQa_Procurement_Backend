package com.tinqa.procurement.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class AdminProfileUpdateRequest {

    private String displayName;

    @Pattern(
            regexp = "^\\+?[0-9]{10,15}$",
            message = "Primary phone must be a valid phone number"
    )
    private String primaryPhone;

    @Pattern(
            regexp = "^\\+?[0-9]{10,15}$",
            message = "Alternate phone must be a valid phone number"
    )
    private String alternatePhone;

    @Email(message = "Personal email must be valid")
    private String personalEmail;

    private LocalDate dateOfBirth;

    private String firstName;

    private String middleName;

    private String lastName;

    private String department;

    private String designation;

    private String gender;

    private String employmentType;

    private String status;
}