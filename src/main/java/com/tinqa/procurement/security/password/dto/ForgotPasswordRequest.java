package com.tinqa.procurement.security.password.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** The username or email someone signs in with. */
@Getter
@Setter
public class ForgotPasswordRequest {

    @NotBlank(message = "Enter your username or email")
    @Size(max = 150, message = "Username or email cannot exceed 150 characters")
    private String identifier;
}
