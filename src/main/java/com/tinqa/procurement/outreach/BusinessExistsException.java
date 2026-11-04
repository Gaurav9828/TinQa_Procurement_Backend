package com.tinqa.procurement.outreach;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * A business with this phone number or email is already on the list: 409 BUSINESS_EXISTS with
 * {@code data.existingId}, so the screen can open it ("we already contacted Radisson Blu").
 */
public class BusinessExistsException extends RuntimeException {

    private final long existingId;

    public BusinessExistsException(long existingId, String message) {
        super(message);
        this.existingId = existingId;
    }

    public long getExistingId() {
        return existingId;
    }

    /** Answers before the global handler, which would drop the existing business's id. */
    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Handler {

        @ExceptionHandler(BusinessExistsException.class)
        ResponseEntity<ApiResponse<Map<String, Long>>> handle(BusinessExistsException exception, HttpServletRequest request) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<Map<String, Long>>builder()
                    .success(false)
                    .message(exception.getMessage())
                    .errorCode(ErrorCodes.BUSINESS_EXISTS)
                    .data(Map.of("existingId", exception.getExistingId()))
                    .timestamp(Instant.now())
                    .path(request.getRequestURI())
                    .build());
        }
    }
}
