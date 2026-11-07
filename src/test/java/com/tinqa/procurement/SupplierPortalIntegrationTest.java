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

    private Map<String, Object> quotation(long itemId) {
        return body("itemId", itemId, "orderQuantity", new BigDecimal("10"), "unitType", "PCS",
                "unitPrice", new BigDecimal("100.00"), "shipmentPrice", new BigDecimal("50.00"), "note", "Ex-works Pune",
                "priceChangeReason", "Bulk rate", "advancePercent", new BigDecimal("30"), "midtermPercent", new BigDecimal("20"),
                "expectedPaymentMethod", "CHEQUE");
    }

    @Test
    void aPriceOtherThanTheItemsMrpNeedsAReason() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
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
    void aStaffEditToTheUnitPriceIsJustifiedAgainAndKeepsTheLastQuotedPrice() throws Exception {
        long orderId = createOrder(createSupplier(), createItem(), "10").get("id").asLong();
        JsonNode before = data(get("/v1/orders/" + orderId, l1.token(), 200));
        BigDecimal quoted = before.get("unitPrice").decimalValue();

        // Another price than the MRP without a reason is refused
        put("/v1/orders/" + orderId, l1.token(), body("unitPrice", new BigDecimal("1600.00"), "additionalInfo", body()), 400);
        JsonNode edited = data(put("/v1/orders/" + orderId, l1.token(), body("unitPrice", new BigDecimal("1600.00"),
                "additionalInfo", body("priceChangeReason", "Freight costs went up")), 200));
        JsonNode info = edited.get("additionalInfo");
        assertThat(info.get("priceChangeReason").asText()).isEqualTo("Freight costs went up");
        assertThat(info.get("mrp").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(info.get("previousUnitPrice").decimalValue()).isEqualByComparingTo(quoted);

        // Back at the MRP nothing needs justifying; the last quoted price stays for approvers
        JsonNode atMrp = data(put("/v1/orders/" + orderId, l1.token(), body("unitPrice", new BigDecimal("1500.00")), 200)).get("additionalInfo");
        assertThat(atMrp.has("priceChangeReason")).isFalse();
        assertThat(atMrp.get("previousUnitPrice").decimalValue()).isEqualByComparingTo("1600.00");
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
        SupplierLogin supplier = supplierWithLogin();
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
        SupplierLogin supplier = supplierWithLogin();
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
        SupplierLogin supplier = supplierWithLogin();
        long itemId = createItem();
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(itemId), 201)).get("id").asLong();

        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("unitPrice", new BigDecimal("100.00")), 409);
        // A new unit price always needs the supplier's reason
        JsonNode noReason = post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("unitPrice", new BigDecimal("90.00")), 400);
        assertThat(noReason.get("errors").findValuesAsText("field")).containsExactly("reason");
        JsonNode change = data(post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("unitPrice", new BigDecimal("90.00"), "reason", "Bulk discount"), 201));
        assertThat(change.get("status").asText()).isEqualTo("PENDING");
        assertThat(change.get("proposedTotalPrice").decimalValue()).isEqualByComparingTo("950.00");
        // Approvers compare the new price with the item's own price and the last quoted one
        JsonNode pending = data(get("/v1/orders/change-requests?status=PENDING", l1.token(), 200));
        JsonNode listed = java.util.stream.StreamSupport.stream(pending.spliterator(), false)
                .filter(node -> node.get("id").asLong() == change.get("id").asLong()).findFirst().orElseThrow();
        assertThat(listed.get("itemPrice").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(listed.get("previous").get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(listed.get("note").asText()).isEqualTo("Bulk discount");

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
        // The supplier's reason is now the quotation's price reason, beside the item's MRP and the price it replaced
        JsonNode info = order.get("additionalInfo");
        assertThat(info.get("priceChangeReason").asText()).isEqualTo("Bulk discount");
        assertThat(info.get("mrp").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(info.get("previousUnitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(order.get("supplierChangePending").asBoolean()).isFalse();
        assertThat(notificationTitles(supplier.actor()))
                .contains("Quotation change approved");
        // The supplier's own approved change is their word, not something TinQa changed
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId, supplier.token(), 200)).get("changedByTinqa")).isEmpty();

        // Once staff submit the quotation it is an order, and the supplier can no longer change it
        moveOrder(orderId, l1, "PENDING");
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(), body("orderQuantity", 20), 409);
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId, supplier.token(), 200)).get("changeable").asBoolean()).isFalse();
    }

    @Test
    void theSupplierSeesWhatTinqaChangedAndMustAgreeToItBeforeAccepting() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
        // Quoted: 30 % advance, 20 % midterm, 50 % after testing, paid by cheque
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(createItem()), 201)).get("id").asLong();
        String portalOrder = "/v1/supplier-portal/orders/" + orderId;
        assertThat(data(get(portalOrder, supplier.token(), 200)).get("changedByTinqa")).isEmpty();

        // Staff lower the advance and change the method while reviewing, then approve it
        put("/v1/orders/" + orderId, l1.token(), body("advancePercent", new BigDecimal("20"), "expectedPaymentMethod", "BANK_TRANSFER"), 200);
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);

        // The supplier sees each change: what they quoted beside what it is now, in the list and the order
        JsonNode changed = data(get(portalOrder, supplier.token(), 200)).get("changedByTinqa");
        assertThat(changed.findValuesAsText("field")).containsExactly("ADVANCE_PERCENT", "FINAL_PERCENT", "PAYMENT_METHOD");
        assertThat(changed.get(0).get("quoted").asText()).isEqualTo("30%");
        assertThat(changed.get(0).get("current").asText()).isEqualTo("20%");
        assertThat(changed.get(1).get("current").asText()).isEqualTo("60%");
        assertThat(changed.get(2).get("quoted").asText()).isEqualTo("Cheque");
        JsonNode listed = java.util.stream.StreamSupport.stream(data(get("/v1/supplier-portal/orders", supplier.token(), 200)).spliterator(), false)
                .filter(node -> node.get("id").asLong() == orderId).findFirst().orElseThrow();
        assertThat(listed.get("changedByTinqa")).hasSize(3);

        // Staff can't agree to the changes on the supplier's behalf
        JsonNode forSupplier = patch("/v1/orders/" + orderId + "/status", l2a.token(), body("status", "CONFIRMED"), 409);
        assertThat(forSupplier.get("errorCode").asText()).isEqualTo("ORDER_NEEDS_SUPPLIER_CONSENT");

        // Accepting needs the supplier's agreement to the changes, which the order's history keeps
        String respond = portalOrder + "/response";
        JsonNode refused = post(respond, supplier.token(), body("decision", "ACCEPT"), 400);
        assertThat(refused.get("errors").findValuesAsText("field")).containsExactly("changesAccepted");
        post(respond, supplier.token(), body("decision", "ACCEPT", "changesAccepted", false), 400);
        JsonNode confirmed = data(post(respond, supplier.token(), body("decision", "ACCEPT", "changesAccepted", true), 200));
        assertThat(confirmed.get("orderStatus").asText()).isEqualTo("CONFIRMED");
        JsonNode history = data(get(portalOrder, supplier.token(), 200)).get("history");
        assertThat(history.get(history.size() - 1).get("reason").asText())
                .startsWith("Agreed to TinQa's changes: Advance payment 30% → 20%");
    }

    @Test
    void staffStillConfirmForASupplierWhoseQuotationTheyPrepared() throws Exception {
        long orderId = orderAwaitingSupplier(createSupplier(), createItem());
        put("/v1/orders/" + orderId, l1.token(), body("advancePercent", new BigDecimal("10")), 409);
        // Nothing the supplier quoted to compare with: confirming for them works as before
        assertThat(data(patch("/v1/orders/" + orderId + "/status", l2a.token(), body("status", "CONFIRMED"), 200))
                .get("orderStatus").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void theSupplierProposesTheirPaymentPlanAndSeesOnlyConfirmedPayments() throws Exception {
        // (A payment's amount is the plan's: left out, it is filled in)
        SupplierLogin supplier = supplierWithLogin();
        JsonNode quote = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(createItem()), 201));
        assertThat(quote.get("finalPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(quote.get("expectedPaymentMethod").asText()).isEqualTo("CHEQUE");
        long orderId = quote.get("id").asLong();

        // More than 50 % before testing is refused; a new plan is a change any Supplier Manager approves
        post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(), body("advancePercent", 80), 400);
        long changeId = data(post("/v1/supplier-portal/quotations/" + orderId + "/change-requests", supplier.token(),
                body("advancePercent", 20, "expectedPaymentMethod", "BANK_TRANSFER"), 201)).get("id").asLong();
        assertThat(notificationTitles(l1)).contains("Supplier quotation change awaiting approval");
        patch("/v1/orders/change-requests/" + changeId + "/decision", l1.token(), body("decision", "APPROVED"), 200);
        JsonNode changed = data(get("/v1/orders/" + orderId, l1.token(), 200));
        assertThat(changed.get("advancePercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(changed.get("finalPercent").decimalValue()).isEqualByComparingTo("60");
        assertThat(changed.get("expectedPaymentMethod").asText()).isEqualTo("BANK_TRANSFER");

        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        // The advance is paid once the supplier has accepted the order
        post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200);
        Map<String, Object> advance = body("kind", "PAYMENT", "milestone", "ADVANCE", "method", "BANK_TRANSFER");
        JsonNode recorded = data(post("/v1/orders/" + orderId + "/payments", l1.token(), advance, 201));
        long recordedId = recorded.get("id").asLong();
        // Requested, then approved by a Super Admin: nothing is paid yet, so the supplier doesn't see it
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("entries")).isEmpty();
        assertThat(data(patch("/v1/orders/payments/" + recordedId + "/decision", l2a.token(), body("decision", "APPROVED"), 200))
                .get("status").asText()).isEqualTo("TO_PAY");
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("entries")).isEmpty();
        patch("/v1/orders/payments/" + recordedId + "/paid", l1.token(), body("reference", "UTR-1", "paidOn", java.time.LocalDate.now().toString()), 200);

        // Paid: it waits for the supplier to say it reached them
        JsonNode view = data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200));
        assertThat(view.get("summary").get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(view.get("entries")).hasSize(1);
        assertThat(view.get("entries").get(0).get("status").asText()).isEqualTo("AWAITING_SUPPLIER");
        assertThat(view.get("entries").get(0).get("recordedByUsername").isNull()).isTrue();
        assertThat(view.get("entries").get(0).get("paidByUsername").isNull()).isTrue();
        // Only the steps from the payment on: never the request or its approval
        assertThat(view.get("entries").get(0).get("events").findValuesAsText("type")).containsExactly("PAID");
        assertThat(notificationTitles(supplier.actor())).contains("Confirm the payment for order " + quote.get("orderNumber").asText());
        assertThat(paymentsToConfirm(supplier, orderId)).isEqualTo(1);
        patch("/v1/supplier-portal/payments/" + recordedId + "/receipt", supplier.token(), body("received", true), 200);
        JsonNode paid = data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200));
        assertThat(paid.get("summary").get("paid").decimalValue()).isEqualByComparingTo(recorded.get("amount").decimalValue());
        assertThat(paymentsToConfirm(supplier, orderId)).isZero();
        assertThat(notificationTitles(l1)).contains("Supplier confirmed a payment");
        // Another supplier's order is not found
        get("/v1/supplier-portal/orders/" + orderId + "/payments", supplierWithLogin().token(), 404);
    }

    // orderAcceptedBySupplier: 5 PCS x 100.00 + 50.00 = 550.00 on a 30 / 20 / 50 plan, so the advance is 165.00
    @Test
    void aCashPaymentTheSupplierDidNotReceiveIsSentAgainUntilTheyConfirmIt() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
        long orderId = orderAcceptedBySupplier(supplier, createItem());
        String orderNumber = data(get("/v1/orders/" + orderId, l1.token(), 200)).get("orderNumber").asText();
        Map<String, Object> cash = body("kind", "PAYMENT", "milestone", "ADVANCE", "method", "CASH");

        // First the cash never reached them: the supplier says so, with a reason, and it goes back to finance
        long cashId = cashSentToSupplier(orderId, cash);
        patch("/v1/supplier-portal/payments/" + cashId + "/receipt", supplier.token(), body("received", false), 400);
        // Another supplier's payment looks like one that doesn't exist
        patch("/v1/supplier-portal/payments/" + cashId + "/receipt", supplierWithLogin().token(), body("received", true), 404);
        // Staff can't answer for the supplier
        patch("/v1/supplier-portal/payments/" + cashId + "/receipt", l2a.token(), body("received", true), 403);
        JsonNode lost = data(patch("/v1/supplier-portal/payments/" + cashId + "/receipt", supplier.token(),
                body("received", false, "reason", "Nobody came"), 200));
        assertThat(lost.get("status").asText()).isEqualTo("NOT_RECEIVED");
        assertThat(lost.get("supplierResponseReason").asText()).isEqualTo("Nobody came");
        // Still held against the advance while it is checked
        assertThat(paymentSummary(orderId).get("pendingPayments").decimalValue()).isEqualByComparingTo("165.00");
        assertThat(paymentSummary(orderId).get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(notificationTitles(l1)).contains("Supplier did not receive a payment");
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("entries")
                .findValuesAsText("status")).containsExactly("NOT_RECEIVED");
        // A staff member can't decide it again
        patch("/v1/orders/payments/" + cashId + "/decision", l2b.token(), body("decision", "APPROVED"), 409);

        // Finance checks it and sends it again (cash needs no reference); it waits for the supplier again
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/v1/orders/payments/" + cashId + "/resend")
                .param("paidOn", java.time.LocalDate.now().toString()).param("note", "Handed over again at the gate")
                .header("Authorization", "Bearer " + l1.token()), 200);
        assertThat(notificationTitles(supplier.actor())).contains("Confirm the payment for order " + orderNumber);
        assertThat(paymentsToConfirm(supplier, orderId)).isEqualTo(1);
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId, supplier.token(), 200)).get("paymentsToConfirm").asInt()).isEqualTo(1);

        JsonNode received = data(patch("/v1/supplier-portal/payments/" + cashId + "/receipt", supplier.token(), body("received", true), 200));
        assertThat(received.get("status").asText()).isEqualTo("APPROVED");
        assertThat(received.get("supplierRespondedAt").isNull()).isFalse();
        assertThat(received.get("sendCount").asInt()).isEqualTo(2);
        assertThat(paymentSummary(orderId).get("paid").decimalValue()).isEqualByComparingTo("165.00");
        assertThat(paymentsToConfirm(supplier, orderId)).isZero();
        assertThat(notificationTitles(l1)).contains("Supplier confirmed a payment");
        patch("/v1/supplier-portal/payments/" + cashId + "/receipt", supplier.token(), body("received", true), 409);
    }

    @Test
    void theRequesterWithdrawsAPaymentOnlyWhileItWaitsOnTinqa() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
        long orderId = orderAcceptedBySupplier(supplier, createItem());
        Map<String, Object> cash = body("kind", "PAYMENT", "milestone", "ADVANCE", "method", "CASH");
        // With the supplier, it can't be taken back
        long sentId = cashSentToSupplier(orderId, cash);
        patch("/v1/orders/payments/" + sentId + "/withdraw", l1.token(), null, 409);
        patch("/v1/supplier-portal/payments/" + sentId + "/receipt", supplier.token(), body("received", false, "reason", "Never came"), 200);
        // Sent back by the supplier, it can: only by its requester
        patch("/v1/orders/payments/" + sentId + "/withdraw", l2a.token(), null, 403);
        assertThat(data(patch("/v1/orders/payments/" + sentId + "/withdraw", l1.token(), null, 200)).get("status").asText()).isEqualTo("WITHDRAWN");
        patch("/v1/supplier-portal/payments/" + sentId + "/receipt", supplier.token(), body("received", true), 409);
        assertThat(paymentSummary(orderId).get("pendingPayments").decimalValue()).isEqualByComparingTo("0");
    }

    // Requested (l1), approved by a Super Admin (l2a) and paid (l1): it then waits for the supplier
    private long cashSentToSupplier(long orderId, Map<String, Object> cash) throws Exception {
        long id = data(post("/v1/orders/" + orderId + "/payments", l1.token(), cash, 201)).get("id").asLong();
        assertThat(data(patch("/v1/orders/payments/" + id + "/decision", l2a.token(), body("decision", "APPROVED"), 200))
                .get("status").asText()).isEqualTo("TO_PAY");
        JsonNode paid = data(patch("/v1/orders/payments/" + id + "/paid", l1.token(), body("paidOn", java.time.LocalDate.now().toString()), 200));
        assertThat(paid.get("status").asText()).isEqualTo("AWAITING_SUPPLIER");
        return id;
    }

    private long orderAcceptedBySupplier(SupplierLogin supplier, long itemId) throws Exception {
        long orderId = orderAwaitingSupplier(supplier.supplierId(), itemId);
        post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200);
        return orderId;
    }

    private JsonNode paymentSummary(long orderId) throws Exception {
        return data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("summary");
    }

    private int paymentsToConfirm(SupplierLogin supplier, long orderId) throws Exception {
        for (JsonNode order : data(get("/v1/supplier-portal/orders", supplier.token(), 200))) {
            if (order.get("id").asLong() == orderId) {
                return order.get("paymentsToConfirm").asInt();
            }
        }
        throw new AssertionError("Order " + orderId + " is not in the supplier's list");
    }

    @Test
    void aSupplierCanWithdrawTheirChange() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
        SupplierLogin other = supplierWithLogin();
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
        SupplierLogin supplier = supplierWithLogin();
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
        SupplierLogin supplier = supplierWithLogin(supplierId);
        SupplierLogin other = supplierWithLogin();

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
        SupplierLogin supplier = supplierWithLogin(supplierId);
        SupplierLogin other = supplierWithLogin();
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
        SupplierLogin supplier = supplierWithLogin(newRecord);

        JsonNode mine = data(get("/v1/supplier-portal/orders", supplier.token(), 200));
        assertThat(mine.findValuesAsText("id")).contains(String.valueOf(orderId));
        JsonNode confirmed = data(post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200));
        assertThat(confirmed.get("orderStatus").asText()).isEqualTo("CONFIRMED");
    }
}
