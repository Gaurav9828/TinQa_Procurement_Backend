package com.tinqa.procurement.security.password.dto;

import com.tinqa.procurement.security.password.PasswordLinkPurpose;

import java.time.LocalDateTime;

/** A link that still works: whose it is and until when, so the page can greet them. */
public record PasswordLinkResponse(PasswordLinkPurpose purpose, String username, LocalDateTime expiresAt) {
}
