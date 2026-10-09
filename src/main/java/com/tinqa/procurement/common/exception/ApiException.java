package com.tinqa.procurement.common.exception;

import com.tinqa.procurement.common.response.ApiResponse;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

@Getter
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;
    private final List<ApiResponse.ApiErrorItem> errors;

    public ApiException(String message) {
        this(message, null, HttpStatus.BAD_REQUEST);
    }

    public ApiException(
            String message,
            String errorCode) {

        this(message, errorCode, HttpStatus.BAD_REQUEST);
    }

    public ApiException(
            String message,
            String errorCode,
            HttpStatus status) {

        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.errors = List.of();
    }

    public ApiException(
            String message,
            Throwable cause) {

        super(message, cause);
        this.errorCode = null;
        this.status = HttpStatus.BAD_REQUEST;
        this.errors = List.of();
    }

    public ApiException(
            String message,
            String errorCode,
            HttpStatus status,
            Throwable cause) {

        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
        this.errors = List.of();
    }

    public ApiException(
            String message,
            String errorCode,
            HttpStatus status,
            List<ApiResponse.ApiErrorItem> errors) {

        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.errors = List.copyOf(errors);
    }
}
