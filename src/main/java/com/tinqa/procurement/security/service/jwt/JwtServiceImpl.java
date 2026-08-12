package com.tinqa.procurement.security.service.jwt;

import com.tinqa.procurement.security.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtServiceImpl implements JwtService {

    @Value("${tinqa.security.jwt.secret}")
    private String secret;

    @Value("${tinqa.security.jwt.expiration}")
    private long expiration;

    @Override
    public String generateToken(User user) {

        Date now = new Date();

        Date expiry = new Date(
                now.getTime() + expiration
        );

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim(
                        "role",
                        user.getRole().name()
                )
                .claim(
                        "authClient",
                        user.getAuthClient().name()
                )
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    @Override
    public String extractUsername(String token) {

        return extractAllClaims(token)
                .getSubject();
    }

    @Override
    public boolean isTokenValid(
            String token,
            UserDetails user) {

        String username =
                extractUsername(token);

        return username.equals(user.getUsername())
                && !isTokenExpired(token);
    }

    @Override
    public boolean isTokenExpired(String token) {

        return extractAllClaims(token)
                .getExpiration()
                .before(new Date());
    }

    @Override
    public String extractJti(String token) {

        return extractAllClaims(token)
                .getId();
    }

    @Override
    public Duration getRemainingLifetime(
            String token) {

        Date expirationDate =
                extractAllClaims(token)
                        .getExpiration();

        long remainingMillis =
                expirationDate.getTime()
                        - System.currentTimeMillis();

        return Duration.ofMillis(
                Math.max(remainingMillis, 0)
        );
    }

    private Claims extractAllClaims(String token) {

        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {

        return Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public long getExpirationTime() {
        return expiration;
    }
}