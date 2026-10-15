package com.tinqa.procurement.security.dto;

import com.tinqa.procurement.common.validation.RawInput;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FirstLoginPasswordRequest {

    // No current password: the user proved the temporary one by logging in. Both fields are checked in the
    // service, after the first-login status, so the errors come back in the documented order.
    @RawInput
    private String newPassword;

    @RawInput
    private String confirmNewPassword;
}
