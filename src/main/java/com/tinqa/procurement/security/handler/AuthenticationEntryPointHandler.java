package com.tinqa.procurement.security.handler;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.web.JsonErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationEntryPointHandler implements AuthenticationEntryPoint {

    private final JsonErrorResponseWriter errorWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.warn("Unauthenticated request. Method: {}, URI: {}, Message: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());
        errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                "Authentication is required.");
    }
}
