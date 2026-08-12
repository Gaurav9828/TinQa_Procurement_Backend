package com.tinqa.procurement.common.exception;

import com.tinqa.procurement.response.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /*
     * ============================================================
     * VALIDATION EXCEPTIONS
     * ============================================================
     */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {

        log.warn(
                "Validation failed. Method: {}, URI: {}",
                request.getMethod(),
                request.getRequestURI()
        );

        List<String> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error ->
                        error.getField() + ": " +
                                (error.getDefaultMessage() != null
                                        ? error.getDefaultMessage()
                                        : "Invalid value")
                )
                .toList();

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Validation Failed",
                "One or more request fields are invalid.",
                "VALIDATION_ERROR",
                errors,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<List<String>>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {

        log.warn(
                "Constraint violation. URI: {}",
                request.getRequestURI()
        );

        List<String> errors = exception.getConstraintViolations()
                .stream()
                .map(violation ->
                        violation.getPropertyPath() + ": " +
                                violation.getMessage()
                )
                .toList();

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Constraint Violation",
                "One or more request parameters are invalid.",
                "CONSTRAINT_VIOLATION",
                errors,
                request
        );
    }


    /*
     * ============================================================
     * MALFORMED REQUEST
     * ============================================================
     */

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {

        log.warn(
                "Malformed request body. URI: {}",
                request.getRequestURI()
        );

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed Request",
                "The request body is missing or contains invalid data.",
                "MALFORMED_REQUEST",
                request
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {

        log.warn(
                "Missing request parameter '{}'. URI: {}",
                exception.getParameterName(),
                request.getRequestURI()
        );

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Missing Request Parameter",
                "Required request parameter '" +
                        exception.getParameterName() +
                        "' is missing.",
                "MISSING_REQUEST_PARAMETER",
                request
        );
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPathVariable(
            MissingPathVariableException exception,
            HttpServletRequest request) {

        log.warn(
                "Missing path variable '{}'. URI: {}",
                exception.getVariableName(),
                request.getRequestURI()
        );

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Missing Path Variable",
                "Required path variable '" +
                        exception.getVariableName() +
                        "' is missing.",
                "MISSING_PATH_VARIABLE",
                request
        );
    }


    /*
     * ============================================================
     * HTTP REQUEST ERRORS
     * ============================================================
     */

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {

        log.warn(
                "HTTP method '{}' not supported for URI: {}",
                request.getMethod(),
                request.getRequestURI()
        );

        return buildErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Method Not Allowed",
                "HTTP method '" +
                        request.getMethod() +
                        "' is not supported for this endpoint.",
                "METHOD_NOT_ALLOWED",
                request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {

        log.warn(
                "Unsupported media type. URI: {}",
                request.getRequestURI()
        );

        return buildErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Media Type",
                "The requested media type is not supported.",
                "UNSUPPORTED_MEDIA_TYPE",
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {

        log.warn(
                "Resource not found. URI: {}",
                request.getRequestURI()
        );

        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Resource Not Found",
                "The requested resource was not found.",
                "RESOURCE_NOT_FOUND",
                request
        );
    }


    /*
     * ============================================================
     * APPLICATION / BUSINESS EXCEPTIONS
     * ============================================================
     */

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(
            EntityNotFoundException exception,
            HttpServletRequest request) {

        log.warn(
                "Entity not found. URI: {}, Message: {}",
                request.getRequestURI(),
                exception.getMessage()
        );

        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Resource Not Found",
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "Requested resource was not found.",
                "RESOURCE_NOT_FOUND",
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {

        log.warn(
                "Illegal argument. URI: {}, Message: {}",
                request.getRequestURI(),
                exception.getMessage()
        );

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid Request",
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "The request contains an invalid argument.",
                "INVALID_REQUEST",
                request
        );
    }


    /*
     * ============================================================
     * DATABASE EXCEPTIONS
     * ============================================================
     */

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {

        log.error(
                "Database integrity violation. URI: {}",
                request.getRequestURI(),
                exception
        );

        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Database Conflict",
                "The operation could not be completed because it violates a database constraint.",
                "DATABASE_CONFLICT",
                request
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(
            DataAccessException exception,
            HttpServletRequest request) {

        log.error(
                "Database access error. URI: {}",
                request.getRequestURI(),
                exception
        );

        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Database Error",
                "An error occurred while accessing the database.",
                "DATABASE_ERROR",
                request
        );
    }


    /*
     * ============================================================
     * NULL POINTER
     * ============================================================
     */

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiResponse<Void>> handleNullPointerException(
            NullPointerException exception,
            HttpServletRequest request) {

        log.error(
                "Unexpected NullPointerException. URI: {}",
                request.getRequestURI(),
                exception
        );

        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Processing Error",
                "An unexpected error occurred while processing the request.",
                "INTERNAL_PROCESSING_ERROR",
                request
        );
    }


    /*
     * ============================================================
     * CUSTOM APPLICATION EXCEPTIONS
     * ============================================================
     */

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequestException(
            BadRequestException exception,
            HttpServletRequest request) {

        log.warn(
                "Bad request. URI: {}, Message: {}",
                request.getRequestURI(),
                exception.getMessage()
        );

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                exception.getMessage(),
                "BAD_REQUEST",
                request
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflictException(
            ConflictException exception,
            HttpServletRequest request) {

        log.warn(
                "Request conflict. URI: {}, Message: {}",
                request.getRequestURI(),
                exception.getMessage()
        );

        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                exception.getMessage(),
                "CONFLICT",
                request
        );
    }


    /*
     * ============================================================
     * GENERIC FALLBACK
     * ============================================================
     */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
            Exception exception,
            HttpServletRequest request) {

        log.error(
                "Unhandled exception. Method: {}, URI: {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred while processing the request.",
                "INTERNAL_SERVER_ERROR",
                request
        );
    }


    /*
     * ============================================================
     * RESPONSE BUILDERS
     * ============================================================
     */

    private <T> ResponseEntity<ApiResponse<T>> buildErrorResponse(
            HttpStatus status,
            String title,
            String message,
            String errorCode,
            T data,
            HttpServletRequest request) {

        return ResponseEntity.status(status)
                .body(
                        ApiResponse.<T>builder()
                                .success(false)
                                .message(message)
                                .errorCode(errorCode)
                                .data(data)
                                .path(request.getRequestURI())
                                .build()
                );
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(
            HttpStatus status,
            String title,
            String message,
            String errorCode,
            HttpServletRequest request) {

        return ResponseEntity.status(status)
                .body(
                        ApiResponse.<Void>builder()
                                .success(false)
                                .message(message)
                                .errorCode(errorCode)
                                .path(request.getRequestURI())
                                .build()
                );
    }
}