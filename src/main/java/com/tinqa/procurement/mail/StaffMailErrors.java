package com.tinqa.procurement.mail;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** How a failed staff email is reported to the caller. */
public final class StaffMailErrors {

    private StaffMailErrors() {
    }

    public static ApiException toApiException(StaffMailException exception) {
        return exception.isNotConfigured()
                ? ApiErrors.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.EMAIL_NOT_CONFIGURED,
                "Emails can't be sent yet: a Super Admin has to set up email in Company Profile › Email.")
                : ApiErrors.of(HttpStatus.BAD_GATEWAY, ErrorCodes.EMAIL_SEND_FAILED,
                "The email could not be sent right now. Please try again in a few minutes.");
    }
}
