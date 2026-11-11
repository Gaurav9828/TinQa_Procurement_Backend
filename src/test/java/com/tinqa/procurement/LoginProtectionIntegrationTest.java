package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-in protection on POST /auth/admin/login: five wrong passwords within 15 minutes lock the typed username
 * (429, even with the right password), a successful sign-in resets the count, unknown usernames lock the same way
 * (so the lock reveals nothing), and every attempt is written to auth_audit_events.
 */
class LoginProtectionIntegrationTest extends IntegrationTestSupport {

    private static final String WRONG = "Wrong!Passw0rd#1";

    @Test
    void fiveWrongPasswordsLockTheUsernameAndEveryAttemptIsAudited() throws Exception {
        String username = uniq("lockme");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        for (int i = 0; i < 5; i++) {
            attempt(username, WRONG, 400);
        }

        JsonNode locked = attempt(username, SEED_PASSWORD, 429);
        assertThat(locked.get("errorCode").asText()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(locked.get("message").asText()).contains("Try again in 15 minutes");

        assertThat(events(username)).containsExactly(
                "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_audit_events WHERE user_id = ? AND event_type = 'LOGIN_FAILED'",
                Integer.class, userId)).isEqualTo(5);
    }

    @Test
    void aSuccessfulSignInResetsTheCount() throws Exception {
        String username = uniq("resetme");
        insertUser(username, "ADMIN_L1", "WEB", false, true);
        for (int i = 0; i < 4; i++) {
            attempt(username, WRONG, 400);
        }
        attempt(username, SEED_PASSWORD, 200);
        for (int i = 0; i < 4; i++) {
            attempt(username, WRONG, 400);
        }
        attempt(username, SEED_PASSWORD, 200);

        assertThat(events(username)).filteredOn("LOGIN_SUCCESS"::equals).hasSize(2);
    }

    @Test
    void unknownUsernamesLockTheSameWaySoTheLockRevealsNothing() throws Exception {
        String ghost = uniq("ghost");
        String realUser = uniq("real");
        insertUser(realUser, "ADMIN_L1", "WEB", false, true);
        for (int i = 0; i < 5; i++) {
            // The same answer as a wrong password on a real account; case does not split the count
            String message = attempt(i % 2 == 0 ? ghost : ghost.toUpperCase(), WRONG, 400).get("message").asText();
            assertThat(message).isEqualTo(attempt(realUser, WRONG, 400).get("message").asText());
        }
        assertThat(attempt(ghost, WRONG, 429).get("errorCode").asText()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(attempt(realUser, SEED_PASSWORD, 429).get("errorCode").asText()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_audit_events WHERE lower(username) = lower(?) AND user_id IS NULL",
                Integer.class, ghost)).isGreaterThanOrEqualTo(6);
    }

    private JsonNode attempt(String username, String password, int expectedStatus) throws Exception {
        return post("/auth/admin/login", null, Map.of("username", username, "password", password), expectedStatus);
    }

    private List<String> events(String username) {
        return jdbc.queryForList("SELECT event_type FROM auth_audit_events WHERE username = ? ORDER BY id", String.class, username);
    }
}
