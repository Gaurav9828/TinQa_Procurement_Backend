package com.tinqa.procurement.security.handler;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.web.JsonErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccessDeniedHandlerImpl implements AccessDeniedHandler {

    private final JsonErrorResponseWriter errorWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        log.warn("Access denied. Method: {}, URI: {}", request.getMethod(), request.getRequestURI());
        errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, ErrorCodes.FORBIDDEN,
                "You do not have permission to access this resource.");
    }
}
