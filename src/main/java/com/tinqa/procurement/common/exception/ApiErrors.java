package com.tinqa.procurement.common.exception;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.response.ApiResponse;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory for the {@link ApiException}s services throw, so every module reports the same kind of problem the
 * same way (status, error code and per-field details).
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiException of(HttpStatus status, String errorCode, String message) {
        return new ApiException(message, errorCode, status);
    }

    public static ApiException conflict(String errorCode, String message) {
        return of(HttpStatus.CONFLICT, errorCode, message);
    }

    public static ApiException forbidden(String errorCode, String message) {
        return of(HttpStatus.FORBIDDEN, errorCode, message);
    }

    /** A 400 VALIDATION_FAILED error on one request field. */
    public static ApiException fieldError(String field, String message) {
        return new ApiException(message, ErrorCodes.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, List.of(error(field, message)));
    }

    /** A 400 VALIDATION_FAILED error listing every invalid field (the message is the only error's, if just one). */
    public static ApiException validationFailed(List<ApiResponse.ApiErrorItem> errors) {
        String message = errors.size() == 1 ? errors.getFirst().getMessage() : AppConstants.MESSAGE_FIELDS_INVALID;
        return new ApiException(message, ErrorCodes.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, errors);
    }

    /** Throws {@link #validationFailed} when any error was collected. */
    public static void throwIfAny(List<ApiResponse.ApiErrorItem> errors) {
        if (!errors.isEmpty()) {
            throw validationFailed(errors);
        }
    }

    public static ApiResponse.ApiErrorItem error(String field, String message) {
        return new ApiResponse.ApiErrorItem(field, message);
    }

    /** The messages of several errors as one sentence list, e.g. for a 409 that reports every shortage. */
    public static String joinMessages(List<ApiResponse.ApiErrorItem> errors) {
        return errors.stream().map(ApiResponse.ApiErrorItem::getMessage).collect(Collectors.joining("; "));
    }
}
