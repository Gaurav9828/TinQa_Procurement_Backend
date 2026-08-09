package com.tinqa.procurement.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
    public ProblemDetail handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {

        log.warn(
                "Validation failed. Method: {}, URI: {}",
                request.getMethod(),
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );

        problemDetail.setTitle("Validation Failed");
        problemDetail.setDetail("One or more request fields are invalid.");

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

        problemDetail.setProperty("errors", errors);
        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {

        log.warn(
                "Constraint violation. URI: {}",
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );

        problemDetail.setTitle("Constraint Violation");
        problemDetail.setDetail("One or more request parameters are invalid.");

        List<String> errors = exception.getConstraintViolations()
                .stream()
                .map(violation ->
                        violation.getPropertyPath() + ": " +
                                violation.getMessage()
                )
                .toList();

        problemDetail.setProperty("errors", errors);
        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    /*
     * ============================================================
     * MALFORMED REQUEST
     * ============================================================
     */

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {

        log.warn(
                "Malformed request body. URI: {}",
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );

        problemDetail.setTitle("Malformed Request");
        problemDetail.setDetail(
                "The request body is missing or contains invalid data."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {

        log.warn(
                "Missing request parameter '{}'. URI: {}",
                exception.getParameterName(),
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );

        problemDetail.setTitle("Missing Request Parameter");
        problemDetail.setDetail(
                "Required request parameter '" +
                        exception.getParameterName() +
                        "' is missing."
        );

        problemDetail.setProperty("parameter", exception.getParameterName());
        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(MissingPathVariableException.class)
    public ProblemDetail handleMissingPathVariable(
            MissingPathVariableException exception,
            HttpServletRequest request) {

        log.warn(
                "Missing path variable '{}'. URI: {}",
                exception.getVariableName(),
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );

        problemDetail.setTitle("Missing Path Variable");
        problemDetail.setDetail(
                "Required path variable '" +
                        exception.getVariableName() +
                        "' is missing."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    /*
     * ============================================================
     * HTTP REQUEST ERRORS
     * ============================================================
     */

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {

        log.warn(
                "HTTP method '{}' not supported for URI: {}",
                request.getMethod(),
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.METHOD_NOT_ALLOWED
        );

        problemDetail.setTitle("Method Not Allowed");
        problemDetail.setDetail(
                "HTTP method '" +
                        request.getMethod() +
                        "' is not supported for this endpoint."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {

        log.warn(
                "Unsupported media type. URI: {}",
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE
        );

        problemDetail.setTitle("Unsupported Media Type");
        problemDetail.setDetail(
                "The requested media type is not supported."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleResourceNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {

        log.warn(
                "Resource not found. URI: {}",
                request.getRequestURI()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.NOT_FOUND
        );

        problemDetail.setTitle("Resource Not Found");
        problemDetail.setDetail(
                "The requested resource was not found."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    /*
     * ============================================================
     * APPLICATION / BUSINESS EXCEPTIONS
     * ============================================================
     */

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(
            EntityNotFoundException exception,
            HttpServletRequest request) {

        log.warn(
                "Entity not found. URI: {}, Message: {}",
                request.getRequestURI(),
                exception.getMessage()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.NOT_FOUND
        );

        problemDetail.setTitle("Resource Not Found");
        problemDetail.setDetail(
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "Requested resource was not found."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {

        log.warn(
                "Illegal argument. URI: {}, Message: {}",
                request.getRequestURI(),
                exception.getMessage()
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.BAD_REQUEST
        );

        problemDetail.setTitle("Invalid Request");
        problemDetail.setDetail(
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "The request contains an invalid argument."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    /*
     * ============================================================
     * DATABASE EXCEPTIONS
     * ============================================================
     */

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {

        log.error(
                "Database integrity violation. URI: {}",
                request.getRequestURI(),
                exception
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.CONFLICT
        );

        problemDetail.setTitle("Database Conflict");
        problemDetail.setDetail(
                "The operation could not be completed because it violates " +
                        "a database constraint."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDataAccessException(
            DataAccessException exception,
            HttpServletRequest request) {

        log.error(
                "Database access error. URI: {}",
                request.getRequestURI(),
                exception
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        problemDetail.setTitle("Database Error");
        problemDetail.setDetail(
                "An error occurred while accessing the database."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    /*
     * ============================================================
     * NULL POINTER
     * ============================================================
     */

    @ExceptionHandler(NullPointerException.class)
    public ProblemDetail handleNullPointerException(
            NullPointerException exception,
            HttpServletRequest request) {

        /*
         * IMPORTANT:
         * Never expose exception.getMessage() or stack trace
         * to the client in production.
         */

        log.error(
                "Unexpected NullPointerException. URI: {}",
                request.getRequestURI(),
                exception
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        problemDetail.setTitle("Internal Processing Error");
        problemDetail.setDetail(
                "An unexpected error occurred while processing the request."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }


    /*
     * ============================================================
     * GENERIC FALLBACK
     * ============================================================
     */

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(
            Exception exception,
            HttpServletRequest request) {

        /*
         * This should be the LAST safety net.
         *
         * Known exceptions should always have their own handler
         * above this method.
         */

        log.error(
                "Unhandled exception. Method: {}, URI: {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        ProblemDetail problemDetail = ProblemDetail.forStatus(
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        problemDetail.setTitle("Internal Server Error");
        problemDetail.setDetail(
                "An unexpected error occurred while processing the request."
        );

        problemDetail.setProperty("path", request.getRequestURI());

        return problemDetail;
    }
}