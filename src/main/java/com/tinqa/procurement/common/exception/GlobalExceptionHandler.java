package com.tinqa.procurement.common.exception;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.UnsafeInputException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns every exception into the standard error envelope. Server-side details are never sent to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_VALUE = "Invalid value";
    private static final String NOT_FOUND_MESSAGE = "The requested resource was not found.";
    private static final String UNEXPECTED_MESSAGE = "An unexpected error occurred while processing the request.";
    private static final String FORBIDDEN_MESSAGE = "You do not have permission to access this resource.";
    private static final String FILE_TOO_LARGE_MESSAGE = "The uploaded file exceeds the maximum allowed size.";

    private record StatusMessage(String message, String errorCode) {
    }

    // Generic wording for ResponseStatusException, by status code
    private static final Map<Integer, StatusMessage> STATUS_MESSAGES = Map.of(
            HttpStatus.TOO_MANY_REQUESTS.value(), new StatusMessage("Too many requests. Please try again later.", ErrorCodes.TOO_MANY_REQUESTS),
            HttpStatus.UNAUTHORIZED.value(), new StatusMessage("Authentication is required.", ErrorCodes.UNAUTHORIZED),
            HttpStatus.FORBIDDEN.value(), new StatusMessage(FORBIDDEN_MESSAGE, ErrorCodes.FORBIDDEN),
            HttpStatus.NOT_FOUND.value(), new StatusMessage(NOT_FOUND_MESSAGE, ErrorCodes.RESOURCE_NOT_FOUND),
            HttpStatus.CONFLICT.value(), new StatusMessage("The request conflicts with the current state.", ErrorCodes.CONFLICT),
            HttpStatus.PAYLOAD_TOO_LARGE.value(), new StatusMessage(FILE_TOO_LARGE_MESSAGE, ErrorCodes.FILE_TOO_LARGE));

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                          HttpServletRequest request) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.add(ApiErrors.error(error.getField(), messageOrDefault(error.getDefaultMessage()))));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                errors.add(ApiErrors.error(error.getObjectName(), messageOrDefault(error.getDefaultMessage()))));

        String message = errors.size() == 1 ? errors.getFirst().getMessage() : AppConstants.MESSAGE_FIELDS_INVALID;
        return build(HttpStatus.BAD_REQUEST, message, ErrorCodes.VALIDATION_FAILED, errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception,
                                                                       HttpServletRequest request) {
        List<ApiResponse.ApiErrorItem> errors = exception.getConstraintViolations().stream()
                .map(violation -> ApiErrors.error(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "One or more request parameters are invalid.", ErrorCodes.VALIDATION_FAILED,
                errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                          HttpServletRequest request) {
        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof UnsafeInputException)) {
            cause = cause.getCause();
        }
        if (cause instanceof UnsafeInputException unsafe) {
            String message = "Field '" + unsafe.getFieldPath() + "' " + unsafe.getReason() + ".";
            return build(HttpStatus.BAD_REQUEST, message, ErrorCodes.UNSAFE_INPUT,
                    List.of(ApiErrors.error(unsafe.getFieldPath(), message)), request);
        }
        return build(HttpStatus.BAD_REQUEST, "The request body is missing or contains invalid data.",
                ErrorCodes.MALFORMED_REQUEST, List.of(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(MissingServletRequestParameterException exception,
                                                                           HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Required request parameter '" + exception.getParameterName() + "' is missing.",
                ErrorCodes.MISSING_REQUEST_PARAMETER, List.of(ApiErrors.error(exception.getParameterName(), "Required")), request);
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPathVariable(MissingPathVariableException exception,
                                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Required path variable '" + exception.getVariableName() + "' is missing.",
                ErrorCodes.MISSING_PATH_VARIABLE, List.of(ApiErrors.error(exception.getVariableName(), "Required")), request);
    }

    // A path variable or parameter of the wrong type, e.g. an unknown status in /v1/orders/status/{status}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                                HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + exception.getName() + "'.",
                ErrorCodes.MALFORMED_REQUEST, List.of(ApiErrors.error(exception.getName(), "Invalid value")), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "The requested HTTP method is not supported for this endpoint.",
                ErrorCodes.METHOD_NOT_ALLOWED, List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The requested media type is not supported.",
                ErrorCodes.UNSUPPORTED_MEDIA_TYPE, List.of(), request);
    }

    // The specific message of a ResourceNotFoundException is deliberately not exposed
    @ExceptionHandler({NoResourceFoundException.class, EntityNotFoundException.class, ResourceNotFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, ErrorCodes.RESOURCE_NOT_FOUND, List.of(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "The request contains an invalid value.", ErrorCodes.INVALID_REQUEST, List.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, FILE_TOO_LARGE_MESSAGE, ErrorCodes.FILE_TOO_LARGE, List.of(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "The operation could not be completed because it violates a database constraint.",
                ErrorCodes.DATABASE_CONFLICT, List.of(), request);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockConflict(HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "This record was modified by someone else. Refresh and try again.",
                ErrorCodes.OPTIMISTIC_LOCK_CONFLICT, List.of(), request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(DataAccessException exception, HttpServletRequest request) {
        log.error("Database error. Method: {}, URI: {}", request.getMethod(), request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An error occurred while accessing the database.",
                ErrorCodes.DATABASE_ERROR, List.of(), request);
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiResponse<Void>> handleNullPointerException(NullPointerException exception, HttpServletRequest request) {
        log.error("Unexpected null value. Method: {}, URI: {}", request.getMethod(), request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_MESSAGE, ErrorCodes.INTERNAL_PROCESSING_ERROR, List.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE, ErrorCodes.FORBIDDEN, List.of(), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, messageOr(exception, "Authentication is required."), ErrorCodes.UNAUTHORIZED,
                List.of(), request);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequestException(BadRequestException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, messageOr(exception, "The request is invalid."), ErrorCodes.BAD_REQUEST,
                List.of(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflictException(ConflictException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, messageOr(exception, "The request conflicts with the current state."),
                ErrorCodes.CONFLICT, List.of(), request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception, HttpServletRequest request) {
        HttpStatus status = exception.getStatus();
        boolean serverError = status.is5xxServerError();
        if (serverError) {
            log.error("Server error. Method: {}, URI: {}", request.getMethod(), request.getRequestURI(), exception);
        }
        String errorCode = exception.getErrorCode() != null && !exception.getErrorCode().isBlank()
                ? exception.getErrorCode()
                : ErrorCodes.API_ERROR;
        return build(status, serverError ? UNEXPECTED_MESSAGE : exception.getMessage(), errorCode,
                serverError ? List.of() : exception.getErrors(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException exception,
                                                                           HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        StatusMessage known = STATUS_MESSAGES.get(status.value());
        StatusMessage reply = known != null ? known
                : status.is4xxClientError()
                ? new StatusMessage("The request could not be completed.", ErrorCodes.REQUEST_REJECTED)
                : new StatusMessage(UNEXPECTED_MESSAGE, ErrorCodes.INTERNAL_SERVER_ERROR);
        return build(status, reply.message(), reply.errorCode(), List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception. Method: {}, URI: {}", request.getMethod(), request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_MESSAGE, ErrorCodes.INTERNAL_SERVER_ERROR, List.of(), request);
    }

    private static String messageOrDefault(String message) {
        return message != null ? message : INVALID_VALUE;
    }

    private static String messageOr(RuntimeException exception, String fallback) {
        return exception.getMessage() != null ? exception.getMessage() : fallback;
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatusCode status, String message, String errorCode,
                                                    List<ApiResponse.ApiErrorItem> errors, HttpServletRequest request) {
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
