package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.clientdemo.DemoStockClient;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Client demos: the quotation is approved by someone else, bills are recorded while the visit runs (attending staff
 * record their own), the completion is approved and booked, and a completed demo is corrected only by a Super Admin
 * and approved again, booking just the difference.
 */
class ClientDemoIntegrationTest extends IntegrationTestSupport {

    private static final String DEMOS = "/v1/admin/client-demos";
    private static final String MINE = "/v1/client-demos/mine";

    /** An attending employee without demo-management powers (a store sales agent, who goes on demos). */
    private record Attendee(long userId, long employeeId, String token) {
    }

    private Attendee attendee() throws Exception {
        return employee("SALES_AGENT");
    }

    private Attendee employee(String designation) throws Exception {
        String username = uniq(designation.toLowerCase());
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        long employeeId = insertEmployee(userId, designation);
        return new Attendee(userId, employeeId, login(username, SEED_PASSWORD));
    }

    // Each client has its own phone (by name); the contact person may be the same at every client
    private static Map<String, Object> client(String name, String category, int day, String from, String to) {
        return body("clientCategory", category, "clientName", name, "clientPhone", String.format("+9198%08d", Math.abs(name.hashCode()) % 100_000_000),
                "contactPerson", "Asha Rao", "contactPhone", "+919876543210",
                "addressLine", "12 MG Road", "city", "Jaipur", "mapLink", "https://maps.app.goo.gl/abc123",
                "dayNumber", day, "startTime", from, "endTime", to);
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    // Without a day-by-day plan the whole budget is extra, so it says why
    private static Map<String, Object> plan(long staffEmployeeId, int days, int budget) {
        return body("demoType", "APPOINTMENT", "clients", List.of(client("Hotel Sunrise", "HOTEL", 1, "10:30", "12:30")),
                "startDate", TODAY.toString(), "numberOfDays", days, "proposedBudget", budget,
                "extraBudgetReason", "Rooms and meals are booked on arrival", "staffEmployeeIds", List.of(staffEmployeeId));
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

    private static String unitStatus(JsonNode demo, String barcode) {
        for (JsonNode unit : demo.get("units")) {
            if (unit.get("barcode").asText().equals(barcode)) return unit.get("status").asText();
        }
        throw new AssertionError("No unit " + barcode);
    }

    private JsonNode feed() throws Exception {
        return data(perform(MockMvcRequestBuilders.get("/v1/internal/finance/demo-events").param("limit", "500")
                .header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 200)).get("events");
    }

    // Demos are field work for the roles that go on them: a delivery rider is never offered, can't be tagged and has no
    // "My demos"
    @Test
    void onlyStaffWhoseRoleGoesOnDemosCanBeTagged() throws Exception {
        Attendee agent = attendee();
        Attendee rider = employee("DELIVERY_RIDER");

        List<Long> offered = new ArrayList<>();
        data(get(DEMOS + "/staff", l2a.token(), 200)).forEach(member -> offered.add(member.get("employeeId").asLong()));
        assertThat(offered).contains(agent.employeeId()).doesNotContain(rider.employeeId());

        JsonNode refused = post(DEMOS, l2a.token(), plan(rider.employeeId(), 1, 5000), 400);
        assertThat(refused.get("errors").get(0).get("field").asText()).isEqualTo("staffEmployeeIds");
        assertThat(refused.get("message").asText()).endsWith("'s role does not go on client demos, so they can't be tagged as attending staff.");

        get(MINE, rider.token(), 403);
        assertThat(data(get(MINE, agent.token(), 200))).isEmpty();
    }

    @Test
    void aDemoIsApprovedRunCompletedBookedAndCorrectedOnlyThroughASuperAdmin() throws Exception {
        Attendee agent = attendee();
        long id = create(plan(agent.employeeId(), 2, 20000));

        // Drafts aren't shown to attending staff
        assertThat(data(get(MINE, agent.token(), 200))).isEmpty();
        get(MINE + "/" + id, agent.token(), 404);

        // Quotation: maker-checker, approval level only
        assertThat(data(post(DEMOS + "/" + id + "/submit", l2a.token(), null, 200)).get("status").asText()).isEqualTo("QUOTATION_PENDING");
        assertThat(decide(l2a, id, "APPROVED", 403).get("errorCode").asText()).isEqualTo("DEMO_SELF_DECISION");
        decide(l1, id, "APPROVED", 403);
        assertThat(data(decide(l2b, id, "APPROVED", 200)).get("status").asText()).isEqualTo("APPROVED");

        // The agent records their own travel and food, with the ticket attached
        assertThat(data(get(MINE, agent.token(), 200))).hasSize(1);
        JsonNode withTravel = data(post(MINE + "/" + id + "/expenses", agent.token(), body("kind", "TRAVEL", "dayNumber", 1,
                "fromPlace", "Delhi", "toPlace", "Jaipur", "travelMode", "TRAIN", "referenceNumber", "PNR 4521", "amount", 1200), 201));
        long travelId = billId(withTravel, "TRAVEL");
        post(MINE + "/" + id + "/expenses", agent.token(), body("kind", "FOOD", "dayNumber", 1, "mealType", "LUNCH",
                "vendorName", "Spice Court", "amount", 450.5), 201);
        // A day beyond the visit and a food bill without its meal are refused
        post(MINE + "/" + id + "/expenses", agent.token(), body("kind", "FOOD", "dayNumber", 3, "mealType", "DINNER",
                "vendorName", "Spice Court", "amount", 300), 400);
        post(MINE + "/" + id + "/expenses", agent.token(), body("kind", "FOOD", "dayNumber", 1, "vendorName", "Spice Court",
                "amount", 300), 400);
        JsonNode attached = perform(MockMvcRequestBuilders.multipart(MINE + "/" + id + "/expenses/" + travelId + "/invoice")
                .file(new MockMultipartFile("file", "ticket.pdf", "application/pdf", "%PDF-1.4\n%test\n".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + agent.token()), 200);
        assertThat(data(attached).get("expenses").findValuesAsText("invoiceFileName")).contains("ticket.pdf");
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get(DEMOS + "/" + id + "/expenses/" + travelId + "/invoice")
                .header("Authorization", "Bearer " + l1.token())).andReturn().getResponse().getStatus()).isEqualTo(200);

        // The manager books the hotel; the agent can't touch it, and changes after approval need a reason
        JsonNode withStay = data(post(DEMOS + "/" + id + "/expenses", l2a.token(), body("kind", "STAY", "dayNumber", 1,
                "vendorName", "Hotel Pearl", "vendorGstin", "08AAAAA0000A1Z5", "referenceNumber", "INV-77", "amount", 3500,
                "gstAmount", 420), 201));
        long stayId = billId(withStay, "STAY");
        put(MINE + "/" + id + "/expenses/" + stayId, agent.token(), body("kind", "STAY", "dayNumber", 1, "vendorName", "Hotel Pearl",
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
        post(MINE + "/" + id + "/expenses", agent.token(), body("kind", "OTHER", "description", "Tea", "reason", "Client meeting",
                "amount", 50), 409);
        assertThat(feed().findValuesAsText("demoReference")).doesNotContain(String.format("DEMO-%05d", id));
        JsonNode completed = data(decide(l2b, id, "APPROVED", 200));
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("bookedTotal").decimalValue()).isEqualByComparingTo("5050.50");
        put(DEMOS + "/" + id, l2a.token(), plan(agent.employeeId(), 2, 20000), 409);

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
        Attendee agent = attendee();
        long id = create(plan(agent.employeeId(), 1, 5000));
        // An agent has no demo-management powers
        post(DEMOS, agent.token(), plan(agent.employeeId(), 1, 5000), 403);
        post(DEMOS + "/" + id + "/submit", l2a.token(), null, 200);
        decide(l2b, id, "APPROVED", 200);

        Map<String, Object> later = plan(agent.employeeId(), 1, 5000);
        later.put("clients", List.of(client("Hotel Sunrise", "HOTEL", 1, "15:00", "16:00")));
        put(DEMOS + "/" + id, l2a.token(), later, 400);
        later.put("changeReason", "Client asked for the afternoon");
        assertThat(data(put(DEMOS + "/" + id, l2a.token(), later, 200)).get("status").asText()).isEqualTo("APPROVED");

        Map<String, Object> bigger = plan(agent.employeeId(), 1, 8000);
        bigger.put("clients", List.of(client("Hotel Sunrise", "HOTEL", 1, "15:00", "16:00")));
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

        long draft = create(plan(agent.employeeId(), 1, 1000));
        delete(DEMOS + "/" + draft, l2a.token(), 200);
        get(DEMOS + "/" + draft, l2a.token(), 404);
    }

    @Test
    void aTripVisitsSeveralClientsInSlotsThatNeverClashAndItsDayPlanIsKeptInOneSave() throws Exception {
        Attendee agent = attendee();
        Map<String, Object> trip = plan(agent.employeeId(), 2, 30000);
        Map<String, Object> pearl = client("Hotel Pearl", "HOTEL", 1, "14:00", "15:00");
        pearl.put("legalName", "Pearl Hospitality Pvt Ltd");
        pearl.put("gstin", "08AAAAA0000A1Z5");
        Map<String, Object> office = client("Infotech Park", "PUBLIC_PLACE", 2, "11:00", "12:00");
        // Only business clients keep legal details
        office.put("gstin", "08AAAAA0000A1Z5");

        // Two visits on day 1 that overlap are refused
        trip.put("clients", List.of(client("Hotel Sunrise", "HOTEL", 1, "10:00", "14:30"), pearl, office));
        assertThat(post(DEMOS, l2a.token(), trip, 400).toString()).contains("overlap on day 1");
        // A visit after the last day is refused
        trip.put("clients", List.of(client("Hotel Sunrise", "HOTEL", 3, "10:00", "11:00")));
        post(DEMOS, l2a.token(), trip, 400);

        trip.put("clients", List.of(office, pearl, client("Hotel Sunrise", "HOTEL", 1, "10:00", "13:00")));
        trip.put("products", List.of(body("productId", 41, "productName", "Smart Lock X1", "quantity", 2,
                        "barcodes", insertProductUnits(41, "Smart Lock X1", 2)),
                body("productId", 42, "productName", "Door Sensor", "quantity", 5, "barcodes", insertProductUnits(42, "Door Sensor", 5))));
        trip.put("plannedBills", List.of(
                body("kind", "STAY", "dayNumber", 1, "vendorName", "Hotel Pearl", "vendorGstin", "08AAAAA0000A1Z5",
                        "startDate", TODAY.toString(), "startTime", "21:00", "endDate", TODAY.plusDays(1).toString(), "endTime", "06:00",
                        "amount", 3500),
                body("kind", "FOOD", "dayNumber", 1, "mealType", "DINNER", "vendorName", "Hotel Pearl", "amount", 800)));
        JsonNode created = data(post(DEMOS, l2a.token(), trip, 201));
        long id = created.get("id").asLong();

        // The demo shows the first visit (by day and time) as its client; every visit is listed in time order
        assertThat(created.get("clientName").asText()).isEqualTo("Hotel Sunrise");
        assertThat(created.get("startTime").asText()).startsWith("10:00");
        assertThat(created.get("clients").findValuesAsText("clientName")).containsExactly("Hotel Sunrise", "Hotel Pearl", "Infotech Park");
        assertThat(created.get("clients").get(1).get("gstin").asText()).isEqualTo("08AAAAA0000A1Z5");
        assertThat(created.get("clients").get(2).get("gstin").isNull()).isTrue();
        assertThat(created.get("products").findValuesAsText("productName")).containsExactly("Door Sensor", "Smart Lock X1");
        assertThat(created.get("products").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(created.get("units")).hasSize(7);
        assertThat(created.get("units").findValuesAsText("status")).containsOnly("RESERVED");
        assertThat(created.get("total").decimalValue()).isEqualByComparingTo("4300");
        JsonNode stay = created.get("expenses").get(0);
        assertThat(stay.get("startTime").asText()).startsWith("21:00");
        assertThat(stay.get("endDate").asText()).isEqualTo(TODAY.plusDays(1).toString());
        assertThat(stay.get("endTime").asText()).startsWith("06:00");
        assertThat(created.get("extraBudget").decimalValue()).isEqualByComparingTo("25700");

        // Products are listed once each
        trip.put("products", List.of(body("productId", 41, "productName", "Smart Lock X1", "quantity", 2),
                body("productId", 41, "productName", "Smart Lock X1", "quantity", 1)));
        post(DEMOS, l2a.token(), trip, 400);

        // Saving the plan again: the dinner changes, the stay is dropped, a breakfast is added; other bills stay
        post(DEMOS + "/" + id + "/expenses", l2a.token(), body("kind", "OTHER", "description", "Parking",
                "reason", "No parking at the hotel", "amount", 1200), 201);
        long dinnerId = billId(data(get(DEMOS + "/" + id, l2a.token(), 200)), "FOOD");
        Map<String, Object> changed = new java.util.LinkedHashMap<>(trip);
        changed.put("products", List.of());
        changed.put("plannedBills", List.of(
                body("id", dinnerId, "kind", "FOOD", "dayNumber", 1, "mealType", "DINNER", "vendorName", "Hotel Pearl", "amount", 950),
                body("kind", "FOOD", "dayNumber", 2, "mealType", "BREAKFAST", "vendorName", "Cafe Lane", "amount", 250)));
        JsonNode saved = data(put(DEMOS + "/" + id, l2a.token(), changed, 200));
        assertThat(saved.get("expenses").findValuesAsText("kind")).containsExactlyInAnyOrder("FOOD", "OTHER", "FOOD");
        assertThat(saved.get("total").decimalValue()).isEqualByComparingTo("2400");
        assertThat(saved.get("products")).isEmpty();
        assertThat(saved.get("events").findValuesAsText("type")).contains("EXPENSE_ADDED", "EXPENSE_UPDATED", "EXPENSE_REMOVED");

        // The plan holds stays, meals and travel only
        changed.put("plannedBills", List.of(body("kind", "OTHER", "description", "Parking", "reason", "None at the hotel", "amount", 10)));
        put(DEMOS + "/" + id, l2a.token(), changed, 400);
    }

    @Test
    void theSameClientIsNeverVisitedTwiceInARowOnOneDay() throws Exception {
        Attendee agent = attendee();
        Map<String, Object> trip = plan(agent.employeeId(), 2, 5000);
        Map<String, Object> morning = client("Hotel Sunrise", "HOTEL", 1, "10:00", "12:00");
        Map<String, Object> afternoon = client("Hotel Sunrise", "HOTEL", 1, "13:00", "15:00");
        // Matched by the client's phone, however it is written, even under another name
        afternoon.put("clientName", "Sunrise Residency");
        afternoon.put("clientPhone", "0" + ((String) morning.get("clientPhone")).substring(3));
        trip.put("clients", List.of(morning, afternoon));
        assertThat(post(DEMOS, l2a.token(), trip, 400).toString()).contains("visited twice in a row on day 1", "from 10:00 to 15:00");

        // ... or by its email
        Map<String, Object> byEmail = client("Sunrise Annexe", "HOTEL", 1, "13:00", "15:00");
        morning.put("clientEmail", "desk@sunrise.example");
        byEmail.put("clientEmail", "DESK@sunrise.example");
        trip.put("clients", List.of(morning, byEmail));
        post(DEMOS, l2a.token(), trip, 400);

        // Another client in between, or another day, is fine; the contact person may be the same everywhere
        trip.put("clients", List.of(morning, client("City Care Hospital", "HOSPITAL", 1, "12:15", "12:45"), afternoon,
                client("Hotel Sunrise", "HOTEL", 2, "10:00", "11:00")));
        JsonNode created = data(post(DEMOS, l2a.token(), trip, 201));
        assertThat(created.get("clients")).hasSize(4);
        assertThat(created.get("clients").get(0).get("clientEmail").asText()).isEqualTo("desk@sunrise.example");
    }

    @Test
    void theBudgetIsTheDayByDayPlanPlusAJustifiedExtraAndStaysAndJourneysAreDated() throws Exception {
        Attendee agent = attendee();
        Map<String, Object> trip = plan(agent.employeeId(), 2, 6000);
        trip.remove("extraBudgetReason");
        String day1 = TODAY.toString();
        String day2 = TODAY.plusDays(1).toString();
        // An overnight journey and a night's stay each end on the next day
        Map<String, Object> train = body("kind", "TRAVEL", "dayNumber", 1, "fromPlace", "Delhi", "toPlace", "Manali", "travelMode", "BUS",
                "startDate", day1, "startTime", "18:00", "endDate", day2, "endTime", "06:00", "amount", 1500);
        Map<String, Object> cab = body("kind", "TRAVEL", "dayNumber", 1, "fromPlace", "Manali", "toPlace", "Mall Road", "travelMode", "CAB",
                "startDate", day2, "startTime", "07:00", "endDate", day2, "endTime", "07:30", "amount", 500);
        Map<String, Object> stay = body("kind", "STAY", "dayNumber", 1, "vendorName", "Hotel Pearl",
                "startDate", day1, "startTime", "18:00", "endDate", day2, "endTime", "06:00", "amount", 4000);
        trip.put("plannedBills", List.of(train, cab, stay));

        // The plan comes to 6000, so there is no extra and no reason is needed
        JsonNode created = data(post(DEMOS, l2a.token(), trip, 201));
        long id = created.get("id").asLong();
        assertThat(created.get("extraBudget").decimalValue()).isEqualByComparingTo("0");
        assertThat(created.get("extraBudgetReason").isNull()).isTrue();
        assertThat(created.get("expenses").findValuesAsText("endDate")).containsOnly(day2);

        // A budget below the plan is refused; one above it needs its reason
        trip.put("proposedBudget", 5000);
        assertThat(post(DEMOS, l2a.token(), trip, 400).toString()).contains("can't be less than the day-by-day plan");
        trip.put("proposedBudget", 8000);
        assertThat(post(DEMOS, l2a.token(), trip, 400).toString()).contains("more than the day-by-day plan");
        trip.put("extraBudgetReason", "Snow chains 1200, local guide 800");
        JsonNode extra = data(post(DEMOS, l2a.token(), trip, 201));
        assertThat(extra.get("extraBudget").decimalValue()).isEqualByComparingTo("2000");
        assertThat(extra.get("extraBudgetReason").asText()).isEqualTo("Snow chains 1200, local guide 800");

        // Arriving before leaving, a half-given time, or a start two days after its day is refused
        trip.put("plannedBills", List.of(body("kind", "TRAVEL", "dayNumber", 1, "fromPlace", "A", "toPlace", "B", "travelMode", "CAB",
                "startDate", day2, "startTime", "08:00", "endDate", day2, "endTime", "07:00", "amount", 10)));
        post(DEMOS, l2a.token(), trip, 400);
        trip.put("plannedBills", List.of(body("kind", "TRAVEL", "dayNumber", 1, "fromPlace", "A", "toPlace", "B", "travelMode", "CAB",
                "startTime", "08:00", "endTime", "09:00", "amount", 10)));
        post(DEMOS, l2a.token(), trip, 400);
        trip.put("plannedBills", List.of(body("kind", "TRAVEL", "dayNumber", 1, "fromPlace", "A", "toPlace", "B", "travelMode", "CAB",
                "startDate", TODAY.plusDays(2).toString(), "startTime", "08:00", "endDate", TODAY.plusDays(2).toString(), "endTime", "09:00",
                "amount", 10)));
        post(DEMOS, l2a.token(), trip, 400);

        // Moving the trip moves its dated stays and journeys with it
        Map<String, Object> later = plan(agent.employeeId(), 2, 6000);
        later.remove("extraBudgetReason");
        later.put("startDate", TODAY.plusDays(7).toString());
        JsonNode moved = data(put(DEMOS + "/" + id, l2a.token(), later, 200));
        assertThat(moved.get("expenses").findValuesAsText("startDate"))
                .containsExactlyInAnyOrder(TODAY.plusDays(7).toString(), TODAY.plusDays(8).toString(), TODAY.plusDays(7).toString());
    }

    @Test
    void productUnitsArePickedByBarcodeHandedOutOnTheDayAndAllComeBackBeforeCompletion() throws Exception {
        Attendee agent = attendee();
        List<String> rudrax = insertProductUnits(77, "RudraX", 3);
        Map<String, Object> plan = plan(agent.employeeId(), 1, 5000);
        plan.put("products", List.of(body("productId", 77, "productName", "RudraX", "quantity", 2,
                "barcodes", List.of(rudrax.get(0).toLowerCase(), rudrax.get(1)))));
        long id = create(plan);
        String reference = String.format("DEMO-%05d", id);

        // A product without its units' barcodes, or a unit of another product, is refused
        Map<String, Object> noUnits = new java.util.LinkedHashMap<>(plan);
        noUnits.put("products", List.of(body("productId", 77, "productName", "RudraX", "quantity", 1)));
        post(DEMOS, l2a.token(), noUnits, 400);
        noUnits.put("products", List.of(body("productId", 78, "productName", "Other", "quantity", 1, "barcodes", List.of(rudrax.get(2)))));
        post(DEMOS, l2a.token(), noUnits, 409);

        // A unit picked for these days can't be picked by another demo on an overlapping day, only on other days
        Map<String, Object> clash = new java.util.LinkedHashMap<>(plan);
        clash.put("products", List.of(body("productId", 77, "productName", "RudraX", "quantity", 1, "barcodes", List.of(rudrax.get(1)))));
        assertThat(post(DEMOS, l2a.token(), clash, 409).get("message").asText()).contains("taken by " + reference);
        clash.put("startDate", TODAY.plusDays(3).toString());
        create(clash);

        // Picking: the product's units for the days, or one unit by its barcode (typed or scanned)
        JsonNode options = data(get(DEMOS + "/units?productId=77&startDate=" + TODAY + "&days=1", l2a.token(), 200));
        assertThat(options).hasSize(3);
        assertThat(options.findValuesAsText("barcode")).containsExactlyElementsOf(rudrax);
        assertThat(options.findValuesAsText("available")).containsExactly("false", "false", "true");
        assertThat(options.get(0).get("heldBy").asText()).isEqualTo(reference);
        JsonNode free = data(get(DEMOS + "/units/lookup?code=" + rudrax.get(2).toLowerCase() + "&startDate=" + TODAY, l2a.token(), 200));
        assertThat(free.get("productTitle").asText()).isEqualTo("RudraX");
        assertThat(free.get("available").asBoolean()).isTrue();
        assertThat(data(get(DEMOS + "/units/lookup?code=" + rudrax.get(0) + "&startDate=" + TODAY + "&demoId=" + id, l2a.token(), 200))
                .get("available").asBoolean()).isTrue();
        get(DEMOS + "/units/lookup?code=NO-SUCH-UNIT&startDate=" + TODAY, l2a.token(), 404);
        get(DEMOS + "/units?productId=77&startDate=" + TODAY, agent.token(), 403);

        // Units leave stock only once the demo is approved
        post(DEMOS + "/" + id + "/units/hand-out", l2a.token(), body("barcodes", List.of(rudrax.get(0))), 409);
        post(DEMOS + "/" + id + "/submit", l2a.token(), null, 200);
        decide(l2b, id, "APPROVED", 200);

        // The attending agent hands one out: the storefront's stock goes down first, then the unit is out here
        JsonNode out = data(post(MINE + "/" + id + "/units/hand-out", agent.token(), body("barcodes", List.of(rudrax.get(0))), 200));
        verify(demoStockClient).move(anyString(), eq(DemoStockClient.Direction.OUT), eq(reference), eq(Map.of(77L, 1)), anyString());
        assertThat(barcodeStatus(rudrax.get(0))).isEqualTo("ON_DEMO");
        assertThat(unitStatus(out, rudrax.get(0))).isEqualTo("OUT");
        assertThat(unitStatus(out, rudrax.get(1))).isEqualTo("RESERVED");
        assertThat(out.get("events").findValuesAsText("type")).contains("UNITS_HANDED_OUT");
        // Handing it out again is refused before the storefront is asked
        clearInvocations(demoStockClient);
        post(MINE + "/" + id + "/units/hand-out", agent.token(), body("barcodes", List.of(rudrax.get(0))), 409);
        verify(demoStockClient, never()).move(anyString(), any(), anyString(), any(), anyString());

        // The storefront refuses (the unit was sold online): nothing changes here
        doThrow(new ApiException("Too few units are left to sell", "DEMO_STOCK_REFUSED", HttpStatus.CONFLICT))
                .when(demoStockClient).move(anyString(), eq(DemoStockClient.Direction.OUT), anyString(), any(), anyString());
        post(DEMOS + "/" + id + "/units/hand-out", l2a.token(), body("barcodes", List.of(rudrax.get(1))), 409);
        assertThat(barcodeStatus(rudrax.get(1))).isEqualTo("IN_STOCK");
        reset(demoStockClient);

        // A unit handed out stays on the demo; the demo can't be completed or cancelled while it is out
        Map<String, Object> dropOut = new java.util.LinkedHashMap<>(plan);
        dropOut.put("products", List.of(body("productId", 77, "productName", "RudraX", "quantity", 1, "barcodes", List.of(rudrax.get(1)))));
        dropOut.put("changeReason", "Taking one less");
        put(DEMOS + "/" + id, l2a.token(), dropOut, 400);
        assertThat(post(DEMOS + "/" + id + "/complete", l2a.token(), body("outcome", "Done"), 409).get("errorCode").asText())
                .isEqualTo("DEMO_UNITS_OUT");
        post(DEMOS + "/" + id + "/cancel", l2a.token(), body("reason", "Client cancelled"), 409);

        // Handed back: in stock again on both sides
        post(MINE + "/" + id + "/units/hand-back", agent.token(), body("barcodes", List.of(rudrax.get(0))), 200);
        verify(demoStockClient).move(anyString(), eq(DemoStockClient.Direction.BACK), eq(reference), eq(Map.of(77L, 1)), anyString());
        assertThat(barcodeStatus(rudrax.get(0))).isEqualTo("IN_STOCK");

        // A unit that does not come back is written off by a Super Admin only; it leaves stock for good
        post(DEMOS + "/" + id + "/units/hand-out", l2a.token(), body("barcodes", List.of(rudrax.get(1))), 200);
        post(DEMOS + "/" + id + "/units/write-off", l1.token(), body("barcode", rudrax.get(1), "reason", "Dropped at the venue"), 403);
        JsonNode writtenOff = data(post(DEMOS + "/" + id + "/units/write-off", l2a.token(),
                body("barcode", rudrax.get(1), "reason", "Dropped at the venue"), 200));
        assertThat(barcodeStatus(rudrax.get(1))).isEqualTo("REMOVED");
        assertThat(unitStatus(writtenOff, rudrax.get(0))).isEqualTo("RETURNED");
        assertThat(unitStatus(writtenOff, rudrax.get(1))).isEqualTo("WRITTEN_OFF");

        // Every unit is accounted for, so the demo completes
        post(DEMOS + "/" + id + "/complete", l2a.token(), body("outcome", "Demo done"), 200);
        assertThat(data(decide(l2b, id, "APPROVED", 200)).get("status").asText()).isEqualTo("COMPLETED");
    }
}
