package com.tinqa.procurement.common.validation;

/**
 * Field formats shared by every request DTO, so the same kind of value is validated the same way everywhere.
 */
public final class ValidationPatterns {

    // Money: nothing in the system may be priced above ₹1,00,00,000 (1 crore)
    public static final String MAX_AMOUNT = "10000000.00";
    public static final String MAX_AMOUNT_LABEL = "₹1,00,00,000 (1 crore)";

    // People: letters (any script), spaces, dot, apostrophe, hyphen
    public static final String PERSON_NAME = "^[\\p{L}][\\p{L}\\p{M} .'-]*$";
    public static final String PERSON_NAME_MESSAGE = "can only contain letters, spaces, dots, apostrophes and hyphens";

    // Single-line business text (names of things, addresses, designations): starts with a letter, digit, # or (
    public static final String SINGLE_LINE_TEXT = "^[\\p{L}\\p{N}#(][\\p{L}\\p{M}\\p{N} .,'&()/#:+_@%-]*$";
    public static final String SINGLE_LINE_TEXT_MESSAGE = "can only contain letters, numbers, spaces and . , ' & ( ) / # : + _ @ % -";

    // Indian mobile (optional +91 or 0 prefix) or any other country in E.164 form
    public static final String PHONE = "^(?:(?:\\+91|0)?[6-9]\\d{9}|\\+(?!91)[1-9]\\d{7,14})$";
    public static final String PHONE_MESSAGE =
            "must be a valid mobile number: 10 digits starting with 6-9 (optionally +91 or 0 in front), or +<country code><number>";

    public static final String EMAIL =
            "^(?!.*\\.\\.)[A-Za-z0-9][A-Za-z0-9._%+-]{0,63}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.[A-Za-z]{2,24}$";
    public static final String EMAIL_MESSAGE = "must be a valid email address, e.g. name@company.com";

    public static final String USERNAME = "^[A-Za-z0-9][A-Za-z0-9._-]{1,48}[A-Za-z0-9]$";
    public static final String USERNAME_MESSAGE =
            "must be 3-50 characters: letters, numbers, dot, underscore or hyphen, starting and ending with a letter or number";

    public static final String CITY = "^[\\p{L}][\\p{L}\\p{M} .'()-]*$";
    public static final String CITY_MESSAGE = "must be a valid city name (letters, spaces, . ' ( ) -)";

    public static final String GSTIN = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";
    public static final String PAN = "^[A-Z]{3}[ABCFGHLJPT][A-Z][0-9]{4}[A-Z]$";

    // Identifiers and codes
    public static final String CODE = "^[A-Za-z0-9][A-Za-z0-9_-]*$";
    public static final String CODE_MESSAGE = "can only contain letters, numbers, underscore and hyphen";
    public static final String UNIT = "^[A-Za-z][A-Za-z ._-]*$";
    public static final String UNIT_MESSAGE = "must be a unit name such as PCS, KG or METER";

    public static final String GENDER = "MALE|FEMALE|OTHER";
    public static final String EMPLOYMENT_TYPE = "FULL_TIME|PART_TIME|CONTRACT|INTERN|CONSULTANT|TEMPORARY";
    public static final String ROLE = "(?i)ADMIN_L1|ADMIN_L2|DEALER|INSPECTOR";

    private ValidationPatterns() {
    }
}
