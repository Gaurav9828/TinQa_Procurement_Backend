package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Customer change requests")
class CustomerChangeRequestIntegrationTest extends IntegrationTestSupport {

    @Test
    void customerCareAsksAndTheSuperAdminIsNotifiedWithALinkToTheCustomer() throws Exception {
        String username = uniq("care");
        long user = insertUser(username, "ADMIN_L1", "WEB", false, true);
        insertEmployee(user, "CUSTOMER_SUPPORT");
        String care = login(username, SEED_PASSWORD);

        post("/v1/admin/customer-change-requests", care, Map.of(
                "customerId", 42,
                "customerName", "Asha Verma",
                "changes", Map.of("PHONE_NUMBER", "+91 9876543210", "FIRST_NAME", "Ashaa"),
                "reason", "Customer called from their new number"), 200);

        JsonNode notification = null;
        for (JsonNode candidate : data(get("/v1/notifications", l2a.token(), 200))) {
            if (candidate.path("message").asText().contains(username)) {
                notification = candidate;
            }
        }
        assertThat(notification).isNotNull();
        assertThat(notification.path("title").asText()).isEqualTo("Customer detail change requested");
        assertThat(notification.path("message").asText()).contains("Asha Verma (#42)", "phone number -> +91 9876543210",
                "first name -> Ashaa", "Reason: Customer called from their new number");
        assertThat(notification.path("link").asText()).isEqualTo("/customers/42");

        // The requester is not notified of their own request, and nothing is asked without a field or a reason
        assertThat(notificationTitles(new Actor(user, null, username, null, care))).doesNotContain("Customer detail change requested");
        post("/v1/admin/customer-change-requests", care, Map.of("customerId", 42, "customerName", "Asha",
                "changes", Map.of(), "reason", "Something"), 400);
        post("/v1/admin/customer-change-requests", care, Map.of("customerId", 42, "customerName", "Asha",
                "changes", Map.of("EMAIL", "x@y.z"), "reason", "Something"), 400);
    }

    @Test
    void staffWithoutCustomerCareCannotAsk() throws Exception {
        String username = uniq("rider");
        long user = insertUser(username, "ADMIN_L1", "WEB", false, true);
        insertEmployee(user, "DELIVERY_RIDER");
        post("/v1/admin/customer-change-requests", login(username, SEED_PASSWORD), Map.of("customerId", 42,
                "customerName", "Asha", "changes", Map.of("FIRST_NAME", "Ashaa"), "reason", "Something"), 403);
    }
}
