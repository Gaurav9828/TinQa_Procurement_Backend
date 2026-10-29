package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dealer logins and the dealer portal: a dealer signs in to the web app, sees and quotes only for themselves, and
 * every change to a quotation waits for a Level 2 Dealer Manager.
 */
@DisplayName("Dealer portal")
class DealerPortalIntegrationTest extends IntegrationTestSupport {

    private static final String DEALER_PASSWORD = "Dealer!Passw0rd#2026";

    private record Dealer(long dealerId, String username, String token) {
    }

    // A dealer with an approved login whose first password is already set, signed in
    private Dealer dealerWithLogin() throws Exception {
        return dealerWithLogin(createDealer());
    }

    private Dealer dealerWithLogin(long dealerId) throws Exception {
        String username = uniq("dealer");
        post("/v1/admin/dealers/" + dealerId + "/login", l1.token(), body("username", username, "email", username + "@dealer.test"), 201);
        patch("/v1/admin/dealers/" + dealerId + "/login/approval", l2a.token(), body("decision", "APPROVED"), 200);
        String first = login(username, DEFAULT_EMPLOYEE_PASSWORD);
        post("/auth/first-login/password", first, body("newPassword", DEALER_PASSWORD, "confirmNewPassword", DEALER_PASSWORD), 200);
        return new Dealer(dealerId, username, login(username, DEALER_PASSWORD));
    }

    private Map<String, Object> quotation(long itemId) {
        return body("itemId", itemId, "orderQuantity", new BigDecimal("10"), "unitType", "PCS",
                "unitPrice", new BigDecimal("100.00"), "shipmentPrice", new BigDecimal("50.00"), "note", "Ex-works Pune",
                "priceChangeReason", "Bulk rate");
    }

    @Test
    void aPriceOtherThanTheItemsMrpNeedsAReason() throws Exception {
        Dealer dealer = dealerWithLogin();
        long itemId = createItem();
        JsonNode items = data(get("/v1/dealer-portal/items", dealer.token(), 200));
        JsonNode item = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(node -> node.get("id").asLong() == itemId).findFirst().orElseThrow();
        assertThat(item.get("mrp").decimalValue()).isEqualByComparingTo("1500.00");

        Map<String, Object> raised = quotation(itemId);
        raised.put("unitPrice", new BigDecimal("1700.00"));
        raised.remove("priceChangeReason");
        JsonNode refused = post("/v1/dealer-portal/quotations", dealer.token(), raised, 400);
        assertThat(refused.get("message").asText()).contains("reason");

        raised.put("priceChangeReason", "  Copper prices went up  ");
        long orderId = data(post("/v1/dealer-portal/quotations", dealer.token(), raised, 201)).get("id").asLong();
        JsonNode info = data(get("/v1/orders/" + orderId, l1.token(), 200)).get("additionalInfo");
        assertThat(info.get("priceChangeReason").asText()).isEqualTo("Copper prices went up");
        assertThat(info.get("mrp").decimalValue()).isEqualByComparingTo("1500.00");

        // At the existing price no reason is needed, and one sent anyway isn't kept
        Map<String, Object> atMrp = quotation(itemId);
        atMrp.put("unitPrice", new BigDecimal("1500.00"));
        long plain = data(post("/v1/dealer-portal/quotations", dealer.token(), atMrp, 201)).get("id").asLong();
        assertThat(data(get("/v1/orders/" + plain, l1.token(), 200)).get("additionalInfo").has("priceChangeReason")).isFalse();
    }

    @Test
    void aDealerLoginSignsInOnlyOnceSomeoneElseApprovesIt() throws Exception {
        long dealerId = createDealer();
        String username = uniq("dealer");
        JsonNode created = data(post("/v1/admin/dealers/" + dealerId + "/login", l2a.token(),
                body("username", username, "email", username + "@dealer.test"), 201));
        assertThat(created.get("status").asText()).isEqualTo("APPROVAL_PENDING");

        JsonNode refused = post("/auth/admin/login", null, body("username", username, "password", DEFAULT_EMPLOYEE_PASSWORD), 400);
        assertThat(refused.get("message").asText()).contains("waiting for approval");

        // Level 1 can't approve, and the creator can't approve their own
        patch("/v1/admin/dealers/" + dealerId + "/login/approval", l1.token(), body("decision", "APPROVED"), 403);
        patch("/v1/admin/dealers/" + dealerId + "/login/approval", l2a.token(), body("decision", "APPROVED"), 403);
        patch("/v1/admin/dealers/" + dealerId + "/login/approval", l2b.token(), body("decision", "APPROVED"), 200);
        assertThat(notificationTitles(l2a)).contains("Dealer login approved");

        JsonNode signedIn = post("/auth/admin/login", null, body("username", username, "password", DEFAULT_EMPLOYEE_PASSWORD), 200);
        assertThat(signedIn.get("role").asText()).isEqualTo("DEALER");
        assertThat(signedIn.get("designationLabel").asText()).isEqualTo("Dealer");
        assertThat(signedIn.get("firstLogin").asBoolean()).isTrue();

        // One login per dealer
        post("/v1/admin/dealers/" + dealerId + "/login", l1.token(), body("username", uniq("again"), "email", uniq("again") + "@dealer.test"), 409);
    }

