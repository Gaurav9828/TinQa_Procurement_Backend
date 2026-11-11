package com.tinqa.procurement.security.service.token;

import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.service.jwt.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Whether a correctly signed, unexpired token still stands: it was not signed out (jti denylist) and was issued after
 * the account's last password change. Shared by this service's request filter and by the token-status check the
 * Ecommerce service makes for admin tokens, so both apply the same rule.
 */
@Component
@RequiredArgsConstructor
public class TokenValidity {

    private final TokenRevocationService tokenRevocationService;

    // Logged out, or issued before the last password change. Compared in milliseconds (the precision tokens carry):
    // a session opened earlier in the same second as the change is signed out, one opened right after it is not.
    public boolean isRevoked(TokenClaims claims, User user) {
        if (tokenRevocationService.isRevoked(claims.jti())) {
            return true;
        }
        Instant passwordChangedAt = user.getPasswordChangedAt();
        if (passwordChangedAt == null) {
            return false;
        }
        return claims.issuedAt() == null || claims.issuedAt().toEpochMilli() < passwordChangedAt.toEpochMilli();
    }
}
