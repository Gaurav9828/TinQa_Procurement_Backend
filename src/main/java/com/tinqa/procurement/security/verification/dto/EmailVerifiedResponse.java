package com.tinqa.procurement.security.verification.dto;

import java.time.LocalDateTime;

/** The address is proved: send {@code verificationToken} with the new employee (personalEmailVerificationToken). */
public record EmailVerifiedResponse(String email, String verificationToken, LocalDateTime expiresAt) {
}
