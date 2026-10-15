package com.tinqa.procurement.security.dto;

import com.tinqa.procurement.common.validation.RawInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    // Passwords are hashed, never displayed: any character is allowed
    @RawInput
    @NotBlank(message = "Current password is required")
    @Size(max = 128, message = "Current password cannot exceed 128 characters")
    private String currentPassword;

    // Checked by PasswordPolicy in the service, so both password endpoints apply the same rule and message
    @RawInput
    private String newPassword;

    @RawInput
    private String confirmNewPassword;
}
