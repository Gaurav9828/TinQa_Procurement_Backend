package com.tinqa.procurement.security.config;

import java.util.Set;

/**
 * Request paths the security layer treats specially, relative to the servlet context path (/api).
 */
public final class SecurityPaths {

    public static final String ADMIN_LOGIN = "/auth/admin/login";
    public static final String MOBILE_LOGIN = "/auth/mobile/login";
    // Idempotent: revokes the presented token if there is one
    public static final String LOGOUT = "/auth/logout";
    public static final String FIRST_LOGIN_PASSWORD = "/auth/first-login/password";
    public static final String CHANGE_PASSWORD = "/auth/change-password";
    // "Forgot password?" and the page an emailed password link opens: used without signing in
    public static final String FORGOT_PASSWORD = "/auth/password/forgot";
    public static final String PASSWORD_LINK_CHECK = "/auth/password/link/check";
    public static final String PASSWORD_LINK = "/auth/password/link";
    // The public key tokens are verified with (JWKS)
    public static final String JWKS = "/auth/jwks";
    public static final String INTERNAL_API_PREFIX = "/v1/internal/";

    public static final String[] PUBLIC = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health",
            ADMIN_LOGIN,
            MOBILE_LOGIN,
            LOGOUT,
            FORGOT_PASSWORD,
            PASSWORD_LINK_CHECK,
            PASSWORD_LINK,
            JWKS
    };

    // The only endpoints a user who still has to set their first password can reach
    public static final Set<String> FIRST_LOGIN_ALLOWED = Set.of(FIRST_LOGIN_PASSWORD, CHANGE_PASSWORD, LOGOUT, ADMIN_LOGIN);

    private SecurityPaths() {
    }
}
