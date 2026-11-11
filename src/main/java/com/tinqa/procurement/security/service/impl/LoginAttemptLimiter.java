package com.tinqa.procurement.security.service.impl;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Locks a username for a while after repeated wrong passwords, so admin passwords cannot be guessed quickly.
 * <p>
 * Counted per typed username (case-insensitive), whether or not an account has it, so the lock never reveals which
 * usernames exist. {@value #MAX_FAILURES} failures within {@link #WINDOW} lock the username until the window ends; a
 * successful sign-in clears the count. Kept in Redis so every instance shares it.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "tinqa:auth:login-failures:";

    private final StringRedisTemplate redisTemplate;

    /** Throws 429 when the username is locked; checked before the password, so a locked account cannot be probed. */
    public void ensureNotLocked(String username) {
        String key = key(username);
        String count = redisTemplate.opsForValue().get(key);
        if (count != null && Long.parseLong(count) >= MAX_FAILURES) {
            throw locked(secondsLeft(key));
        }
    }

    public void recordFailure(String username) {
        String key = key(username);
        Long failures = redisTemplate.opsForValue().increment(key);
        if (failures != null && failures == 1) {
            redisTemplate.expire(key, WINDOW);
        } else {
            // Self-heal a key whose expiry was lost (a crash between INCR and EXPIRE) instead of locking it forever
            secondsLeft(key);
        }
    }

    public void clear(String username) {
        redisTemplate.delete(key(username));
    }

    private long secondsLeft(String key) {
        Long seconds = redisTemplate.getExpire(key);
        if (seconds == null || seconds < 0) {
            redisTemplate.expire(key, WINDOW);
            return WINDOW.toSeconds();
        }
        return seconds;
    }

    private static ApiException locked(long secondsLeft) {
        long minutes = Math.max(1, (secondsLeft + 59) / 60);
        return new ApiException("Too many failed sign-in attempts. Try again in " + minutes
                + (minutes == 1 ? " minute" : " minutes") + ", or reset your password.",
                ErrorCodes.TOO_MANY_LOGIN_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS);
    }

    private static String key(String username) {
        return KEY_PREFIX + (username == null ? "" : username.strip().toLowerCase(Locale.ROOT));
    }
}
