package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quotation's payment plan (advance %, midterm %, the rest after testing, expected method), the payments made to the
 * supplier against it (recorded by one person, confirmed by another) and the refund claim opened when testing or a
 * cancellation leaves the supplier overpaid. The order fixture is 10 PCS x 100.00 + 50.00 shipment = 1050.00 (no GST)
 * with a 30 / 30 / 40 plan, so 315.00 advance, 315.00 midterm and 420.00 after testing.
 */
class SupplierPaymentIntegrationTest extends IntegrationTestSupport {

    @Test
    void thePlanIsPartOfTheQuotationAndItsFinalPartIsTheRest() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();

        Map<String, Object> tooMuch = orderBody(supplierId, itemId, "10");
        tooMuch.put("advancePercent", new BigDecimal("60"));
        tooMuch.put("midtermPercent", new BigDecimal("50"));
        JsonNode refused = post("/v1/orders", l1.token(), tooMuch, 400);
        assertThat(refused.get("message").asText()).contains("cannot be more than 100%");

        Map<String, Object> noMethod = orderBody(supplierId, itemId, "10");
        noMethod.remove("expectedPaymentMethod");
        assertThat(post("/v1/orders", l1.token(), noMethod, 400).get("errors").findValuesAsText("field")).contains("expectedPaymentMethod");

        JsonNode order = createOrder(supplierId, itemId, "10");
        assertThat(order.get("advancePercent").decimalValue()).isEqualByComparingTo("30");
        assertThat(order.get("finalPercent").decimalValue()).isEqualByComparingTo("40");
        assertThat(order.get("expectedPaymentMethod").asText()).isEqualTo("BANK_TRANSFER");