    @Test
    void dealersOnlyReachTheirOwnRecords() throws Exception {
        Dealer dealer = dealerWithLogin();
        long otherDealer = createDealer();
        long itemId = createItem();
        long othersOrder = createOrder(otherDealer, itemId, "5").get("id").asLong();

        get("/v1/orders", dealer.token(), 403);
        get("/v1/orders/" + othersOrder, dealer.token(), 403);
        get("/v1/admin/dealers", dealer.token(), 403);
        get("/v1/admin/items", dealer.token(), 403);
        get("/v1/admin/profile", dealer.token(), 403);
        get("/v1/dealer-portal/orders/" + othersOrder, dealer.token(), 404);

        JsonNode me = data(get("/v1/dealer-portal/me", dealer.token(), 200));
        assertThat(me.get("dealerId").asLong()).isEqualTo(dealer.dealerId());

        // The dealer is taken from the account: a dealerId in the body is ignored
        Map<String, Object> request = quotation(itemId);
        request.put("dealerId", otherDealer);
        JsonNode quote = data(post("/v1/dealer-portal/quotations", dealer.token(), request, 201));
        assertThat(quote.get("orderStatus").asText()).isEqualTo("QUOTATION");
        assertThat(quote.get("raisedByYou").asBoolean()).isTrue();
        assertThat(quote.get("note").asText()).isEqualTo("Ex-works Pune");
        JsonNode staffView = data(get("/v1/orders/" + quote.get("id").asLong(), l1.token(), 200));
        assertThat(staffView.get("dealerId").asLong()).isEqualTo(dealer.dealerId());
        assertThat(staffView.get("raisedByDealer").asBoolean()).isTrue();

        JsonNode mine = data(get("/v1/dealer-portal/orders", dealer.token(), 200));
        assertThat(mine.findValuesAsText("orderNumber")).containsExactly(quote.get("orderNumber").asText());
    }

