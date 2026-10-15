package com.tinqa.procurement.common.validation;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.web.JsonErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link InputSafety} to query/form parameters and the request path, which never pass through Jackson.
 * Registered as a servlet filter after Spring Security, so rejected requests still carry CORS headers.
 */
@Component
public class RequestInputSafetyFilter extends OncePerRequestFilter {

    private static final int MAX_PARAMETER_LENGTH = 500;

    private final JsonErrorResponseWriter errorWriter;

    public RequestInputSafetyFilter(JsonErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
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
                if (value != null && value.length() > MAX_PARAMETER_LENGTH) {
                    reject(request, response, parameter.getKey(), "Parameter '" + parameter.getKey() + "' cannot exceed " + MAX_PARAMETER_LENGTH + " characters");
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
        errorWriter.write(request, response, HttpServletResponse.SC_BAD_REQUEST, ErrorCodes.UNSAFE_INPUT, message,
                List.of(ApiErrors.error(field, message)));
    }
}
