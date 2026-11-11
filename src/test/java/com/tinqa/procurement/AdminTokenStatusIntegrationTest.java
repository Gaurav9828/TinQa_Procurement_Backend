package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /v1/internal/auth/token-status: the Ecommerce service asks whether an admin token still stands. It applies the
 * same rules as this service's own requests (signed out, password changed, account disabled) and returns the
 * account's current permissions, not the token's.
 */
class AdminTokenStatusIntegrationTest extends IntegrationTestSupport {

    private static final String PATH = "/v1/internal/auth/token-status";

    @Test
    void aFreshTokenIsActiveWithTheAccountsCurrentPermissions() throws Exception {
        JsonNode status = status(login(l2a.username(), SEED_PASSWORD));
        assertThat(status.get("active").asBoolean()).isTrue();
        assertThat(status.get("role").asText()).isEqualTo("ADMIN_L2");
        assertThat(status.get("permissions").size()).isPositive();
    }

    @Test
    void aSignedOutTokenIsInactive() throws Exception {
        String username = uniq("signout");
        insertUser(username, "ADMIN_L1", "WEB", false, true);
        String token = login(username, SEED_PASSWORD);
        post("/auth/logout", token, null, 200);

        assertThat(status(token).get("active").asBoolean()).isFalse();
    }

    @Test
    void aDisabledAccountsTokenIsInactive() throws Exception {
        String username = uniq("disabled");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        String token = login(username, SEED_PASSWORD);
        jdbc.update("UPDATE users SET enabled = FALSE WHERE id = ?", userId);

        assertThat(status(token).get("active").asBoolean()).isFalse();
    }

    @Test
    void anInvalidTokenIsInactive() throws Exception {
        assertThat(status("not-a-token").get("active").asBoolean()).isFalse();
    }

    @Test
    void onlyOtherTinQaServicesMayAsk() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("token", "anything"));
        perform(MockMvcRequestBuilders.post(PATH).contentType(MediaType.APPLICATION_JSON).content(body), 401);
        perform(MockMvcRequestBuilders.post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + l2a.token()), 401);
    }

    private JsonNode status(String token) throws Exception {
        return data(perform(MockMvcRequestBuilders.post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token)))
                .header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 200));
    }
}
