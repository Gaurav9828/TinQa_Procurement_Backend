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
 * Supplier logins and the supplier portal: a supplier signs in to the web app, sees and quotes only for themselves, and
 * every change to a quotation waits for a Level 2 Supplier Manager.
 */
@DisplayName("Supplier portal")
class SupplierPortalIntegrationTest extends IntegrationTestSupport {

    private static final String SUPPLIER_PASSWORD = "Supplier!Passw0rd#2026";

    private record Supplier(long supplierId, String username, String token) {
    }

    // A supplier with an approved login whose first password is already set, signed in
    private Supplier supplierWithLogin() throws Exception {
        return supplierWithLogin(createSupplier());
    }

    private Supplier supplierWithLogin(long supplierId) throws Exception {
        String username = uniq("supplier");
        post("/v1/admin/suppliers/" + supplierId + "/login", l1.token(), body("username", username, "email", username + "@supplier.test"), 201);
        patch("/v1/admin/suppliers/" + supplierId + "/login/approval", l2a.token(), body("decision", "APPROVED"), 200);
        String first = login(username, DEFAULT_EMPLOYEE_PASSWORD);
        post("/auth/first-login/password", first, body("newPassword", SUPPLIER_PASSWORD, "confirmNewPassword", SUPPLIER_PASSWORD), 200);
        return new Supplier(supplierId, username, login(username, SUPPLIER_PASSWORD));
    }

    private Map<String, Object> quotation(long itemId) {
        return body("itemId", itemId, "orderQuantity", new BigDecimal("10"), "unitType", "PCS",
                "unitPrice", new BigDecimal("100.00"), "shipmentPrice", new BigDecimal("50.00"), "note", "Ex-works Pune",
                "priceChangeReason", "Bulk rate", "advancePercent", new BigDecimal("30"), "midtermPercent", new BigDecimal("30"),
                "expectedPaymentMethod", "CHEQUE");
    }

