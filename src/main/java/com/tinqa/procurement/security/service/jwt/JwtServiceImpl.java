package com.tinqa.procurement.security.service.jwt;

import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.UserAccess;
import com.tinqa.procurement.security.config.JwtProperties;
import com.tinqa.procurement.security.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtServiceImpl implements JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_AUTH_CLIENT = "authClient";
    private static final String CLAIM_DESIGNATION = "designation";
    // Not "permissions": the Ecommerce backend reads that claim name as roles
    private static final String CLAIM_PERMISSIONS = "perms";
    private static final String CLAIM_NAME = "name";
    // Standard "iat" is whole seconds; this keeps milliseconds so a password change revokes tokens issued earlier in the same second
    private static final String CLAIM_ISSUED_AT_MS = "iat_ms";

    // Other services (Ecommerce) accept a token as Procurement's only with this issuer and an EdDSA signature
    public static final String ISSUER = "tinqa-procurement";

    private final JwtKeys keys;
    private final long expiration;
    private final Clock clock;

    // Signed with the Ed25519 private key (JwtKeys); anyone holding only the public key can verify but not create tokens
    public JwtServiceImpl(JwtKeys keys, JwtProperties properties, Clock clock) {
        this.keys = keys;
        this.expiration = properties.expiration();
        this.clock = clock;
    }

    @Override
    public String generateToken(User user, UserAccess access, String displayName) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .header().keyId(keys.keyId()).and()
                .issuer(ISSUER)
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_AUTH_CLIENT, user.getAuthClient().name())
                .claim(CLAIM_DESIGNATION, access.designationCode())
                .claim(CLAIM_PERMISSIONS, access.permissions().stream().map(Permission::name).sorted().toList())
                .claim(CLAIM_NAME, displayName)
                .issuedAt(now)
                .claim(CLAIM_ISSUED_AT_MS, now.getTime())
                .expiration(new Date(now.getTime() + expiration))
                .signWith(keys.privateKey(), Jwts.SIG.EdDSA)
                .compact();
    }

    @Override
    public long getExpirationTime() {
        return expiration;
    }

    @Override
    public TokenClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(keys.publicKey())
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // Tokens issued before the millisecond claim existed fall back to the whole-second "iat"
        Long issuedAtMillis = claims.get(CLAIM_ISSUED_AT_MS, Long.class);
        Instant issuedAt = issuedAtMillis != null ? Instant.ofEpochMilli(issuedAtMillis)
                : claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
        return new TokenClaims(claims.getSubject(), claims.getId(), issuedAt, claims.getExpiration().toInstant());
    }
}
