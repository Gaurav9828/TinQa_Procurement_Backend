package com.tinqa.procurement.common.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link InputSafety} to query/form parameters and the request path, which never pass through Jackson.
 * Registered as a servlet filter after Spring Security, so rejected requests still carry CORS headers.
 */
@Component
public class RequestInputSafetyFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public RequestInputSafetyFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = decode(request.getRequestURI());
        String violation = path == null ? "is not valid URL encoding" : InputSafety.findViolation(path);
        if (violation != null || (path != null && (path.contains("..") || path.contains("\\")))) {
            reject(request, response, "path", "Request path " + (violation != null ? violation : "must not contain '..' or '\\'"));
            return;
        }

        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            String nameViolation = InputSafety.findViolation(parameter.getKey());
            if (nameViolation != null) {
                reject(request, response, "query", "Parameter name " + nameViolation);
                return;
            }
            for (String value : parameter.getValue()) {
                String valueViolation = InputSafety.findViolation(value);
                if (valueViolation != null) {
                    reject(request, response, parameter.getKey(), "Parameter '" + parameter.getKey() + "' " + valueViolation);
                    return;
                }
                if (value != null && value.length() > 500) {
                    reject(request, response, parameter.getKey(), "Parameter '" + parameter.getKey() + "' cannot exceed 500 characters");
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String decode(String uri) {
        try {
            return URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String field, String message) throws IOException {
        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .message(message)
                .errorCode("UNSAFE_INPUT")
                .errors(List.of(new ApiResponse.ApiErrorItem(field, message)))
                .timestamp(Instant.now())
                .path(request.getRequestURI())
                .build();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
