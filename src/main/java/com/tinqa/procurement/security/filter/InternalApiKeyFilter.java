package com.tinqa.procurement.security.filter;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.web.JsonErrorResponseWriter;
import com.tinqa.procurement.security.config.Authorities;
import com.tinqa.procurement.security.config.InternalApiProperties;
import com.tinqa.procurement.security.config.SecurityPaths;
import com.tinqa.procurement.security.web.BearerTokenResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates service-to-service calls under /v1/internal/ with a shared secret header.
 * Fails closed: if no key is configured, every internal call is rejected.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    private static final String PRINCIPAL = "internal-service";
    private static final String AUTHORITY = "ROLE_" + Authorities.INTERNAL_SERVICE_ROLE;

    private final byte[] configuredKey;
    private final JsonErrorResponseWriter errorWriter;

    public InternalApiKeyFilter(InternalApiProperties properties, JsonErrorResponseWriter errorWriter) {
        this.configuredKey = properties.key().getBytes(StandardCharsets.UTF_8);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !BearerTokenResolver.applicationPath(request).startsWith(SecurityPaths.INTERNAL_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        // Constant-time comparison, so response timing reveals nothing about the key
        if (configuredKey.length == 0 || presented == null
                || !MessageDigest.isEqual(configuredKey, presented.getBytes(StandardCharsets.UTF_8))) {
            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCodes.INTERNAL_AUTH_FAILED,
                    "A valid internal API key is required.");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                PRINCIPAL, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        filterChain.doFilter(request, response);
    }
}
