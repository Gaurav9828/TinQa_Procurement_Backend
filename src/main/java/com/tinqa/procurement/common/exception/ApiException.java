package com.tinqa.procurement.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

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
    }

    public ApiException(
            String message,
            Throwable cause) {

        super(message, cause);
        this.errorCode = null;
        this.status = HttpStatus.BAD_REQUEST;
    }

    public ApiException(
            String message,
            String errorCode,
            HttpStatus status,
            Throwable cause) {

        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
    }
}