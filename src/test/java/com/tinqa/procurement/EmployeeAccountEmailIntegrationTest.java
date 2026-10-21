package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.mail.StaffMailException;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A new employee's personal email is verified with a code; passwords are chosen through emailed, single-use links. */
class EmployeeAccountEmailIntegrationTest extends IntegrationTestSupport {

    @Test
    void theAdministratorVerifiesThePersonalEmailWithTheCodeSentThere() throws Exception {
        String email = uniq("verify") + "@example.test";
        JsonNode sent = data(post("/v1/admin/employees/email-verification", l2a.token(), body("email", email, "name", "Priya"), 200));
        assertThat(sent.get("codeValidMinutes").asLong()).isEqualTo(10);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(staffMailer).sendVerificationCode(eq(email), eq("Priya"), code.capture(), eq(10L));
        assertThat(code.getValue()).matches("\\d{5}");
        assertThat(jdbc.queryForObject("SELECT code_hash FROM email_verification WHERE email = ?", String.class, email))
                .isNotEqualTo(code.getValue());

        // At once again: refused; a wrong code counts down
        JsonNode tooSoon = post("/v1/admin/employees/email-verification", l2a.token(), body("email", email), 429);
        assertThat(tooSoon.get("errorCode").asText()).isEqualTo("VERIFICATION_TOO_SOON");
        String wrong = code.getValue().equals("00000") ? "11111" : "00000";
        JsonNode mistake = post("/v1/admin/employees/email-verification/confirm", l2a.token(), body("email", email, "code", wrong), 400);
        assertThat(mistake.get("message").asText()).contains("4 tries left");

        String token = data(post("/v1/admin/employees/email-verification/confirm", l2a.token(),
                body("email", email, "code", code.getValue()), 200)).get("verificationToken").asText();
        Map<String, Object> employee = withPanAndAddress(body("username", uniq("ver"), "email", uniq("ver") + "@tinqa.test",
                "firstName", letters(), "lastName", letters(), "dateOfBirth", "1990-01-15", "gender", "FEMALE",
                "designation", "DELIVERY_RIDER", "employmentType", "FULL_TIME", "joiningDate", "2015-06-01",
                "phone", nextLocalMobile(), "role", "ADMIN_L1"));
        employee.put("personalEmail", email);
        employee.put("personalEmailVerificationToken", "not-the-token");
        JsonNode unverified = post("/v1/admin/employees", l2a.token(), employee, 400);
        assertThat(unverified.get("errorCode").asText()).isEqualTo("EMAIL_NOT_VERIFIED");

        employee.put("personalEmailVerificationToken", token);
        post("/v1/admin/employees", l2a.token(), employee, 201);
        // A verification is used once (and the address now belongs to that employee)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verification WHERE email = ? AND consumed_at IS NOT NULL",
                Integer.class, email.toLowerCase())).isEqualTo(1);
        employee.putAll(body("username", uniq("ver"), "email", uniq("ver") + "@tinqa.test", "phone", nextLocalMobile(),
                "panNumber", nextPersonalPan()));
        assertThat(post("/v1/admin/employees", l2a.token(), employee, 409).get("errorCode").asText()).isEqualTo("DUPLICATE_VALUE");
        // ...and doesn't prove any other address
        employee.put("personalEmail", uniq("other") + "@example.test");
        assertThat(post("/v1/admin/employees", l2a.token(), employee, 400).get("errorCode").asText()).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void forgotPasswordEmailsAResetLinkThatWorksOnceAndSignsOutOldSessions() throws Exception {
        String username = uniq("forgot");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        Actor staff = new Actor(userId, insertEmployee(userId, "STORE_MANAGER"), username, username + "@tinqa.test",
                login(username, SEED_PASSWORD));
        JsonNode accepted = post("/auth/password/forgot", null, body("identifier", staff.username().toUpperCase()), 200);
        // Unknown accounts get the same answer and no email
        assertThat(post("/auth/password/forgot", null, body("identifier", "nobody-at-all"), 200).get("message").asText())
                .isEqualTo(accepted.get("message").asText());
        String token = lastPasswordLink(staff.email(), false);
        verify(staffMailer, times(1)).sendPasswordLink(any(), any(), any(), eq(false), eq(15L));

        JsonNode link = data(post("/auth/password/link/check", null, body("token", token), 200));
        assertThat(link.get("purpose").asText()).isEqualTo("RESET");
        assertThat(link.get("username").asText()).isEqualTo(staff.username());
        JsonNode weak = post("/auth/password/link", null, body("token", token, "newPassword", "short", "confirmNewPassword", "short"), 400);
        assertThat(weak.get("errors").get(0).get("field").asText()).isEqualTo("newPassword");

        String fresh = setPasswordWithLink(token, staff.username(), "Reset!Passw0rd#2026");
        assertThat(fresh).isNotBlank();
        // Signed in before the reset (usually in the same second): signed out; signed in right after: still in
        get("/v1/notifications", staff.token(), 401);
        get("/v1/notifications", fresh, 200);
        JsonNode reused = post("/auth/password/link", null,
                body("token", token, "newPassword", "Again!Passw0rd#2026", "confirmNewPassword", "Again!Passw0rd#2026"), 400);
        assertThat(reused.get("errorCode").asText()).isEqualTo("PASSWORD_LINK_INVALID");
    }

    @Test
    void whenEmailIsNotSetUpTheRequestSaysSo() throws Exception {
        doThrow(new StaffMailException("not set up", true, null)).when(staffMailer).sendVerificationCode(any(), any(), any(), anyLong());
        JsonNode refused = post("/v1/admin/employees/email-verification", l2a.token(), body("email", uniq("nomail") + "@example.test"), 503);
        assertThat(refused.get("errorCode").asText()).isEqualTo("EMAIL_NOT_CONFIGURED");
    }
}