    @Test
    void dealersOnlySeeAndQuoteForItemsOfTheirCategories() throws Exception {
        Dealer dealer = dealerWithLogin();
        long other = createCategory(l1);
        long mine = createItem();
        long both = data(post("/v1/admin/items", l1.token(), itemBody(other, fixtureCategory()), 201)).get("id").asLong();
        long elsewhere = data(post("/v1/admin/items", l1.token(), itemBody(other), 201)).get("id").asLong();

        JsonNode items = data(get("/v1/dealer-portal/items", dealer.token(), 200));
        assertThat(items.findValues("id").stream().map(JsonNode::asLong)).containsExactlyInAnyOrder(mine, both);

        JsonNode refused = post("/v1/dealer-portal/quotations", dealer.token(), quotation(elsewhere), 400);
        assertThat(refused.get("errors").findValuesAsText("field")).containsExactly("itemId");
        long orderId = data(post("/v1/dealer-portal/quotations", dealer.token(), quotation(both), 201)).get("id").asLong();
        post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(),
                body("itemId", elsewhere, "reason", "Other model"), 400);
        post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(),
                body("itemId", mine, "reason", "Other model"), 201);

        // Once the dealer is assigned the other category, its items are theirs too
        put("/v1/admin/dealers/" + dealer.dealerId() + "/categories", l1.token(), List.of(fixtureCategory(), other), 200);
        assertThat(data(get("/v1/dealer-portal/items", dealer.token(), 200)).findValues("id").stream().map(JsonNode::asLong))
                .contains(elsewhere);
    }

    @Test
    void aDealersChangeAppliesOnlyWhenADealerManagerApprovesIt() throws Exception {
        Dealer dealer = dealerWithLogin();
        long itemId = createItem();
        long orderId = data(post("/v1/dealer-portal/quotations", dealer.token(), quotation(itemId), 201)).get("id").asLong();

        post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(),
                body("unitPrice", new BigDecimal("100.00")), 409);
        JsonNode change = data(post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(),
                body("unitPrice", new BigDecimal("90.00"), "reason", "Bulk discount"), 201));
        assertThat(change.get("status").asText()).isEqualTo("PENDING");
        assertThat(change.get("proposedTotalPrice").decimalValue()).isEqualByComparingTo("950.00");

        // Nothing changed yet, and the quotation is frozen for everyone
        assertThat(data(get("/v1/orders/" + orderId, l1.token(), 200)).get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(data(get("/v1/orders/" + orderId, l1.token(), 200)).get("dealerChangePending").asBoolean()).isTrue();
        put("/v1/orders/" + orderId, l1.token(), body("unitPrice", new BigDecimal("80.00")), 409);
        patch("/v1/orders/" + orderId + "/status", l1.token(), body("status", "PENDING"), 409);
        post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(), body("orderQuantity", 20), 409);

        long changeId = change.get("id").asLong();
        patch("/v1/orders/change-requests/" + changeId + "/decision", dealer.token(), body("decision", "APPROVED"), 403);
        patch("/v1/orders/change-requests/" + changeId + "/decision", l1.token(), body("decision", "REJECTED"), 400);
        // Any Dealer Manager decides it, Level 1 included
        patch("/v1/orders/change-requests/" + changeId + "/decision", l1.token(), body("decision", "APPROVED"), 200);

        JsonNode order = data(get("/v1/orders/" + orderId, l1.token(), 200));
        assertThat(order.get("unitPrice").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(order.get("totalPrice").decimalValue()).isEqualByComparingTo("950.00");
        assertThat(order.get("dealerChangePending").asBoolean()).isFalse();
        assertThat(notificationTitles(new Actor(null, null, dealer.username(), null, dealer.token())))
                .contains("Quotation change approved");

        // Once staff submit the quotation it is an order, and the dealer can no longer change it
        moveOrder(orderId, l1, "PENDING");
        post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(), body("orderQuantity", 20), 409);
        assertThat(data(get("/v1/dealer-portal/orders/" + orderId, dealer.token(), 200)).get("changeable").asBoolean()).isFalse();
    }

    @Test
    void aDealerCanWithdrawTheirChange() throws Exception {
        Dealer dealer = dealerWithLogin();
        Dealer other = dealerWithLogin();
        long orderId = data(post("/v1/dealer-portal/quotations", dealer.token(), quotation(createItem()), 201)).get("id").asLong();
        long changeId = data(post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer.token(),
                body("orderQuantity", 12), 201)).get("id").asLong();

        patch("/v1/dealer-portal/change-requests/" + changeId + "/withdraw", other.token(), null, 404);
        JsonNode withdrawn = data(patch("/v1/dealer-portal/change-requests/" + changeId + "/withdraw", dealer.token(), null, 200));
        assertThat(withdrawn.get("status").asText()).isEqualTo("WITHDRAWN");
        put("/v1/orders/" + orderId, l1.token(), body("orderQuantity", 15), 200);
    }

    @Test
    void deactivatingTheDealerEndsTheirSessions() throws Exception {
        Dealer dealer = dealerWithLogin();
        get("/v1/dealer-portal/orders", dealer.token(), 200);

        patch("/v1/admin/dealers/" + dealer.dealerId() + "/toggle-status", l1.token(), null, 200);
        get("/v1/dealer-portal/orders", dealer.token(), 401);

        patch("/v1/admin/dealers/" + dealer.dealerId() + "/toggle-status", l1.token(), null, 200);
        String token = login(dealer.username(), DEALER_PASSWORD);
        get("/v1/dealer-portal/orders", token, 200);

        patch("/v1/admin/dealers/" + dealer.dealerId() + "/login/status", l1.token(), body("enabled", false), 200);
        JsonNode refused = post("/auth/admin/login", null, body("username", dealer.username(), "password", DEALER_PASSWORD), 400);
        assertThat(refused.get("message").asText()).contains("disabled");
    }

    // Staff raise the order, submit it and Level 2 approves it: it then waits for the dealer
    private long orderAwaitingDealer(long dealerId, long itemId) throws Exception {
        long orderId = createOrder(dealerId, itemId, "5").get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "DEALER_LEVEL_PENDING"), 200);
        return orderId;
    }

    @Test
    void aDealerAcceptsOrRejectsOrdersRaisedBeforeTheyHadALogin() throws Exception {
        long dealerId = createDealer();
        long itemId = createItem();
        long accepted = orderAwaitingDealer(dealerId, itemId);
        long rejected = orderAwaitingDealer(dealerId, itemId);
        long quotation = createOrder(dealerId, itemId, "1").get("id").asLong();
        Dealer dealer = dealerWithLogin(dealerId);
        Dealer other = dealerWithLogin();

        JsonNode waiting = data(get("/v1/dealer-portal/orders/" + accepted, dealer.token(), 200));
        assertThat(waiting.get("awaitingYourResponse").asBoolean()).isTrue();

        String respond = "/v1/dealer-portal/orders/%d/response";
        post(respond.formatted(accepted), other.token(), body("decision", "ACCEPT"), 404);
        post(respond.formatted(accepted), l2a.token(), body("decision", "ACCEPT"), 403);
        post(respond.formatted(quotation), dealer.token(), body("decision", "ACCEPT"), 409);
        post(respond.formatted(rejected), dealer.token(), body("decision", "REJECT"), 400);

        JsonNode confirmed = data(post(respond.formatted(accepted), dealer.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("orderStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("awaitingYourResponse").asBoolean()).isFalse();
        post(respond.formatted(accepted), dealer.token(), body("decision", "REJECT", "reason", "Too late"), 409);

        JsonNode cancelled = data(post(respond.formatted(rejected), dealer.token(),
                body("decision", "REJECT", "reason", "Out of stock until next month"), 200));
        assertThat(cancelled.get("orderStatus").asText()).isEqualTo("CANCELLED");
        JsonNode history = data(get("/v1/dealer-portal/orders/" + rejected, dealer.token(), 200)).get("history");
        assertThat(history.get(history.size() - 1).get("reason").asText()).isEqualTo("Out of stock until next month");
        assertThat(notificationTitles(l2a)).contains("Order Rejected by Dealer");
    }

    @Test
    void aDealerMarksTheirConfirmedOrdersAsShipped() throws Exception {
        long dealerId = createDealer();
        long itemId = createItem();
        long orderId = orderAwaitingDealer(dealerId, itemId);
        Dealer dealer = dealerWithLogin(dealerId);
        Dealer other = dealerWithLogin();
        String ship = "/v1/dealer-portal/orders/" + orderId + "/ship";

        post(ship, dealer.token(), body(), 409);
        JsonNode confirmed = data(post("/v1/dealer-portal/orders/" + orderId + "/response", dealer.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("shippable").asBoolean()).isTrue();
        post(ship, other.token(), body(), 404);
        post(ship, l2a.token(), body(), 403);

        JsonNode shipped = data(post(ship, dealer.token(), body(), 200));
        assertThat(shipped.get("orderStatus").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.get("shippable").asBoolean()).isFalse();
        post(ship, dealer.token(), body(), 409);
        assertThat(notificationTitles(l1)).contains("Order Shipped by Dealer");
        // Staff record the delivery as usual
        assertThat(data(get("/v1/orders/" + orderId, l1.token(), 200)).get("orderStatus").asText()).isEqualTo("SHIPPED");
    }

    @Test
    void ordersOfAnEarlierRecordWithTheSameEmailBelongToTheDealer() throws Exception {
        String email = uniq("same") + "@dealer.test";
        long category = fixtureCategory();
        Map<String, Object> first = dealerBody(List.of(category));
        first.put("email", email);
        long oldRecord = data(post("/v1/admin/dealers", l1.token(), first, 201)).get("id").asLong();
        long orderId = orderAwaitingDealer(oldRecord, createItem());
        delete("/v1/admin/dealers/" + oldRecord, l2a.token(), 200);

        Map<String, Object> second = dealerBody(List.of(category));
        second.put("email", email.toUpperCase());
        long newRecord = data(post("/v1/admin/dealers", l1.token(), second, 201)).get("id").asLong();
        Dealer dealer = dealerWithLogin(newRecord);

        JsonNode mine = data(get("/v1/dealer-portal/orders", dealer.token(), 200));
        assertThat(mine.findValuesAsText("id")).contains(String.valueOf(orderId));
        JsonNode confirmed = data(post("/v1/dealer-portal/orders/" + orderId + "/response", dealer.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("orderStatus").asText()).isEqualTo("CONFIRMED");
    }
}
