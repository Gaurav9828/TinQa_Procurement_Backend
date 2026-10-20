package com.tinqa.procurement.common.constant;

/**
 * Every machine-readable error code the API returns in {@code ApiResponse.errorCode}. Clients branch on these,
 * so they are part of the public contract and must not be renamed.
 */
public final class ErrorCodes {

    // Generic request and framework errors
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String UNSAFE_INPUT = "UNSAFE_INPUT";
    public static final String MISSING_REQUEST_PARAMETER = "MISSING_REQUEST_PARAMETER";
    public static final String MISSING_PATH_VARIABLE = "MISSING_PATH_VARIABLE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String CONFLICT = "CONFLICT";
    public static final String REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String DATABASE_CONFLICT = "DATABASE_CONFLICT";
    public static final String DATABASE_ERROR = "DATABASE_ERROR";
    public static final String OPTIMISTIC_LOCK_CONFLICT = "OPTIMISTIC_LOCK_CONFLICT";
    public static final String INTERNAL_PROCESSING_ERROR = "INTERNAL_PROCESSING_ERROR";
    public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
    public static final String API_ERROR = "API_ERROR";

    // Security
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String INTERNAL_AUTH_FAILED = "INTERNAL_AUTH_FAILED";
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_REUSED = "PASSWORD_REUSED";
    public static final String FIRST_LOGIN_NOT_REQUIRED = "FIRST_LOGIN_NOT_REQUIRED";
    public static final String TOO_MANY_PASSWORD_ATTEMPTS = "TOO_MANY_PASSWORD_ATTEMPTS";

    // Employees
    public static final String DUPLICATE_VALUE = "DUPLICATE_VALUE";
    public static final String EMPLOYEE_NOT_PENDING_APPROVAL = "EMPLOYEE_NOT_PENDING_APPROVAL";
    public static final String EMPLOYEE_APPROVAL_SELF_DECISION = "EMPLOYEE_APPROVAL_SELF_DECISION";
    public static final String EMPLOYEE_DELETED = "EMPLOYEE_DELETED";

    // Orders
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String ORDER_NOT_EDITABLE = "ORDER_NOT_EDITABLE";
    public static final String ORDER_STOCKED = "ORDER_STOCKED";
    public static final String ORDER_CHANGE_PENDING = "ORDER_CHANGE_PENDING";
    public static final String ORDER_CHANGE_NOT_PENDING = "ORDER_CHANGE_NOT_PENDING";
    public static final String ORDER_CHANGE_EMPTY = "ORDER_CHANGE_EMPTY";
    public static final String ORDER_INVOICE_REQUIRED = "ORDER_INVOICE_REQUIRED";
    public static final String ORDER_INVOICE_MISMATCH = "ORDER_INVOICE_MISMATCH";
    public static final String ORDER_INVOICE_NOT_ALLOWED = "ORDER_INVOICE_NOT_ALLOWED";

    // Dealer logins
    public static final String DEALER_INACTIVE = "DEALER_INACTIVE";
    public static final String DEALER_LOGIN_EXISTS = "DEALER_LOGIN_EXISTS";
    public static final String DEALER_LOGIN_NOT_PENDING = "DEALER_LOGIN_NOT_PENDING";
    public static final String DEALER_LOGIN_NOT_APPROVED = "DEALER_LOGIN_NOT_APPROVED";
    public static final String DEALER_LOGIN_SELF_DECISION = "DEALER_LOGIN_SELF_DECISION";

    // Stock
    public static final String ORDER_NOT_DELIVERED = "ORDER_NOT_DELIVERED";
    public static final String ORDER_ALREADY_HAS_STOCK = "ORDER_ALREADY_HAS_STOCK";
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String INSUFFICIENT_ITEM_STOCK = "INSUFFICIENT_ITEM_STOCK";
    public static final String REVERSAL_NOT_POSSIBLE = "REVERSAL_NOT_POSSIBLE";
    public static final String MOVEMENT_ID_CONFLICT = "MOVEMENT_ID_CONFLICT";
    public static final String MOVEMENT_IN_PROGRESS = "MOVEMENT_IN_PROGRESS";
    public static final String INVALID_MOVEMENT_LINES = "INVALID_MOVEMENT_LINES";
    public static final String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";

    // Product approvals
    public static final String PRODUCT_APPROVAL_PENDING = "PRODUCT_APPROVAL_PENDING";
    public static final String PRODUCT_APPROVAL_NOT_FOUND = "PRODUCT_APPROVAL_NOT_FOUND";
    public static final String PRODUCT_APPROVAL_CANCELLED = "PRODUCT_APPROVAL_CANCELLED";
    public static final String PRODUCT_APPROVAL_NOT_PENDING = "PRODUCT_APPROVAL_NOT_PENDING";
    public static final String PRODUCT_APPROVAL_NOT_APPLICABLE = "PRODUCT_APPROVAL_NOT_APPLICABLE";
    public static final String PRODUCT_APPROVAL_INVALID_DECISION = "PRODUCT_APPROVAL_INVALID_DECISION";
    public static final String PRODUCT_APPROVAL_SELF_DECISION = "PRODUCT_APPROVAL_SELF_DECISION";
    public static final String PRODUCT_ALREADY_APPROVED = "PRODUCT_ALREADY_APPROVED";
    public static final String PRODUCT_NOT_APPROVED = "PRODUCT_NOT_APPROVED";
    public static final String PRODUCT_UPDATE_NOT_APPLIED = "PRODUCT_UPDATE_NOT_APPLIED";

    // Documents
    public static final String INVALID_FILE = "INVALID_FILE";

    private ErrorCodes() {
    }
}
