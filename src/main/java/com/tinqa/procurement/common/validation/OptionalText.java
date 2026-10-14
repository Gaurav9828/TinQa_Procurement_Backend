package com.tinqa.procurement.common.validation;

/**
 * Optional text fields (e.g. middle name): a blank value means "none" and is stored as null.
 */
public final class OptionalText {

    private OptionalText() {
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Whether an edit changes an optional value. A null request means "not sent" (no change); blank and null
     * count as the same "none".
     */
    public static boolean changes(String current, String requested) {
        return requested != null && !java.util.Objects.equals(blankToNull(current), blankToNull(requested));
    }
}
