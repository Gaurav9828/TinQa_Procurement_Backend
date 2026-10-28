package com.tinqa.procurement.common.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Measures every API request (time, SQL statements, N+1 patterns) for {@link ApiCallRegistry}. Runs before Spring
 * Security so the database work of authentication is counted too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "monitoring.api", name = "enabled", matchIfMissing = true)
public class ApiCallMonitorFilter extends OncePerRequestFilter {

    // Requests rejected before reaching a controller (no token, unknown path) are grouped under this route
    static final String UNMATCHED_ROUTE = "(not routed)";

    private final ApiCallRegistry registry;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || uri.contains("/actuator/") || uri.contains("/swagger-ui") || uri.contains("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RequestDbStats.begin();
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestDbStats.Snapshot db = RequestDbStats.end();
            registry.record(request.getMethod(), route(request), response.getStatus(), (System.nanoTime() - start) / 1_000_000, db);
        }
    }

    // The mapping pattern (/api/products/{id}), never the raw path, so ids do not create a new entry per record
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? UNMATCHED_ROUTE : String.valueOf(pattern);
    }
}
