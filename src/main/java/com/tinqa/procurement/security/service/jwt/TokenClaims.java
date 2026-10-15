package com.tinqa.procurement.security.service.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The verified contents of an access token.
 *
 * @param username  the subject
 * @param jti       unique token id, used for revocation
 * @param issuedAt  may be null for tokens without an iat claim
 * @param expiresAt expiry
 */
public record TokenClaims(String username, String jti, Instant issuedAt, Instant expiresAt) {

    public Duration remainingLifetime(Clock clock) {
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
