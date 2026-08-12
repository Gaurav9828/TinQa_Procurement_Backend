package com.tinqa.procurement.security.service.jwt;

import com.tinqa.procurement.security.model.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Duration;

public interface JwtService {

    String generateToken(User user);

    String extractUsername(String token);

    boolean isTokenValid(
            String token,
            UserDetails user
    );

    boolean isTokenExpired(String token);

    long getExpirationTime();

    String extractJti(String token);

    Duration getRemainingLifetime(String token);
}