package com.tinqa.procurement.security.password;

import com.tinqa.procurement.common.exception.ApiErrors;

import java.util.regex.Pattern;

/**
 * The one rule for a password a user sets themselves (first login, change password and emailed links). It applies only when a
 * password is set, so existing weaker passwords still log in. The value is checked as sent: never trimmed.
 */
public final class PasswordPolicy {

    public static final String MESSAGE = "Password must be 12-128 characters with no spaces, and include an uppercase "
            + "letter, a lowercase letter, a number and a special character.";

    private static final Pattern PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{12,128}$");

    private PasswordPolicy() {
    }

    public static boolean isSatisfiedBy(String password) {
        return password != null && PATTERN.matcher(password).matches();
    }

    /** A new password and its confirmation, checked exactly as sent (a password is never trimmed). */
    public static void requireNewPassword(String newPassword, String confirmNewPassword) {
        if (!isSatisfiedBy(newPassword)) {
            throw ApiErrors.fieldError("newPassword", MESSAGE);
        }
        if (!newPassword.equals(confirmNewPassword)) {
            throw ApiErrors.fieldError("confirmNewPassword", "New password and confirm password do not match.");
        }
    }
}
