package com.tinqa.procurement.mail;

/**
 * Sends account emails to staff and dealers. The message wording, branding and the link's page belong to the sender;
 * callers pass only the secret (a code or a link token) and who it is for.
 */
public interface StaffMailer {

    /** @throws StaffMailException when the email could not be sent */
    void sendVerificationCode(String to, String name, String code, long validMinutes);

    /**
     * @param setup true for a new account's first password, false for "forgot password"
     * @throws StaffMailException when the email could not be sent
     */
    void sendPasswordLink(String to, String name, String token, boolean setup, long validMinutes);
}
