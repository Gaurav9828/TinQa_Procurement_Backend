package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.outreach.OutreachFollowUpReminder;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client outreach: businesses are unique by phone and email, every contact is logged by the person who made it (only
 * they change it or its proofs), the business keeps its latest contact's interest and follow-up, and the logger is
 * reminded of a due follow-up once.
 */
class OutreachIntegrationTest extends IntegrationTestSupport {

    private static final String OUTREACH = "/v1/outreach";
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    @Autowired
    private OutreachFollowUpReminder followUpReminder;

    private Actor staff(String designation) throws Exception {
        String username = uniq("or");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        long employeeId = insertEmployee(userId, designation);
        return new Actor(userId, employeeId, username, username + "@tinqa.test", login(username, SEED_PASSWORD));
    }

    private Map<String, Object> businessBody(String name, String phone, String email) {
        return body("name", name, "businessType", "HOTEL", "city", "Jaipur", "address", "MI Road",
                "googleMapsUrl", "https://maps.app.goo.gl/abc123", "phone", phone, "email", email, "contactPerson", "Asha Rao");
    }

    private static Map<String, Object> contactBody(OffsetDateTime at, String interest, String nextAction, OffsetDateTime followUp) {
        Map<String, Object> contact = body("contactedAt", at.toString(), "channel", "CALL", "contactPerson", "Front desk",
                "summary", "Pitched the smart room kit", "response", "Asked for a price list", "rating", "GOOD",
                "interest", interest, "nextAction", nextAction);
        if (followUp != null) {
            contact.put("followUpAt", followUp.toString());
        }
        return contact;
    }

    @Test
    void businessesAreLoggedOnceAndContactsBelongToTheirLogger() throws Exception {
        Actor manager = staff("STORE_MANAGER");
        Actor finance = staff("FINANCE_MANAGER");
        String mobile = nextLocalMobile();
        String name = "Radisson " + letters();
        JsonNode business = data(post(OUTREACH + "/businesses", manager.token(),
                businessBody(name, "+91 " + mobile, uniq("hotel") + "@Example.com"), 201));
        long businessId = business.get("id").asLong();
        assertThat(business.get("phone").asText()).isEqualTo(mobile);
        assertThat(business.get("contactCount").asInt()).isZero();

        // Same number in another spelling: 409 with the existing business
        JsonNode duplicate = post(OUTREACH + "/businesses", finance.token(), businessBody("Radisson Blu", "0" + mobile, null), 409);
        assertThat(duplicate.get("errorCode").asText()).isEqualTo("BUSINESS_EXISTS");
        assertThat(duplicate.get("data").get("existingId").asLong()).isEqualTo(businessId);
        String email = business.get("email").asText();
        assertThat(post(OUTREACH + "/businesses", finance.token(), businessBody("Other", null, email.toUpperCase()), 409)
                .get("data").get("existingId").asLong()).isEqualTo(businessId);

        // Rating and interest are required; a follow-up date is required unless nothing is next
        OffsetDateTime now = OffsetDateTime.now(IST).withNano(0);
        Map<String, Object> noRating = contactBody(now.minusHours(2), "INTERESTED", "NONE", null);
        noRating.remove("rating");
        post(OUTREACH + "/businesses/" + businessId + "/contacts", manager.token(), noRating, 400);
        post(OUTREACH + "/businesses/" + businessId + "/contacts", manager.token(), contactBody(now, "MAYBE", "CALL_BACK", null), 400);
        post(OUTREACH + "/businesses/" + businessId + "/contacts", manager.token(), contactBody(now.plusDays(1), "MAYBE", "NONE", null), 400);

        JsonNode first = data(post(OUTREACH + "/businesses/" + businessId + "/contacts", manager.token(),
                contactBody(now.minusDays(3), "MAYBE", "CALL_BACK", now.minusDays(2)), 201));
        assertThat(first.get("editable").asBoolean()).isTrue();
        long firstId = first.get("id").asLong();
        JsonNode second = data(post(OUTREACH + "/businesses/" + businessId + "/contacts", finance.token(),
                contactBody(now.minusHours(1), "INTERESTED", "DEMO_REQUESTED", now.plusDays(5)), 201));

        JsonNode summary = data(get(OUTREACH + "/businesses/" + businessId, manager.token(), 200));
        assertThat(summary.get("contactCount").asInt()).isEqualTo(2);
        assertThat(summary.get("demoRequestCount").asInt()).isEqualTo(1);
        assertThat(summary.get("latestInterest").asText()).isEqualTo("INTERESTED");
        assertThat(OffsetDateTime.parse(summary.get("nextFollowUpAt").asText()).toInstant())
                .isEqualTo(now.plusDays(5).toInstant());
        assertThat(summary.get("lastContactAt").asText()).endsWith("+05:30");

        // Newest first; only the logger edits
        JsonNode contacts = data(get(OUTREACH + "/businesses/" + businessId + "/contacts", manager.token(), 200));
        assertThat(contacts.get("totalElements").asInt()).isEqualTo(2);
        assertThat(contacts.get("content").get(0).get("id").asLong()).isEqualTo(second.get("id").asLong());
        assertThat(contacts.get("content").get(0).get("editable").asBoolean()).isFalse();
        assertThat(put(OUTREACH + "/contacts/" + second.get("id").asLong(), manager.token(),
                contactBody(now.minusHours(1), "LATER", "NONE", null), 403).get("errorCode").asText()).isEqualTo("OUTREACH_NOT_YOURS");
        JsonNode edited = data(put(OUTREACH + "/contacts/" + firstId, manager.token(),
                contactBody(now.minusDays(3), "LATER", "CALL_BACK", now.minusDays(2)), 200));
        assertThat(edited.get("interest").asText()).isEqualTo("LATER");

        // Search by name or phone; the log across businesses; mine
        assertThat(data(get(OUTREACH + "/businesses?search=" + name.substring(9).toLowerCase(), finance.token(), 200))
                .get("totalElements").asInt()).isEqualTo(1);
        assertThat(data(get(OUTREACH + "/businesses?search=" + mobile.substring(4), finance.token(), 200))
                .get("content").get(0).get("id").asLong()).isEqualTo(businessId);
        JsonNode mine = data(get(OUTREACH + "/contacts?mine=true", finance.token(), 200));
        assertThat(mine.get("totalElements").asInt()).isEqualTo(1);
        assertThat(mine.get("content").get(0).get("businessName").asText()).isEqualTo(name);

        // Proofs: images or PDFs only, the logger's own contacts only
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        JsonNode proof = data(perform(MockMvcRequestBuilders.multipart(OUTREACH + "/contacts/" + firstId + "/proofs")
                .file(new MockMultipartFile("file", "call_log.png", "image/png", png))
                .header("Authorization", "Bearer " + manager.token()), 201));
        perform(MockMvcRequestBuilders.multipart(OUTREACH + "/contacts/" + firstId + "/proofs")
                .file(new MockMultipartFile("file", "call_log.png", "image/png", png))
                .header("Authorization", "Bearer " + finance.token()), 403);
        perform(MockMvcRequestBuilders.multipart(OUTREACH + "/contacts/" + firstId + "/proofs")
                .file(new MockMultipartFile("file", "notes.csv", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + manager.token()), 400);
        long proofId = proof.get("id").asLong();
        assertThat(data(get(OUTREACH + "/businesses/" + businessId + "/contacts", finance.token(), 200))
                .get("content").get(1).get("proofs").get(0).get("fileName").asText()).isEqualTo("call_log.png");
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/outreach/contacts/" + firstId + "/proofs/" + proofId)
                .header("Authorization", "Bearer " + finance.token())).andReturn();
        delete(OUTREACH + "/contacts/" + firstId + "/proofs/" + proofId, finance.token(), 403);
        delete(OUTREACH + "/contacts/" + firstId + "/proofs/" + proofId, manager.token(), 200);
        delete(OUTREACH + "/contacts/" + firstId + "/proofs/" + proofId, manager.token(), 404);

        // Without outreach access: nothing
        Actor rider = staff("DELIVERY_RIDER");
        get(OUTREACH + "/businesses", rider.token(), 403);
        post(OUTREACH + "/businesses", rider.token(), businessBody("X", null, null), 403);
    }

