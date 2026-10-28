package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client demos: the quotation is approved by someone else, bills are recorded while the visit runs (attending staff
 * record their own), the completion is approved and booked, and a completed demo is corrected only by a Super Admin
 * and approved again, booking just the difference.
 */
class ClientDemoIntegrationTest extends IntegrationTestSupport {

    private static final String DEMOS = "/v1/admin/client-demos";
    private static final String MINE = "/v1/client-demos/mine";

    /** An attending employee without demo powers (a delivery rider). */
    private record Attendee(long userId, long employeeId, String token) {
    }

    private Attendee attendee() throws Exception {
        String username = uniq("rider");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        long employeeId = insertEmployee(userId, "DELIVERY_RIDER");
        return new Attendee(userId, employeeId, login(username, SEED_PASSWORD));
    }

    private static Map<String, Object> plan(long staffEmployeeId, int days, int budget) {
        return body("demoType", "APPOINTMENT", "clientCategory", "HOTEL", "clientName", "Hotel Sunrise",
                "contactPerson", "Asha Rao", "contactPhone", "+919876543210", "addressLine", "12 MG Road", "city", "Jaipur",
                "mapLink", "https://maps.app.goo.gl/abc123",
                "startDate", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString(), "startTime", "10:30",
                "numberOfDays", days, "proposedBudget", budget, "staffEmployeeIds", List.of(staffEmployeeId));
    }

    private long create(Map<String, Object> plan) throws Exception {
        JsonNode demo = data(post(DEMOS, l2a.token(), plan, 201));
        assertThat(demo.get("status").asText()).isEqualTo("DRAFT");
        assertThat(demo.get("reference").asText()).startsWith("DEMO-");
        return demo.get("id").asLong();
    }

    private JsonNode decide(Actor actor, long id, String decision, int expectedStatus) throws Exception {
        return post(DEMOS + "/" + id + "/decision", actor.token(), body("decision", decision,
                "reason", "REJECTED".equals(decision) ? "Too costly" : null), expectedStatus);
    }

    private static long billId(JsonNode demo, String kind) {
        for (JsonNode expense : demo.get("expenses")) {
            if (expense.get("kind").asText().equals(kind)) return expense.get("id").asLong();
        }
        throw new AssertionError("No " + kind + " bill");
    }

    private JsonNode feed() throws Exception {
        return data(perform(MockMvcRequestBuilders.get("/v1/internal/finance/demo-events").param("limit", "500")
                .header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 200)).get("events");
    }

    @Test
    void aDemoIsApprovedRunCompletedBookedAndCorrectedOnlyThroughASuperAdmin() throws Exception {
        Attendee rider = attendee();
        long id = create(plan(rider.employeeId(), 2, 20000));

        // Drafts aren't shown to attending staff
        assertThat(data(get(MINE, rider.token(), 200))).isEmpty();
        get(MINE + "/" + id, rider.token(), 404);

        // Quotation: maker-checker, approval level only
        assertThat(data(post(DEMOS + "/" + id + "/submit", l2a.token(), null, 200)).get("status").asText()).isEqualTo("QUOTATION_PENDING");
        assertThat(decide(l2a, id, "APPROVED", 403).get("errorCode").asText()).isEqualTo("DEMO_SELF_DECISION");
        decide(l1, id, "APPROVED", 403);
        assertThat(data(decide(l2b, id, "APPROVED", 200)).get("status").asText()).isEqualTo("APPROVED");

        // The rider records their own travel and food, with the ticket attached
        assertThat(data(get(MINE, rider.token(), 200))).hasSize(1);
        JsonNode withTravel = data(post(MINE + "/" + id + "/expenses", rider.token(), body("kind", "TRAVEL", "dayNumber", 1,
                "fromPlace", "Delhi", "toPlace", "Jaipur", "travelMode", "TRAIN", "referenceNumber", "PNR 4521", "amount", 1200), 201));
        long travelId = billId(withTravel, "TRAVEL");
        post(MINE + "/" + id + "/expenses", rider.token(), body("kind", "FOOD", "dayNumber", 1, "mealType", "LUNCH",
                "vendorName", "Spice Court", "amount", 450.5), 201);
        // A day beyond the visit and a food bill without its meal are refused
        post(MINE + "/" + id + "/expenses", rider.token(), body("kind", "FOOD", "dayNumber", 3, "mealType", "DINNER",
                "vendorName", "Spice Court", "amount", 300), 400);
        post(MINE + "/" + id + "/expenses", rider.token(), body("kind", "FOOD", "dayNumber", 1, "vendorName", "Spice Court",
                "amount", 300), 400);
        JsonNode attached = perform(MockMvcRequestBuilders.multipart(MINE + "/" + id + "/expenses/" + travelId + "/invoice")
                .file(new MockMultipartFile("file", "ticket.pdf", "application/pdf", "%PDF-1.4\n%test\n".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + rider.token()), 200);
        assertThat(data(attached).get("expenses").findValuesAsText("invoiceFileName")).contains("ticket.pdf");
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get(DEMOS + "/" + id + "/expenses/" + travelId + "/invoice")
                .header("Authorization", "Bearer " + l1.token())).andReturn().getResponse().getStatus()).isEqualTo(200);

