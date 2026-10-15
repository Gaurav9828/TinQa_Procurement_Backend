package com.tinqa.procurement.security.web;

import com.tinqa.procurement.common.constant.AppConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

import java.util.Optional;

/**
 * Reads the JWT from the {@code Authorization: Bearer <token>} header.
 */
public final class BearerTokenResolver {

    private BearerTokenResolver() {
    }

    public static Optional<String> resolve(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(AppConstants.BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(AppConstants.BEARER_PREFIX.length()));
    }

    // Request URI without the servlet context path (/api), as the security path constants are written
    public static String applicationPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