    @Test
    void followUpsAreListedAndRemindedOnce() throws Exception {
        Actor caller = staff("STORE_MANAGER");
        OffsetDateTime now = OffsetDateTime.now(IST).withNano(0);
        long businessId = data(post(OUTREACH + "/businesses", caller.token(),
                businessBody("Hotel " + letters(), nextLocalMobile(), null), 201)).get("id").asLong();
        long overdueId = data(post(OUTREACH + "/businesses/" + businessId + "/contacts", caller.token(),
                contactBody(now.minusDays(3), "MAYBE", "CALL_BACK", now.minusDays(1)), 201)).get("id").asLong();

        JsonNode due = data(get(OUTREACH + "/follow-ups?mine=true", caller.token(), 200));
        assertThat(due.get("totalElements").asInt()).isEqualTo(1);
        assertThat(due.get("content").get(0).get("id").asLong()).isEqualTo(overdueId);
        assertThat(data(get(OUTREACH + "/businesses?followUpDue=true&size=100", caller.token(), 200)).get("content")
                .findValuesAsText("id")).contains(String.valueOf(businessId));

        followUpReminder.remindDue();
        assertThat(notificationTitles(caller)).containsOnlyOnce("Follow-up due today");
        followUpReminder.remindDue();
        assertThat(notificationTitles(caller)).containsOnlyOnce("Follow-up due today");

        // A later contact supersedes the follow-up
        post(OUTREACH + "/businesses/" + businessId + "/contacts", caller.token(),
                contactBody(now.minusMinutes(5), "INTERESTED", "MEETING_REQUESTED", now.plusDays(3)), 201);
        assertThat(data(get(OUTREACH + "/follow-ups?mine=true", caller.token(), 200)).get("totalElements").asInt()).isZero();

        // The dashboard counts it all in one go
        JsonNode summary = data(get(OUTREACH + "/summary?from=" + now.toLocalDate().minusDays(5) + "&to=" + now.toLocalDate(),
                caller.token(), 200));
        assertThat(summary.get("totalContacts").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(summary.get("byChannel").get("CALL").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(summary.get("byExecutive").findValuesAsText("userId")).contains(String.valueOf(caller.userId()));
        assertThat(summary.get("byDay").size()).isPositive();
        get(OUTREACH + "/summary?from=2026-02-01&to=2026-01-01", caller.token(), 400);
    }
}
