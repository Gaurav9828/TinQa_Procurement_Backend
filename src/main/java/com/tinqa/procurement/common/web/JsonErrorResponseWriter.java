package com.tinqa.procurement.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Writes the standard error envelope from servlet filters and security handlers, which run before Spring MVC
 * and so cannot rely on {@code GlobalExceptionHandler}.
 */
@Component
@RequiredArgsConstructor
public class JsonErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String errorCode, String message)
            throws IOException {
        write(request, response, status, errorCode, message, List.of());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String errorCode, String message,
                      List<ApiResponse.ApiErrorItem> errors) throws IOException {
        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .message(message)
                .errorCode(errorCode)
                .errors(errors)
                .timestamp(Instant.now())
                .path(request.getRequestURI())
                .build();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
