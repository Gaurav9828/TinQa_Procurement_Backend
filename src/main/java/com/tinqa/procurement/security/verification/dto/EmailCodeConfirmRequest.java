package com.tinqa.procurement.security.verification.dto;

import com.tinqa.procurement.common.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** The code the employee received at {@code email}. */
@Getter
@Setter
public class EmailCodeConfirmRequest {

    @NotBlank(message = "Email is required")
    @Size(max = 150, message = "Email cannot exceed 150 characters")
    @Pattern(regexp = ValidationPatterns.EMAIL, message = "Email " + ValidationPatterns.EMAIL_MESSAGE)
    private String email;

    @NotBlank(message = "Enter the 5-digit code from the email")
    @Pattern(regexp = "^\\d{5}$", message = "Enter the 5-digit code from the email")
    private String code;
}
