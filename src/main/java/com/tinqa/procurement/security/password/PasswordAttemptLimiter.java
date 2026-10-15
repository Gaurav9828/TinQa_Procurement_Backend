package com.tinqa.procurement.security.password;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window limit on password-setting attempts per user. Every attempt counts, including failed ones, so a
 * stolen first-login token cannot be used to try passwords quickly.
 */
@Component
@RequiredArgsConstructor
public class PasswordAttemptLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "tinqa:auth:password-attempts:";

    private final StringRedisTemplate redisTemplate;

    public void recordAttempt(Long userId) {
        String key = KEY_PREFIX + userId;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(key, WINDOW);
        }
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            Long secondsLeft = redisTemplate.getExpire(key);
            if (secondsLeft == null || secondsLeft < 0) {
                // The expiry was lost (e.g. a crash between INCR and EXPIRE): restart the window rather than lock forever
                redisTemplate.expire(key, WINDOW);
                secondsLeft = WINDOW.toSeconds();
            }
            long minutesLeft = Math.max(1, (secondsLeft + 59) / 60);
            throw new ApiException(
                    "Too many password attempts. Try again in " + minutesLeft
                            + (minutesLeft == 1 ? " minute." : " minutes."),
                    ErrorCodes.TOO_MANY_PASSWORD_ATTEMPTS,
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
