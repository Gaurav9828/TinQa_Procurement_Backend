package com.tinqa.procurement.security.password;

/** Why a password link was emailed ({@code password_link.purpose}). */
public enum PasswordLinkPurpose {
    /** A new employee chooses their first password. */
    SETUP,
    /** "Forgot password?" on the sign-in page. */
    RESET
}
