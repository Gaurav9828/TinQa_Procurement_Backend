package com.tinqa.procurement.security.dto;

import com.tinqa.procurement.common.validation.RawInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    @RawInput
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 128, message = "New password must be between 8 and 128 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,128}$",
            message = "Password must contain at least one uppercase letter, one lowercase letter, one digit, one special character, and no whitespace"
    )
    private String newPassword;

    @RawInput
    @NotBlank(message = "Confirm new password is required")
    @Size(max = 128, message = "Confirm new password cannot exceed 128 characters")
    private String confirmNewPassword;
}
