package com.tinqa.procurement.mail;

/** A staff email could not be sent (email not set up, mail server down, or the e-commerce service unreachable). */
public class StaffMailException extends RuntimeException {

    /** True when email is simply not set up yet (Company Profile › Email). */
    private final boolean notConfigured;

    public StaffMailException(String message, boolean notConfigured, Throwable cause) {
        super(message, cause);
        this.notConfigured = notConfigured;
    }

    public boolean isNotConfigured() {
        return notConfigured;
    }
}
