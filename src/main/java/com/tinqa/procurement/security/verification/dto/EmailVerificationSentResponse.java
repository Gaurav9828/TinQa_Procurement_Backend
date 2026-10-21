package com.tinqa.procurement.security.verification.dto;

/**
 * @param resendAfterSeconds a new code can be asked for after this many seconds
 */
public record EmailVerificationSentResponse(String email, long codeValidMinutes, long resendAfterSeconds) {
}