        // The manager books the hotel; the rider can't touch it, and changes after approval need a reason
        JsonNode withStay = data(post(DEMOS + "/" + id + "/expenses", l2a.token(), body("kind", "STAY", "dayNumber", 1,
                "vendorName", "Hotel Pearl", "vendorGstin", "08AAAAA0000A1Z5", "referenceNumber", "INV-77", "amount", 3500,
                "gstAmount", 420), 201));
        long stayId = billId(withStay, "STAY");
        put(MINE + "/" + id + "/expenses/" + stayId, rider.token(), body("kind", "STAY", "dayNumber", 1, "vendorName", "Hotel Pearl",
                "amount", 1, "changeReason", "Mine now"), 403);
        Map<String, Object> cheaperTrain = body("kind", "TRAVEL", "dayNumber", 1, "fromPlace", "Delhi", "toPlace", "Jaipur",
                "travelMode", "TRAIN", "amount", 1000);
        put(DEMOS + "/" + id + "/expenses/" + travelId, l2a.token(), cheaperTrain, 400);
        cheaperTrain.put("changeReason", "Refund of the tatkal charge");
        put(DEMOS + "/" + id + "/expenses/" + travelId, l2a.token(), cheaperTrain, 200);
        // Another cost always says why it was needed
        post(DEMOS + "/" + id + "/expenses", l2a.token(), body("kind", "OTHER", "description", "Parking", "amount", 100), 400);
        JsonNode running = data(post(DEMOS + "/" + id + "/expenses", l2a.token(), body("kind", "OTHER", "description", "Parking",
                "reason", "No parking at the hotel", "amount", 100), 201));
        assertThat(running.get("total").decimalValue()).isEqualByComparingTo("5050.50");
        assertThat(running.get("budgetLeft").decimalValue()).isEqualByComparingTo("14949.50");

