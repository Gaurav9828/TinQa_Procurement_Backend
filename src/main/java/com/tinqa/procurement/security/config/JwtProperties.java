package com.tinqa.procurement.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret     HMAC signing key, at least 32 bytes
 * @param expiration token lifetime in milliseconds
 */
@ConfigurationProperties(prefix = "tinqa.security.jwt")
public record JwtProperties(String secret, long expiration) {
}
