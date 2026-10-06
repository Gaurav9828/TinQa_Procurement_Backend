package com.tinqa.procurement.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationEntryPointHandler implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {

        log.warn("Unauthenticated request. Method: {}, URI: {}, Message: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiResponse<Void> body = ApiResponse.<Void>builder()
                .success(false)
                .message("Authentication is required.")
                .errorCode("UNAUTHORIZED")
                .timestamp(Instant.now())
                .path(request.getRequestURI())
                .errors(java.util.List.of())
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}