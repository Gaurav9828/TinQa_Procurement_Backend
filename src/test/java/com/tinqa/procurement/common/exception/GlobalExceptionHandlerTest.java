package com.tinqa.procurement.common.exception;

import com.tinqa.procurement.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingResourcesUse404AndStandardEnvelope() {
        MockHttpServletRequest request = request();

        var response = handler.handleResourceNotFound(
                new ResourceNotFoundException("internal storage key"),
                request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertErrorEnvelope(response.getBody(), "RESOURCE_NOT_FOUND", request.getRequestURI());
        assertEquals("The requested resource was not found.", response.getBody().getMessage());
    }

    @Test
    void uploadLimitUses413AndStandardEnvelope() {
        MockHttpServletRequest request = request();

        var response = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(1024),
                request
        );

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertErrorEnvelope(response.getBody(), "FILE_TOO_LARGE", request.getRequestURI());
    }

    @Test
    void optimisticLockingUses409AndStandardEnvelope() {
        MockHttpServletRequest request = request();

        var response = handler.handleOptimisticLockConflict(
                new OptimisticLockException("internal persistence details"),
                request
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertErrorEnvelope(response.getBody(), "OPTIMISTIC_LOCK_CONFLICT", request.getRequestURI());
        assertEquals(
                "This record was modified by someone else. Refresh and try again.",
                response.getBody().getMessage()
        );
    }

    @Test
    void successResponsesDoNotGainErrorFields() throws Exception {
        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(true)
                .message("Success")
                .path("/api/v1/test")
                .build();

        assertNull(response.getErrors());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("\"errors\""));
        assertTrue(json.contains("\"success\":true"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        return request;
    }

    private static void assertErrorEnvelope(
            ApiResponse<Void> body,
            String errorCode,
            String path) {
        assertFalse(body.isSuccess());
        assertEquals(errorCode, body.getErrorCode());
        assertEquals(path, body.getPath());
        assertEquals(java.util.List.of(), body.getErrors());
        org.junit.jupiter.api.Assertions.assertNotNull(body.getTimestamp());
    }
}
