package com.tinqa.procurement.common.constant;

/**
 * Values shared across modules that are not configuration (configuration lives in the *Properties classes).
 */
public final class AppConstants {

    public static final String TOKEN_TYPE_BEARER = "Bearer";
    public static final String BEARER_PREFIX = TOKEN_TYPE_BEARER + " ";
    public static final String DEFAULT_CURRENCY = "INR";

    // Prefixes of generated business identifiers, e.g. ORD-1728375000000-AB12
    public static final String ORDER_NUMBER_PREFIX = "ORD";
    public static final String STOCK_IDENTITY_PREFIX = "STK";
    public static final String BATCH_NUMBER_PREFIX = "BAT";
    public static final String PRODUCT_ID_PREFIX = "PRD";
    public static final String EMPLOYEE_CODE_FORMAT = "EMP-%04d";

    // Employees cannot join before this age
    public static final int MINIMUM_EMPLOYEE_AGE = 18;

    // Generic messages shared by several modules
    public static final String MESSAGE_INVALID_CREDENTIALS = "Invalid username or password";
    public static final String MESSAGE_FIELDS_INVALID = "One or more request fields are invalid.";
    public static final String MESSAGE_ACCOUNT_DISABLED = "User account is disabled";
    public static final String MESSAGE_ACCOUNT_LOCKED = "User account is locked";

    private AppConstants() {
    }
}