        // Completion: locked while it waits, then booked once approved
        post(DEMOS + "/" + id + "/complete", l2a.token(), body("outcome", "Demo done; 4 rooms want a trial"), 200);
        post(MINE + "/" + id + "/expenses", rider.token(), body("kind", "OTHER", "description", "Tea", "reason", "Client meeting",
                "amount", 50), 409);
        assertThat(feed().findValuesAsText("demoReference")).doesNotContain(String.format("DEMO-%05d", id));
        JsonNode completed = data(decide(l2b, id, "APPROVED", 200));
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("bookedTotal").decimalValue()).isEqualByComparingTo("5050.50");
        put(DEMOS + "/" + id, l2a.token(), plan(rider.employeeId(), 2, 20000), 409);

        // Only a Super Admin (Level 2) reopens it; the corrected completion books just the difference
        post(DEMOS + "/" + id + "/reopen", l1.token(), body("reason", "Wrong hotel bill"), 403);
        post(DEMOS + "/" + id + "/reopen", l2a.token(), body("reason", "Hotel refunded part of the bill"), 200);
        Map<String, Object> refunded = body("kind", "STAY", "dayNumber", 1, "vendorName", "Hotel Pearl", "amount", 3000,
                "changeReason", "Refund of 500 received");
        put(DEMOS + "/" + id + "/expenses/" + stayId, l2a.token(), refunded, 200);
        post(DEMOS + "/" + id + "/complete", l2a.token(), body("outcome", "Demo done; 4 rooms want a trial"), 200);
        JsonNode corrected = data(decide(l2b, id, "APPROVED", 200));
        assertThat(corrected.get("bookedTotal").decimalValue()).isEqualByComparingTo("4550.50");

        List<JsonNode> postings = new java.util.ArrayList<>();
        feed().forEach(event -> {
            if (event.get("demoReference").asText().equals(String.format("DEMO-%05d", id))) postings.add(event);
        });
        assertThat(postings).extracting(event -> event.get("kind").asText()).containsExactly("COMPLETED", "CORRECTION");
        assertThat(postings.get(0).get("amount").decimalValue()).isEqualByComparingTo("5050.50");
        assertThat(postings.get(1).get("amount").decimalValue()).isEqualByComparingTo("-500");
        assertThat(postings.get(1).get("postingKey").asText()).startsWith("PROCUREMENT_DEMO:");
        assertThat(corrected.get("events").findValuesAsText("type")).contains("REOPENED", "EXPENSE_UPDATED", "COMPLETION_APPROVED");
    }

    @Test
    void anApprovedPlanChangesOnlyWithAReasonAndANewBudgetIsApprovedAgain() throws Exception {
        Attendee rider = attendee();
        long id = create(plan(rider.employeeId(), 1, 5000));
        // A rider has no demo-management powers
        post(DEMOS, rider.token(), plan(rider.employeeId(), 1, 5000), 403);
        post(DEMOS + "/" + id + "/submit", l2a.token(), null, 200);
        decide(l2b, id, "APPROVED", 200);

        Map<String, Object> later = plan(rider.employeeId(), 1, 5000);
        later.put("startTime", "15:00");
        put(DEMOS + "/" + id, l2a.token(), later, 400);
        later.put("changeReason", "Client asked for the afternoon");
        assertThat(data(put(DEMOS + "/" + id, l2a.token(), later, 200)).get("status").asText()).isEqualTo("APPROVED");

        Map<String, Object> bigger = plan(rider.employeeId(), 1, 8000);
        bigger.put("startTime", "15:00");
        bigger.put("changeReason", "Two more staff need rooms");
        assertThat(data(put(DEMOS + "/" + id, l2a.token(), bigger, 200)).get("status").asText()).isEqualTo("QUOTATION_PENDING");
        JsonNode rejected = data(decide(l2b, id, "REJECTED", 200));
        assertThat(rejected.get("status").asText()).isEqualTo("DRAFT");
        assertThat(rejected.get("rejectionReason").asText()).isEqualTo("Too costly");

        // A demo sent for approval before is cancelled with a reason, never deleted
        delete(DEMOS + "/" + id, l2a.token(), 409);
        post(DEMOS + "/" + id + "/cancel", l2a.token(), body("reason", ""), 400);
        assertThat(data(post(DEMOS + "/" + id + "/cancel", l2a.token(), body("reason", "Client postponed"), 200))
                .get("status").asText()).isEqualTo("CANCELLED");

        long draft = create(plan(rider.employeeId(), 1, 1000));
        delete(DEMOS + "/" + draft, l2a.token(), 200);
        get(DEMOS + "/" + draft, l2a.token(), 404);
    }
}
