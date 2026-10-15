package com.tinqa.procurement.security.password;

import java.util.regex.Pattern;

/**
 * The one rule for a password a user sets themselves (first login and change password). It applies only when a
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
}