        long orderId = order.get("id").asLong();
        JsonNode edited = data(put("/v1/orders/" + orderId, l1.token(), body("midtermPercent", new BigDecimal("50"), "expectedPaymentMethod", "CHEQUE"), 200));
        assertThat(edited.get("finalPercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(edited.get("expectedPaymentMethod").asText()).isEqualTo("CHEQUE");
        // Either part alone is checked against the other
        put("/v1/orders/" + orderId + "", l1.token(), body("advancePercent", new BigDecimal("60")), 400);
    }

    @Test
    void aPlanCanHaveOnlyAnAdvanceAndTheFinalPayment() throws Exception {
        Map<String, Object> request = orderBody(createSupplier(), createItem(), "10");
        request.remove("midtermPercent");
        JsonNode order = data(post("/v1/orders", l1.token(), request, 201));
        assertThat(order.get("midtermPercent").decimalValue()).isEqualByComparingTo("0");
        assertThat(order.get("finalPercent").decimalValue()).isEqualByComparingTo("70");
        long orderId = order.get("id").asLong();

        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        moveOrder(orderId, l2a, "CONFIRMED");
        moveOrder(orderId, l1, "SHIPPED");

        // Shipped, yet no midterm is offered or accepted; before testing only the 30 % advance (315.00) can be paid
        JsonNode summary = summary(orderId);
        assertThat(summary.get("openMilestones").toString()).isEqualTo("[\"ADVANCE\"]");
        assertThat(summary.get("payableNow").decimalValue()).isEqualByComparingTo("315.00");
        assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "100.00"), 409).get("message").asText())
                .contains("no midterm part");
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "315.01"), 400);
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "315.00"), 201);
    }

    @Test
    void paymentsFollowThePlanAndTheFinalPartWaitsForTesting() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();
        long orderId = createOrder(supplierId, itemId, "10").get("id").asLong();

        // Still a quotation: nothing is due yet
        assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "100.00"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_NOT_DUE");

        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);

        // The supplier was given 400.00 rather than the planned 315.00: allowed, up to the advance and midterm parts
        long advanceId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "400.00"), 201)).get("id").asLong();
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary(orderId).get("pendingPayments").decimalValue()).isEqualByComparingTo("400.00");

        // Level 1 can't confirm, and nobody confirms what they recorded themselves
        patch("/v1/orders/payments/" + advanceId + "/decision", l1.token(), body("decision", "APPROVED"), 403);
        long ownId = data(post("/v1/orders/" + orderId + "/payments", l2a.token(), payment("ADVANCE", "10.00"), 201)).get("id").asLong();
        assertThat(patch("/v1/orders/payments/" + ownId + "/decision", l2a.token(), body("decision", "APPROVED"), 403).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_SELF_DECISION");
        patch("/v1/orders/payments/" + ownId + "/decision", l2b.token(), body("decision", "REJECTED"), 400);
        patch("/v1/orders/payments/" + ownId + "/decision", l2b.token(), body("decision", "REJECTED", "reason", "Duplicate"), 200);

        JsonNode confirmed = data(patch("/v1/orders/payments/" + advanceId + "/decision", l2a.token(), body("decision", "APPROVED"), 200));
        assertThat(confirmed.get("status").asText()).isEqualTo("APPROVED");
        JsonNode afterAdvance = summary(orderId);
        assertThat(afterAdvance.get("paid").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(afterAdvance.get("payableNow").decimalValue()).isEqualByComparingTo("230.00");
        assertThat(afterAdvance.get("outstanding").decimalValue()).isEqualByComparingTo("650.00");

        // The midterm part is due once shipped
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "100.00"), 409);
        moveOrder(orderId, l2a, "CONFIRMED");
        moveOrder(orderId, l1, "SHIPPED");
        JsonNode tooMuch = post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "300.00"), 400);
        assertThat(tooMuch.get("message").asText()).contains("230.00").contains("waits for testing");
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "230.00"), 201)).get("id").asLong());

        // The final part only after the delivered units are tested (stock entry approved)
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL", "100.00"), 409);
        long stockId = createStock(orderNumber, "8", "2").get("id").asLong();
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL", "100.00"), 409);
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

        // 8 passed: 8 x 100.00 + 50.00 = 850.00, of which 630.00 is paid
        JsonNode tested = summary(orderId);
        assertThat(tested.get("tested").asBoolean()).isTrue();
        assertThat(tested.get("payable").decimalValue()).isEqualByComparingTo("850.00");
        assertThat(tested.get("outstanding").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(tested.get("unitsFailed").decimalValue()).isEqualByComparingTo("2");
        assertThat(tested.get("claim").isNull()).isTrue();
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL", "220.01"), 400);
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL", "220.00"), 201)).get("id").asLong());
        assertThat(summary(orderId).get("outstanding").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void anOverpaidSupplierOwesTheDifferenceOnceUnitsFailTesting() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();
        Map<String, Object> request = orderBody(supplierId, itemId, "10");
        // Everything before testing: 50 % advance, 50 % midterm
        request.put("advancePercent", new BigDecimal("50"));
        request.put("midtermPercent", new BigDecimal("50"));
        long orderId = data(post("/v1/orders", l1.token(), request, 201)).get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "525.00"), 201)).get("id").asLong());
        moveOrder(orderId, l2a, "CONFIRMED");
        moveOrder(orderId, l1, "SHIPPED");
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "525.00"), 201)).get("id").asLong());

        // No refund is owed before testing
        post("/v1/orders/" + orderId + "/payments", l1.token(), refund("10.00"), 409);
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();
        long stockId = createStock(orderNumber, "8", "2").get("id").asLong();
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

        // Paid 1050.00 for what is worth 850.00: a claim of 200.00 opens by itself
        JsonNode claim = summary(orderId).get("claim");
        assertThat(claim.get("status").asText()).isEqualTo("OPEN");
        assertThat(claim.get("cause").asText()).isEqualTo("FAILED_TESTING");
        assertThat(claim.get("failedUnits").decimalValue()).isEqualByComparingTo("2");
        assertThat(claim.get("claimedAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(claim.get("refundDue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(notificationTitles(l1)).contains("Refund due from a supplier");

        post("/v1/orders/" + orderId + "/payments", l1.token(), refund("200.01"), 400);
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), refund("150.00"), 201)).get("id").asLong());
        assertThat(summary(orderId).get("claim").get("refundDue").decimalValue()).isEqualByComparingTo("50.00");

        // Level 2 agrees with the supplier to waive the rest: the claim is settled
        Map<String, Object> waive = body("agreedAmount", new BigDecimal("150.00"), "reason", "50.00 kept for the repaired unit");
        patch("/v1/orders/" + orderId + "/refund-claim", l1.token(), waive, 403);
        patch("/v1/orders/" + orderId + "/refund-claim", l2a.token(), body("agreedAmount", new BigDecimal("250.00"), "reason", "More"), 400);
        JsonNode settled = data(patch("/v1/orders/" + orderId + "/refund-claim", l2a.token(), waive, 200)).get("claim");
        assertThat(settled.get("status").asText()).isEqualTo("SETTLED");
        assertThat(settled.get("refundDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(settled.get("adjustReason").asText()).isEqualTo("50.00 kept for the repaired unit");
    }

    @Test
    void aConfirmedPaymentIsCorrectedByAReversal() throws Exception {
        long orderId = approvedOrder();
        long paymentId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "300.00"), 201)).get("id").asLong();
        // Waiting: only its recorder corrects or withdraws it
        put("/v1/orders/payments/" + paymentId, l2a.token(), payment("ADVANCE", "200.00"), 403);
        assertThat(data(put("/v1/orders/payments/" + paymentId, l1.token(), payment("ADVANCE", "200.00"), 200)).get("amount").decimalValue())
                .isEqualByComparingTo("200.00");
        approve(paymentId);
        put("/v1/orders/payments/" + paymentId, l1.token(), payment("ADVANCE", "250.00"), 409);

        post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", " "), 400);
        long reversalId = data(post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", "Paid to the wrong account"), 201))
                .get("id").asLong();
        assertThat(post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", "Again"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_ALREADY_REVERSED");
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("200.00");
        approve(reversalId);
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("0");

        JsonNode entries = data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("entries");
        assertThat(entries.get(0).get("reversed").asBoolean()).isTrue();
        assertThat(entries.get(1).get("kind").asText()).isEqualTo("REVERSAL");

        // A withdrawn entry is gone from the queue
        long withdrawnId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "50.00"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + withdrawnId + "/withdraw", l2a.token(), null, 403);
        patch("/v1/orders/payments/" + withdrawnId + "/withdraw", l1.token(), null, 200);
        patch("/v1/orders/payments/" + withdrawnId + "/decision", l2a.token(), body("decision", "APPROVED"), 409);
        assertThat(data(get("/v1/orders/payments", l2a.token(), 200)).findValuesAsText("id")).doesNotContain(String.valueOf(withdrawnId));
    }

    @Test
    void anAdvancePaidForACancelledOrderIsDueBack() throws Exception {
        long orderId = approvedOrder();
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "100.00"), 201)).get("id").asLong());
        patch("/v1/orders/" + orderId + "/status", l2a.token(), body("status", "CANCELLED", "reason", "Supplier can't deliver"), 200);

        JsonNode summary = summary(orderId);
        assertThat(summary.get("payable").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("openMilestones").size()).isZero();
        assertThat(summary.get("claim").get("cause").asText()).isEqualTo("ORDER_CANCELLED");
        assertThat(summary.get("claim").get("refundDue").decimalValue()).isEqualByComparingTo("100.00");
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "10.00"), 409);
    }

    @Test
    void everyoneWhoSeesOrdersSeesThePaymentsButOnlyPaymentStaffRecordThem() throws Exception {
        long orderId = approvedOrder();
        String username = uniq("auditor");
        long userId = insertUser(username, "ADMIN_L2", "WEB", false, true);
        insertEmployee(userId, "FINANCIAL_AUDITOR");
        String auditor = login(username, SEED_PASSWORD);
        get("/v1/orders/" + orderId + "/payments", auditor, 200);
        post("/v1/orders/" + orderId + "/payments", auditor, payment("ADVANCE", "10.00"), 403);
        get("/v1/orders/payments", auditor, 403);
    }

    private long approvedOrder() throws Exception {
        long orderId = createOrder(createSupplier(), createItem(), "10").get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        return orderId;
    }

    private void approve(long paymentId) throws Exception {
        patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
    }

    private JsonNode summary(long orderId) throws Exception {
        return data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("summary");
    }

    private static Map<String, Object> payment(String milestone, String amount) {
        return body("kind", "PAYMENT", "milestone", milestone, "amount", new BigDecimal(amount), "method", "BANK_TRANSFER",
                "reference", "UTR-" + amount, "paidOn", LocalDate.now().toString());
    }

    private static Map<String, Object> refund(String amount) {
        return body("kind", "REFUND", "amount", new BigDecimal(amount), "method", "ONLINE", "paidOn", LocalDate.now().toString());
    }
}
