package com.tinqa.procurement.security.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bootstrap Admin L2 account created at startup when it does not exist yet. Leaving the username or password
 * empty disables it.
 */
@ConfigurationProperties(prefix = "tinqa.super-admin")
public record SuperAdminProperties(String username, String email, String password,
                                   String firstName, String lastName, String phone) {

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
