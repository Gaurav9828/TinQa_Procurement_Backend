package com.tinqa.procurement.security.audit;

public enum AuthAuditEventType {
    FIRST_LOGIN_PASSWORD_SET,
    PASSWORD_CHANGED,
    /** Through an emailed link: a new account's first password, or "forgot password". */
    PASSWORD_SET_BY_LINK
}
