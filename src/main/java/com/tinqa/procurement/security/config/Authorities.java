package com.tinqa.procurement.security.config;

/**
 * {@code @PreAuthorize} expressions, so each access rule is written once.
 */
public final class Authorities {

    public static final String INTERNAL_SERVICE_ROLE = "INTERNAL_SERVICE";

    public static final String AUTHENTICATED = "isAuthenticated()";
    public static final String ADMIN = "hasAnyRole('ADMIN_L1', 'ADMIN_L2')";
    public static final String ADMIN_L2 = "hasRole('ADMIN_L2')";
    public static final String INTERNAL_SERVICE = "hasRole('" + INTERNAL_SERVICE_ROLE + "')";

    private Authorities() {
    }
}
