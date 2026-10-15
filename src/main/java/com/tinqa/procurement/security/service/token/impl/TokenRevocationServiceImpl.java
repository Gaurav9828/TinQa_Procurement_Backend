package com.tinqa.procurement.security.service.token.impl;

import com.tinqa.procurement.security.service.token.TokenRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Revoked token ids live in Redis only until the token would have expired anyway.
 */
@Service
@RequiredArgsConstructor
public class TokenRevocationServiceImpl implements TokenRevocationService {

    private static final String KEY_PREFIX = "tinqa:auth:revoked:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void revoke(String jti, Duration remainingLifetime) {
        if (jti == null || jti.isBlank() || remainingLifetime == null
                || remainingLifetime.isZero() || remainingLifetime.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", remainingLifetime);
    }

    @Override
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
    }
}
