package com.tinqa.procurement.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * Authenticates service-to-service calls under /v1/internal/ with a shared secret header.
 * Fails closed: if no key is configured, every internal call is rejected.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String INTERNAL_PATH_PREFIX = "/v1/internal/";
    public static final String ROLE = "ROLE_INTERNAL_SERVICE";

    private final byte[] configuredKey;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(@Value("${tinqa.internal-api.key:}") String configuredKey, ObjectMapper objectMapper) {
        this.configuredKey = configuredKey.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String applicationPath = request.getRequestURI().substring(request.getContextPath().length());
        return !applicationPath.startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (configuredKey.length == 0 || presented == null
                || !MessageDigest.isEqual(configuredKey, presented.getBytes(StandardCharsets.UTF_8))) {
            ApiResponse<Void> body = ApiResponse.<Void>builder()
                    .success(false)
                    .message("A valid internal API key is required.")
                    .errorCode("INTERNAL_AUTH_FAILED")
                    .timestamp(Instant.now())
                    .path(request.getRequestURI())
                    .errors(List.of())
                    .build();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "internal-service", null, List.of(new SimpleGrantedAuthority(ROLE))));
        filterChain.doFilter(request, response);
    }
}
