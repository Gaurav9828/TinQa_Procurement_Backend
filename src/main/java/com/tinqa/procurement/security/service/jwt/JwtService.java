package com.tinqa.procurement.security.service.jwt;

import com.tinqa.procurement.security.access.UserAccess;
import com.tinqa.procurement.security.model.User;

public interface JwtService {

    /**
     * Signs a token for the account. The access (designation and permissions) is embedded for other TinQa services
     * that trust this token without a database of their own; this service re-reads access on every request.
     */
    String generateToken(User user, UserAccess access);

    /** Token lifetime in milliseconds. */
    long getExpirationTime();

    /**
     * Verifies the signature and expiry and returns the claims.
     *
     * @throws io.jsonwebtoken.JwtException     if the token is invalid or expired
     * @throws IllegalArgumentException if the token is empty
     */
    TokenClaims parse(String token);
}
