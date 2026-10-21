package com.tinqa.procurement.security.password.dto;

import com.tinqa.procurement.common.validation.RawInput;
import lombok.Getter;
import lombok.Setter;

/** The password chosen on an emailed link's page. Checked in the service, after the link, so errors come in order. */
@Getter
@Setter
public class PasswordLinkResetRequest {

    private String token;

    @RawInput
    private String newPassword;

    @RawInput
    private String confirmNewPassword;
}
