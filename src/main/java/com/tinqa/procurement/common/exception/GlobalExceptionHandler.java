package com.tinqa.procurement.common.exception;

import com.tinqa.procurement.common.response.ApiResponse;
import jakarta.persistence.OptimisticLockException;
import com.tinqa.procurement.common.validation.UnsafeInputException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {

        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.add(ApiResponse.ApiErrorItem.builder()
                        .field(error.getField())
                        .message(error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value")
                        .build()));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                errors.add(ApiResponse.ApiErrorItem.builder()
                        .field(error.getObjectName())
                        .message(error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value")
                        .build()));

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                errors.size() == 1 ? errors.getFirst().getMessage() : "One or more request fields are invalid.",
                "VALIDATION_FAILED",
                errors,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {

        List<ApiResponse.ApiErrorItem> errors = exception.getConstraintViolations().stream()
                .map(violation -> ApiResponse.ApiErrorItem.builder()
                        .field(violation.getPropertyPath().toString())
                        .message(violation.getMessage())
                        .build())
                .toList();

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "One or more request parameters are invalid.",
                "VALIDATION_FAILED",
                errors,
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof UnsafeInputException)) {
            cause = cause.getCause();
        }
        if (cause instanceof UnsafeInputException unsafe) {
            String message = "Field '" + unsafe.getFieldPath() + "' " + unsafe.getReason() + ".";
            return buildErrorResponse(
                    HttpStatus.BAD_REQUEST,
                    "Unsafe input",
                    message,
                    "UNSAFE_INPUT",
                    List.of(new ApiResponse.ApiErrorItem(unsafe.getFieldPath(), message)),
                    request
            );
        }
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed request",
                "The request body is missing or contains invalid data.",
                "MALFORMED_REQUEST",
                List.of(),
                request
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Missing request parameter",
                "Required request parameter '" + exception.getParameterName() + "' is missing.",
                "MISSING_REQUEST_PARAMETER",
                List.of(ApiResponse.ApiErrorItem.builder()
                        .field(exception.getParameterName())
                        .message("Required")
                        .build()),
                request
        );
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPathVariable(
            MissingPathVariableException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Missing path variable",
                "Required path variable '" + exception.getVariableName() + "' is missing.",
                "MISSING_PATH_VARIABLE",
                List.of(ApiResponse.ApiErrorItem.builder()
                        .field(exception.getVariableName())
                        .message("Required")
                        .build()),
                request
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Method not allowed",
                "The requested HTTP method is not supported for this endpoint.",
                "METHOD_NOT_ALLOWED",
                List.of(),
                request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported media type",
                "The requested media type is not supported.",
                "UNSUPPORTED_MEDIA_TYPE",
                List.of(),
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                "The requested resource was not found.",
                "RESOURCE_NOT_FOUND",
                List.of(),
                request
        );
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(
            EntityNotFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                "The requested resource was not found.",
                "RESOURCE_NOT_FOUND",
                List.of(),
                request
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                "The requested resource was not found.",
                "RESOURCE_NOT_FOUND",
                List.of(),
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                "The request contains an invalid value.",
                "INVALID_REQUEST",
                List.of(),
                request
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "File too large",
                "The uploaded file exceeds the maximum allowed size.",
                "FILE_TOO_LARGE",
                List.of(),
                request
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Database conflict",
                "The operation could not be completed because it violates a database constraint.",
                "DATABASE_CONFLICT",
                List.of(),
                request
        );
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockConflict(
            RuntimeException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Update conflict",
                "This record was modified by someone else. Refresh and try again.",
                "OPTIMISTIC_LOCK_CONFLICT",
                List.of(),
                request
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            DataAccessException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An error occurred while accessing the database.",
                "DATABASE_ERROR",
                List.of(),
                request
        );
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiResponse<Void>> handleNullPointerException(
            NullPointerException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred while processing the request.",
                "INTERNAL_PROCESSING_ERROR",
                List.of(),
                request
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "You do not have permission to access this resource.",
                "FORBIDDEN",
                List.of(),
                request
        );
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(
            UnauthorizedException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                exception.getMessage() != null ? exception.getMessage() : "Authentication is required.",
                "UNAUTHORIZED",
                List.of(),
                request
        );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequestException(
            BadRequestException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad request",
                exception.getMessage() != null ? exception.getMessage() : "The request is invalid.",
                "BAD_REQUEST",
                List.of(),
                request
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflictException(
            ConflictException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                exception.getMessage() != null ? exception.getMessage() : "The request conflicts with the current state.",
                "CONFLICT",
                List.of(),
                request
        );
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(
            ApiException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.getStatus();
        boolean serverError = status.is5xxServerError();
        return buildErrorResponse(
                status,
                status.getReasonPhrase(),
                serverError
                        ? "An unexpected error occurred while processing the request."
                        : exception.getMessage(),
                exception.getErrorCode() != null && !exception.getErrorCode().isBlank()
                        ? exception.getErrorCode()
                        : "API_ERROR",
                serverError ? List.of() : exception.getErrors(),
                request
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        String message;
        String errorCode;

        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            message = "Too many requests. Please try again later.";
            errorCode = "TOO_MANY_REQUESTS";
        } else if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            message = "Authentication is required.";
            errorCode = "UNAUTHORIZED";
        } else if (status.value() == HttpStatus.FORBIDDEN.value()) {
            message = "You do not have permission to access this resource.";
            errorCode = "FORBIDDEN";
        } else if (status.value() == HttpStatus.NOT_FOUND.value()) {
            message = "The requested resource was not found.";
            errorCode = "RESOURCE_NOT_FOUND";
        } else if (status.value() == HttpStatus.CONFLICT.value()) {
            message = "The request conflicts with the current state.";
            errorCode = "CONFLICT";
        } else if (status.value() == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            message = "The uploaded file exceeds the maximum allowed size.";
            errorCode = "FILE_TOO_LARGE";
        } else if (status.is4xxClientError()) {
            message = "The request could not be completed.";
            errorCode = "REQUEST_REJECTED";
        } else {
            message = "An unexpected error occurred while processing the request.";
            errorCode = "INTERNAL_SERVER_ERROR";
        }

        return buildErrorResponse(
                status,
                status.toString(),
                message,
                errorCode,
                List.of(),
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unhandled exception. Method: {}, URI: {}",
                request.getMethod(), request.getRequestURI(), exception);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred while processing the request.",
                "INTERNAL_SERVER_ERROR",
                List.of(),
                request
        );
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(
            HttpStatusCode status,
            String title,
            String message,
            String errorCode,
            List<ApiResponse.ApiErrorItem> errors,
            HttpServletRequest request) {

        return ResponseEntity.status(status).body(ApiResponse.<Void>builder()
                .success(false)
                .message(message)
                .errorCode(errorCode)
                .errors(errors)
                .timestamp(Instant.now())
                .path(request.getRequestURI())
                .build());
    }
}