    @Test
    void aPriceOtherThanTheItemsMrpNeedsAReason() throws Exception {
        Supplier supplier = supplierWithLogin();
        long itemId = createItem();
        JsonNode items = data(get("/v1/supplier-portal/items", supplier.token(), 200));
        JsonNode item = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(node -> node.get("id").asLong() == itemId).findFirst().orElseThrow();
        assertThat(item.get("mrp").decimalValue()).isEqualByComparingTo("1500.00");

        Map<String, Object> raised = quotation(itemId);
        raised.put("unitPrice", new BigDecimal("1700.00"));
        raised.remove("priceChangeReason");
        JsonNode refused = post("/v1/supplier-portal/quotations", supplier.token(), raised, 400);
        assertThat(refused.get("message").asText()).contains("reason");

        raised.put("priceChangeReason", "  Copper prices went up  ");
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), raised, 201)).get("id").asLong();
        JsonNode info = data(get("/v1/orders/" + orderId, l1.token(), 200)).get("additionalInfo");
        assertThat(info.get("priceChangeReason").asText()).isEqualTo("Copper prices went up");
        assertThat(info.get("mrp").decimalValue()).isEqualByComparingTo("1500.00");

        // At the existing price no reason is needed, and one sent anyway isn't kept
        Map<String, Object> atMrp = quotation(itemId);
        atMrp.put("unitPrice", new BigDecimal("1500.00"));
        long plain = data(post("/v1/supplier-portal/quotations", supplier.token(), atMrp, 201)).get("id").asLong();
        assertThat(data(get("/v1/orders/" + plain, l1.token(), 200)).get("additionalInfo").has("priceChangeReason")).isFalse();
    }

    @Test
    void aSupplierLoginSignsInOnlyOnceSomeoneElseApprovesIt() throws Exception {
        long supplierId = createSupplier();
        String username = uniq("supplier");
        JsonNode created = data(post("/v1/admin/suppliers/" + supplierId + "/login", l2a.token(),
                body("username", username, "email", username + "@supplier.test"), 201));
        assertThat(created.get("status").asText()).isEqualTo("APPROVAL_PENDING");

        JsonNode refused = post("/auth/admin/login", null, body("username", username, "password", DEFAULT_EMPLOYEE_PASSWORD), 400);
        assertThat(refused.get("message").asText()).contains("waiting for approval");

        // Level 1 can't approve, and the creator can't approve their own
        patch("/v1/admin/suppliers/" + supplierId + "/login/approval", l1.token(), body("decision", "APPROVED"), 403);
        patch("/v1/admin/suppliers/" + supplierId + "/login/approval", l2a.token(), body("decision", "APPROVED"), 403);
        patch("/v1/admin/suppliers/" + supplierId + "/login/approval", l2b.token(), body("decision", "APPROVED"), 200);
        assertThat(notificationTitles(l2a)).contains("Supplier login approved");

        JsonNode signedIn = post("/auth/admin/login", null, body("username", username, "password", DEFAULT_EMPLOYEE_PASSWORD), 200);
        assertThat(signedIn.get("role").asText()).isEqualTo("SUPPLIER");
        assertThat(signedIn.get("designationLabel").asText()).isEqualTo("Supplier");
        assertThat(signedIn.get("firstLogin").asBoolean()).isTrue();

        // One login per supplier
        post("/v1/admin/suppliers/" + supplierId + "/login", l1.token(), body("username", uniq("again"), "email", uniq("again") + "@supplier.test"), 409);
    }

    @Test
    void suppliersOnlyReachTheirOwnRecords() throws Exception {
        Supplier supplier = supplierWithLogin();
        long otherSupplier = createSupplier();
        long itemId = createItem();
        long othersOrder = createOrder(otherSupplier, itemId, "5").get("id").asLong();

        get("/v1/orders", supplier.token(), 403);
        get("/v1/orders/" + othersOrder, supplier.token(), 403);
        get("/v1/admin/suppliers", supplier.token(), 403);
        get("/v1/admin/items", supplier.token(), 403);
        get("/v1/admin/profile", supplier.token(), 403);
        get("/v1/supplier-portal/orders/" + othersOrder, supplier.token(), 404);

        JsonNode me = data(get("/v1/supplier-portal/me", supplier.token(), 200));
        assertThat(me.get("supplierId").asLong()).isEqualTo(supplier.supplierId());

        // The supplier is taken from the account: a supplierId in the body is ignored
        Map<String, Object> request = quotation(itemId);
        request.put("supplierId", otherSupplier);
        JsonNode quote = data(post("/v1/supplier-portal/quotations", supplier.token(), request, 201));
        assertThat(quote.get("orderStatus").asText()).isEqualTo("QUOTATION");
        assertThat(quote.get("raisedByYou").asBoolean()).isTrue();
        assertThat(quote.get("note").asText()).isEqualTo("Ex-works Pune");
        JsonNode staffView = data(get("/v1/orders/" + quote.get("id").asLong(), l1.token(), 200));
        assertThat(staffView.get("supplierId").asLong()).isEqualTo(supplier.supplierId());
        assertThat(staffView.get("raisedBySupplier").asBoolean()).isTrue();

        JsonNode mine = data(get("/v1/supplier-portal/orders", supplier.token(), 200));
        assertThat(mine.findValuesAsText("orderNumber")).containsExactly(quote.get("orderNumber").asText());
    }

    @Test
    void suppliersOnlySeeAndQuoteForItemsOfTheirCategories() throws Exception {
        Supplier supplier = supplierWithLogin();
        long other = createCategory(l1);
        long mine = createItem();
        long both = data(post("/v1/admin/items", l1.token(), itemBody(other, fixtureCategory()), 201)).get("id").asLong();
        long elsewhere = data(post("/v1/admin/items", l1.token(), itemBody(other), 201)).get("id").asLong();

        JsonNode items = data(get("/v1/supplier-portal/items", supplier.token(), 200));
        assertThat(items.findValues("id").stream().map(JsonNode::asLong)).containsExactlyInAnyOrder(mine, both);

        JsonNode refused = post("/v1/supplier-portal/quotations", supplier.token(), quotation(elsewhere), 400);
        assertThat(refused.get("errors").findValuesAsText("field")).containsExactly("itemId");
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(both), 201)).get("id").asLong();
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("itemId", elsewhere, "reason", "Other model"), 400);
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("itemId", mine, "reason", "Other model"), 201);

        // Once the supplier is assigned the other category, its items are theirs too
        put("/v1/admin/suppliers/" + supplier.supplierId() + "/categories", l1.token(), List.of(fixtureCategory(), other), 200);
        assertThat(data(get("/v1/supplier-portal/items", supplier.token(), 200)).findValues("id").stream().map(JsonNode::asLong))
                .contains(elsewhere);
    }

    @Test
    void aSuppliersChangeAppliesOnlyWhenASupplierManagerApprovesIt() throws Exception {
        Supplier supplier = supplierWithLogin();
        long itemId = createItem();
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(itemId), 201)).get("id").asLong();

        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("unitPrice", new BigDecimal("100.00")), 409);
        JsonNode change = data(post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("unitPrice", new BigDecimal("90.00"), "reason", "Bulk discount"), 201));
        assertThat(change.get("status").asText()).isEqualTo("PENDING");
        assertThat(change.get("proposedTotalPrice").decimalValue()).isEqualByComparingTo("950.00");

        // Nothing changed yet, and the quotation is frozen for everyone
        assertThat(data(get("/v1/orders/" + orderId, l1.token(), 200)).get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(data(get("/v1/orders/" + orderId, l1.token(), 200)).get("supplierChangePending").asBoolean()).isTrue();
        put("/v1/orders/" + orderId, l1.token(), body("unitPrice", new BigDecimal("80.00")), 409);
        patch("/v1/orders/" + orderId + "/status", l1.token(), body("status", "PENDING"), 409);
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(), body("orderQuantity", 20), 409);

        long changeId = change.get("id").asLong();
        patch("/v1/orders/change-requests/" + changeId + "/decision", supplier.token(), body("decision", "APPROVED"), 403);
        patch("/v1/orders/change-requests/" + changeId + "/decision", l1.token(), body("decision", "REJECTED"), 400);
        // Any Supplier Manager decides it, Level 1 included
        patch("/v1/orders/change-requests/" + changeId + "/decision", l1.token(), body("decision", "APPROVED"), 200);

        JsonNode order = data(get("/v1/orders/" + orderId, l1.token(), 200));
        assertThat(order.get("unitPrice").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(order.get("totalPrice").decimalValue()).isEqualByComparingTo("950.00");
        assertThat(order.get("supplierChangePending").asBoolean()).isFalse();
        assertThat(notificationTitles(new Actor(null, null, supplier.username(), null, supplier.token())))
                .contains("Quotation change approved");

        // Once staff submit the quotation it is an order, and the supplier can no longer change it
        moveOrder(orderId, l1, "PENDING");
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(), body("orderQuantity", 20), 409);
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId, supplier.token(), 200)).get("changeable").asBoolean()).isFalse();
    }

    @Test
    void theSupplierProposesTheirPaymentPlanAndSeesOnlyConfirmedPayments() throws Exception {
        Supplier supplier = supplierWithLogin();
        JsonNode quote = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(createItem()), 201));
        assertThat(quote.get("finalPercent").decimalValue()).isEqualByComparingTo("40");
        assertThat(quote.get("expectedPaymentMethod").asText()).isEqualTo("CHEQUE");
        long orderId = quote.get("id").asLong();

        // A plan over 100 % is refused; a new plan is a change any Supplier Manager approves
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(), body("advancePercent", 80), 400);
        long changeId = data(post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("advancePercent", 20, "expectedPaymentMethod", "BANK_TRANSFER"), 201)).get("id").asLong();
        assertThat(notificationTitles(l1)).contains("Supplier quotation change awaiting approval");
        patch("/v1/orders/change-requests/" + changeId + "/decision", l1.token(), body("decision", "APPROVED"), 200);
        JsonNode changed = data(get("/v1/orders/" + orderId, l1.token(), 200));
        assertThat(changed.get("advancePercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(changed.get("finalPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(changed.get("expectedPaymentMethod").asText()).isEqualTo("BANK_TRANSFER");

        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        Map<String, Object> advance = body("kind", "PAYMENT", "milestone", "ADVANCE", "amount", new BigDecimal("100.00"),
                "method", "BANK_TRANSFER", "paidOn", java.time.LocalDate.now().toString());
        long confirmedId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), advance, 201)).get("id").asLong();
        patch("/v1/orders/payments/" + confirmedId + "/decision", l2a.token(), body("decision", "APPROVED"), 200);
        advance.put("amount", new BigDecimal("50.00"));
        post("/v1/orders/" + orderId + "/payments", l1.token(), advance, 201);

        JsonNode view = data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200));
        assertThat(view.get("summary").get("paid").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(view.get("entries")).hasSize(1);
        assertThat(view.get("entries").get(0).get("recordedByUsername").isNull()).isTrue();
        assertThat(notificationTitles(new Actor(null, null, supplier.username(), null, supplier.token())))
                .contains("Payment recorded for order " + quote.get("orderNumber").asText());
        // Another supplier's order is not found
        get("/v1/supplier-portal/orders/" + orderId + "/payments", supplierWithLogin().token(), 404);
    }

    @Test
    void aSupplierCanWithdrawTheirChange() throws Exception {
        Supplier supplier = supplierWithLogin();
        Supplier other = supplierWithLogin();
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(createItem()), 201)).get("id").asLong();
        long changeId = data(post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("orderQuantity", 12), 201)).get("id").asLong();

        patch("/v1/supplier-portal/change-requests/" + changeId + "/withdraw", other.token(), null, 404);
        JsonNode withdrawn = data(patch("/v1/supplier-portal/change-requests/" + changeId + "/withdraw", supplier.token(), null, 200));
        assertThat(withdrawn.get("status").asText()).isEqualTo("WITHDRAWN");
        put("/v1/orders/" + orderId, l1.token(), body("orderQuantity", 15), 200);
    }

    @Test
    void deactivatingTheSupplierEndsTheirSessions() throws Exception {
        Supplier supplier = supplierWithLogin();
        get("/v1/supplier-portal/orders", supplier.token(), 200);

        patch("/v1/admin/suppliers/" + supplier.supplierId() + "/toggle-status", l1.token(), null, 200);
        get("/v1/supplier-portal/orders", supplier.token(), 401);

        patch("/v1/admin/suppliers/" + supplier.supplierId() + "/toggle-status", l1.token(), null, 200);
        String token = login(supplier.username(), SUPPLIER_PASSWORD);
        get("/v1/supplier-portal/orders", token, 200);

        patch("/v1/admin/suppliers/" + supplier.supplierId() + "/login/status", l1.token(), body("enabled", false), 200);
        JsonNode refused = post("/auth/admin/login", null, body("username", supplier.username(), "password", SUPPLIER_PASSWORD), 400);
        assertThat(refused.get("message").asText()).contains("disabled");
    }

    // Staff raise the order, submit it and Level 2 approves it: it then waits for the supplier
    private long orderAwaitingSupplier(long supplierId, long itemId) throws Exception {
        long orderId = createOrder(supplierId, itemId, "5").get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        return orderId;
    }

    @Test
    void aSupplierAcceptsOrRejectsOrdersRaisedBeforeTheyHadALogin() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();
        long accepted = orderAwaitingSupplier(supplierId, itemId);
        long rejected = orderAwaitingSupplier(supplierId, itemId);
        long quotation = createOrder(supplierId, itemId, "1").get("id").asLong();
        Supplier supplier = supplierWithLogin(supplierId);
        Supplier other = supplierWithLogin();

        JsonNode waiting = data(get("/v1/supplier-portal/orders/" + accepted, supplier.token(), 200));
        assertThat(waiting.get("awaitingYourResponse").asBoolean()).isTrue();

        String respond = "/v1/supplier-portal/orders/%d/response";
        post(respond.formatted(accepted), other.token(), body("decision", "ACCEPT"), 404);
        post(respond.formatted(accepted), l2a.token(), body("decision", "ACCEPT"), 403);
        post(respond.formatted(quotation), supplier.token(), body("decision", "ACCEPT"), 409);
        post(respond.formatted(rejected), supplier.token(), body("decision", "REJECT"), 400);

        JsonNode confirmed = data(post(respond.formatted(accepted), supplier.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("orderStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("awaitingYourResponse").asBoolean()).isFalse();
        post(respond.formatted(accepted), supplier.token(), body("decision", "REJECT", "reason", "Too late"), 409);

        JsonNode cancelled = data(post(respond.formatted(rejected), supplier.token(),
                body("decision", "REJECT", "reason", "Out of stock until next month"), 200));
        assertThat(cancelled.get("orderStatus").asText()).isEqualTo("CANCELLED");
        JsonNode history = data(get("/v1/supplier-portal/orders/" + rejected, supplier.token(), 200)).get("history");
        assertThat(history.get(history.size() - 1).get("reason").asText()).isEqualTo("Out of stock until next month");
        assertThat(notificationTitles(l2a)).contains("Order Rejected by Supplier");
    }

    @Test
    void aSupplierMarksTheirConfirmedOrdersAsShipped() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();
        long orderId = orderAwaitingSupplier(supplierId, itemId);
        Supplier supplier = supplierWithLogin(supplierId);
        Supplier other = supplierWithLogin();
        String ship = "/v1/supplier-portal/orders/" + orderId + "/ship";

        post(ship, supplier.token(), body(), 409);
        JsonNode confirmed = data(post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("shippable").asBoolean()).isTrue();
        post(ship, other.token(), body(), 404);
        post(ship, l2a.token(), body(), 403);

        JsonNode shipped = data(post(ship, supplier.token(), body(), 200));
        assertThat(shipped.get("orderStatus").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.get("shippable").asBoolean()).isFalse();
        post(ship, supplier.token(), body(), 409);
        assertThat(notificationTitles(l1)).contains("Order Shipped by Supplier");
        // Staff record the delivery as usual
        assertThat(data(get("/v1/orders/" + orderId, l1.token(), 200)).get("orderStatus").asText()).isEqualTo("SHIPPED");
    }

    @Test
    void ordersOfAnEarlierRecordWithTheSameEmailBelongToTheSupplier() throws Exception {
        String email = uniq("same") + "@supplier.test";
        long category = fixtureCategory();
        Map<String, Object> first = supplierBody(List.of(category));
        first.put("email", email);
        long oldRecord = data(post("/v1/admin/suppliers", l1.token(), first, 201)).get("id").asLong();
        long orderId = orderAwaitingSupplier(oldRecord, createItem());
        delete("/v1/admin/suppliers/" + oldRecord, l2a.token(), 200);

        Map<String, Object> second = supplierBody(List.of(category));
        second.put("email", email.toUpperCase());
        long newRecord = data(post("/v1/admin/suppliers", l1.token(), second, 201)).get("id").asLong();
        Supplier supplier = supplierWithLogin(newRecord);

        JsonNode mine = data(get("/v1/supplier-portal/orders", supplier.token(), 200));
        assertThat(mine.findValuesAsText("id")).contains(String.valueOf(orderId));
        JsonNode confirmed = data(post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("orderStatus").asText()).isEqualTo("CONFIRMED");
    }
}
