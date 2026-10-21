package com.tinqa.procurement.security.verification.dto;

import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Send a 5-digit code to the new employee's personal email. */
@Getter
@Setter
public class EmailVerificationRequest {

    @NotBlank(message = "Email is required")
    @Size(max = 150, message = "Email cannot exceed 150 characters")
    @Pattern(regexp = ValidationPatterns.EMAIL, message = "Email " + ValidationPatterns.EMAIL_MESSAGE)
    private String email;

    // How the email greets them (e.g. the first name typed so far); optional
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    @Pattern(regexp = ValidationPatterns.OPTIONAL_PERSON_NAME, message = "Name " + ValidationPatterns.OPTIONAL_PERSON_NAME_MESSAGE)
    private String name;
}
