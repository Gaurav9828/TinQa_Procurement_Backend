package com.tinqa.procurement.security.dto;

import com.tinqa.procurement.common.validation.*;
import jakarta.validation.constraints.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MobileLoginRequest {

    @NotBlank(message = "Mobile number is required")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Mobile number " + ValidationPatterns.PHONE_MESSAGE)
    private String mobileNumber;

    @RawInput
    @NotBlank(message = "Password is required")
    @Size(max = 128, message = "Password cannot exceed 128 characters")
    private String password;
}