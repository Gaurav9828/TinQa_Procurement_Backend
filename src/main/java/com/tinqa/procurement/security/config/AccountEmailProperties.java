package com.tinqa.procurement.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Email verification codes and password links (tinqa.account.*). */
@ConfigurationProperties(prefix = "tinqa.account")
public record AccountEmailProperties(@DefaultValue Verification verification, @DefaultValue PasswordLink passwordLink) {

    /**
     * @param codeValidity    how long the 5-digit code works after it is emailed
     * @param maxAttempts     wrong codes allowed before a new one must be requested
     * @param resendCooldown  wait before another code can be sent to the same address
     * @param maxCodesPerHour codes one address can be sent per hour
     * @param tokenValidity   how long a verified email may be used to create the account
     */
    public record Verification(@DefaultValue("10m") Duration codeValidity, @DefaultValue("5") int maxAttempts,
                               @DefaultValue("60s") Duration resendCooldown, @DefaultValue("5") int maxCodesPerHour,
                               @DefaultValue("30m") Duration tokenValidity) {
    }

    /**
     * @param validity       how long a set-password or reset link works after it is emailed
     * @param resendCooldown "forgot password" requests for one account closer together than this send nothing more
     */
    public record PasswordLink(@DefaultValue("15m") Duration validity, @DefaultValue("60s") Duration resendCooldown) {
    }
}
