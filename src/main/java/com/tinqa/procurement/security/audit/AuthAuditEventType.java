package com.tinqa.procurement.security.audit;

public enum AuthAuditEventType {
    FIRST_LOGIN_PASSWORD_SET,
    PASSWORD_CHANGED,
    /** Through an emailed link: a new account's first password, or "forgot password". */
    PASSWORD_SET_BY_LINK,
    LOGIN_SUCCESS,
    /** Wrong password, unknown username, or an account that may not sign in (disabled, locked, wrong channel). */
    LOGIN_FAILED,
    /** Refused without checking the password: too many failed attempts on this username recently. */
    LOGIN_BLOCKED
}
