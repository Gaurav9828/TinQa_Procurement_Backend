package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import com.tinqa.procurement.support.InvoicePdfs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quotation's payment plan (advance %, midterm %, the rest after testing, expected method), the payments made to the
 * supplier against it (recorded by one person, confirmed by another) and the refund claim opened when testing or a
 * cancellation leaves the supplier overpaid. The order fixture is 10 PCS x 100.00 + 50.00 shipment = 1050.00 (no GST)
 * with a 30 / 30 / 40 plan, so 315.00 advance, 315.00 midterm and 420.00 after testing. A payment's amount is set by the
 * plan: what is left of its part, so staff pick the part and the system fills in the amount. A payment is requested
 * (l1, holding SUPPLIER_PAYMENT_RECORD), approved by a Super Admin (l2b), paid with its reference (l1), and counts only
 * once the supplier confirms in their login that they received it.
 */
class SupplierPaymentIntegrationTest extends IntegrationTestSupport {

    // The supplier being paid in the current test (each test gets a new instance)
    private SupplierLogin supplier;

    @Test
    void thePlanIsPartOfTheQuotationAndItsFinalPartIsTheRest() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();

        // At least half is always paid after delivery and testing: 80 % up front is refused
        Map<String, Object> tooMuch = orderBody(supplierId, itemId, "10");
        tooMuch.put("advancePercent", new BigDecimal("80"));
        tooMuch.put("midtermPercent", BigDecimal.ZERO);
        JsonNode refused = post("/v1/orders", l1.token(), tooMuch, 400);
        assertThat(refused.get("message").asText()).contains("at most 50%");
        assertThat(refused.get("errors").findValuesAsText("field")).contains("advancePercent");
        tooMuch.put("advancePercent", new BigDecimal("30"));
        tooMuch.put("midtermPercent", new BigDecimal("30"));
        assertThat(post("/v1/orders", l1.token(), tooMuch, 400).get("errors").findValuesAsText("field")).contains("midtermPercent");

        Map<String, Object> noMethod = orderBody(supplierId, itemId, "10");
        noMethod.remove("expectedPaymentMethod");
        assertThat(post("/v1/orders", l1.token(), noMethod, 400).get("errors").findValuesAsText("field")).contains("expectedPaymentMethod");

        JsonNode order = createOrder(supplierId, itemId, "10");
        assertThat(order.get("advancePercent").decimalValue()).isEqualByComparingTo("30");
        assertThat(order.get("finalPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(order.get("expectedPaymentMethod").asText()).isEqualTo("BANK_TRANSFER");

        long orderId = order.get("id").asLong();
        JsonNode edited = data(put("/v1/orders/" + orderId, l1.token(), body("midtermPercent", new BigDecimal("10"), "expectedPaymentMethod", "CHEQUE"), 200));
        assertThat(edited.get("finalPercent").decimalValue()).isEqualByComparingTo("60");
        assertThat(edited.get("expectedPaymentMethod").asText()).isEqualTo("CHEQUE");
        // Either part alone is checked against the other
        put("/v1/orders/" + orderId + "", l1.token(), body("advancePercent", new BigDecimal("45")), 400);
    }

    @Test
    void aPlanCanHaveOnlyAnAdvanceAndTheFinalPayment() throws Exception {
        Map<String, Object> request = orderBody(paidSupplier(), createItem(), "10");
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
        long supplierId = paidSupplier();
        long itemId = createItem();
        long orderId = createOrder(supplierId, itemId, "10").get("id").asLong();

        // Still a quotation: nothing is due yet
        assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_NOT_DUE");

        // Approved, but the supplier has not accepted the order yet: the advance is not due
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        assertThat(milestone(summary(orderId), "ADVANCE").get("due").asBoolean()).isFalse();
        assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 409).get("message").asText())
                .contains("advance can be paid once");
        accept(orderId);
        JsonNode accepted = summary(orderId);
        assertThat(milestone(accepted, "ADVANCE").get("left").decimalValue()).isEqualByComparingTo("315.00");
        assertThat(milestone(accepted, "FINAL").get("left").decimalValue()).isEqualByComparingTo("0");

        // The amount is the plan's, not typed: another amount is refused, none is filled in
        JsonNode typed = post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "400.00"), 400);
        assertThat(typed.get("message").asText()).contains("set by the payment plan").contains("315.00");

        // Level 1 can't approve, and nobody approves what they requested themselves
        long ownId = data(post("/v1/orders/" + orderId + "/payments", l2a.token(), payment("ADVANCE"), 201)).get("id").asLong();
        assertThat(patch("/v1/orders/payments/" + ownId + "/decision", l2a.token(), body("decision", "APPROVED"), 403).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_SELF_DECISION");
        patch("/v1/orders/payments/" + ownId + "/decision", l1.token(), body("decision", "APPROVED"), 403);
        // While it waits, the advance part has nothing left
        assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 409).get("message").asText())
                .contains("Nothing is left to pay on the advance part");
        patch("/v1/orders/payments/" + ownId + "/decision", l2b.token(), body("decision", "REJECTED"), 400);
        JsonNode rejected = data(patch("/v1/orders/payments/" + ownId + "/decision", l2b.token(), body("decision", "REJECTED", "reason", "Duplicate"), 200));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        // A rejected request stops there: it can't be paid
        patch("/v1/orders/payments/" + ownId + "/paid", l1.token(), paid("UTR-X"), 409);

        // A request has no reference or date: they come when it is paid
        JsonNode advance = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201));
        assertThat(advance.get("amount").decimalValue()).isEqualByComparingTo("315.00");
        assertThat(advance.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(advance.get("reference").isNull()).isTrue();
        assertThat(advance.get("paidOn").isNull()).isTrue();
        assertThat(notificationTitles(l2b)).contains("Supplier payment request to approve");
        long advanceId = advance.get("id").asLong();
        // Nothing can be paid before a Super Admin approves
        assertThat(patch("/v1/orders/payments/" + advanceId + "/paid", l1.token(), paid("UTR-ADVANCE"), 409).get("message").asText())
                .contains("Super Admin");

        JsonNode toPay = data(patch("/v1/orders/payments/" + advanceId + "/decision", l2b.token(), body("decision", "APPROVED"), 200));
        assertThat(toPay.get("status").asText()).isEqualTo("TO_PAY");
        assertThat(notificationTitles(l1)).contains("Payment request approved");
        assertThat(data(get("/v1/orders/payments/to-make", l1.token(), 200)).findValuesAsText("id")).contains(String.valueOf(advanceId));
        assertThat(summary(orderId).get("pendingPayments").decimalValue()).isEqualByComparingTo("315.00");

        JsonNode sent = data(patch("/v1/orders/payments/" + advanceId + "/paid", l1.token(), paid("UTR-ADVANCE"), 200));
        assertThat(sent.get("status").asText()).isEqualTo("AWAITING_SUPPLIER");
        assertThat(sent.get("reference").asText()).isEqualTo("UTR-ADVANCE");
        assertThat(sent.get("paidByUsername").asText()).isEqualTo(l1.username());
        assertThat(sent.get("sendCount").asInt()).isEqualTo(1);
        // Top-level entry ids only: a nested id may happen to be equal
        assertThat(java.util.stream.StreamSupport.stream(data(get("/v1/orders/payments/to-make", l1.token(), 200)).spliterator(), false)
                .map(queued -> queued.get("id").asLong())).doesNotContain(advanceId);
        JsonNode awaiting = summary(orderId);
        assertThat(awaiting.get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(awaiting.get("awaitingSupplier").decimalValue()).isEqualByComparingTo("315.00");
        assertThat(notificationTitles(supplier.actor())).anyMatch(title -> title.startsWith("Confirm the payment for order"));
        // It can't be withdrawn while the supplier holds it, nor paid twice
        patch("/v1/orders/payments/" + advanceId + "/withdraw", l1.token(), null, 409);
        patch("/v1/orders/payments/" + advanceId + "/paid", l1.token(), paid("UTR-ADVANCE"), 409);
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 409);
        JsonNode received = data(patch("/v1/supplier-portal/payments/" + advanceId + "/receipt", supplier.token(), body("received", true), 200));
        assertThat(received.get("status").asText()).isEqualTo("APPROVED");
        JsonNode afterAdvance = summary(orderId);
        assertThat(afterAdvance.get("paid").decimalValue()).isEqualByComparingTo("315.00");
        assertThat(afterAdvance.get("outstanding").decimalValue()).isEqualByComparingTo("735.00");
        // No breakdown before testing: nothing has been taken off yet
        assertThat(afterAdvance.get("settlement").isNull()).isTrue();
        assertThat(milestone(afterAdvance, "ADVANCE").get("left").decimalValue()).isEqualByComparingTo("0");

        // Every step is kept, with who took it
        JsonNode entry = entry(orderId, advanceId);
        assertThat(entry.get("events").findValuesAsText("type")).containsExactly("REQUESTED", "REQUEST_APPROVED", "PAID", "RECEIVED");
        assertThat(entry.get("events").get(1).get("actorRole").asText()).isEqualTo("Super Admin");
        assertThat(entry.get("events").get(2).get("reference").asText()).isEqualTo("UTR-ADVANCE");
        assertThat(entry.get("events").get(3).get("actorRole").asText()).isEqualTo("Supplier");
        assertThat(entry.get("events").get(3).get("actorName").isNull()).isTrue();

        // The midterm part is due once shipped
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM"), 409);
        moveOrder(orderId, l1, "SHIPPED");
        assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "200.00"), 400).get("message").asText()).contains("210.00");
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "210.00"), 201)).get("id").asLong());

        // The final part only after the delivered units are tested (stock entry approved)
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL"), 409);
        long stockId = createStock(orderNumber, "8", "2").get("id").asLong();
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL"), 409);
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

        // 8 passed: 8 x 100.00 + 50.00 = 850.00, of which 525.00 is paid; the final part is what is left
        JsonNode tested = summary(orderId);
        assertThat(tested.get("tested").asBoolean()).isTrue();
        assertThat(tested.get("payable").decimalValue()).isEqualByComparingTo("850.00");
        assertThat(tested.get("outstanding").decimalValue()).isEqualByComparingTo("325.00");
        assertThat(tested.get("unitsFailed").decimalValue()).isEqualByComparingTo("2");
        assertThat(tested.get("claim").isNull()).isTrue();
        // The breakdown says why: 2 failed x 100.00 taken off the quoted 1050.00
        JsonNode settlement = tested.get("settlement");
        assertThat(settlement.get("orderedUnits").decimalValue()).isEqualByComparingTo("10");
        assertThat(settlement.get("passedUnits").decimalValue()).isEqualByComparingTo("8");
        assertThat(settlement.get("failedUnits").decimalValue()).isEqualByComparingTo("2");
        assertThat(settlement.get("shortUnits").decimalValue()).isEqualByComparingTo("0");
        assertThat(settlement.get("orderValue").decimalValue()).isEqualByComparingTo("1050.00");
        assertThat(settlement.get("failedValue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(settlement.get("gstReduction").decimalValue()).isEqualByComparingTo("0");
        assertThat(settlement.get("reduction").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(settlement.get("payable").decimalValue()).isEqualByComparingTo(tested.get("payable").decimalValue());
        assertThat(milestone(tested, "FINAL").get("left").decimalValue()).isEqualByComparingTo("325.00");
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL", "325.01"), 400);
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL"), 201)).get("id").asLong());
        assertThat(summary(orderId).get("outstanding").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void everyMethodButCashNeedsItsReferenceWhenPaid() throws Exception {
        long orderId = approvedOrder();
        for (String method : new String[] {"ONLINE", "BANK_TRANSFER", "CHEQUE"}) {
            Map<String, Object> request = payment("ADVANCE");
            request.put("method", method);
            long paymentId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), request, 201)).get("id").asLong();
            patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
            Map<String, Object> blank = paid(" ");
            assertThat(patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), blank, 400).get("errors").findValuesAsText("field"))
                    .containsExactly("reference");
            blank.remove("reference");
            patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), blank, 400);
            Map<String, Object> noDate = paid("REF-1");
            noDate.remove("paidOn");
            patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), noDate, 400);
            Map<String, Object> future = paid("REF-1");
            future.put("paidOn", LocalDate.now().plusDays(2).toString());
            patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), future, 400);
            patch("/v1/orders/payments/" + paymentId + "/withdraw", l1.token(), null, 200);
        }
        // Cash needs none
        Map<String, Object> cash = payment("ADVANCE");
        cash.put("method", "CASH");
        long cashId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), cash, 201)).get("id").asLong();
        patch("/v1/orders/payments/" + cashId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        Map<String, Object> noReference = paid("x");
        noReference.remove("reference");
        assertThat(data(patch("/v1/orders/payments/" + cashId + "/paid", l1.token(), noReference, 200)).get("status").asText())
                .isEqualTo("AWAITING_SUPPLIER");
        // Money the supplier pays back keeps its reference on record too (they enter it in their portal)
        assertThat(perform(MockMvcRequestBuilders.multipart("/v1/supplier-portal/orders/" + orderId + "/repayments")
                .param("amount", "10.00").param("method", "ONLINE").param("paidOn", LocalDate.now().toString())
                .header("Authorization", "Bearer " + supplier.token()), 400).get("message").asText()).contains("transaction ID");
    }

    @Test
    void everyPaymentNeedsASupplierLoginBecauseTheSupplierConfirmsReceipt() throws Exception {
        // A supplier without a login could never confirm a payment, whatever its method, so none can be requested
        long orderId = approvedOrder(createSupplier());
        Map<String, Object> cash = payment("ADVANCE");
        cash.put("method", "CASH");
        for (Map<String, Object> request : List.of(cash, payment("ADVANCE"))) {
            assertThat(post("/v1/orders/" + orderId + "/payments", l1.token(), request, 409).get("errorCode").asText())
                    .isEqualTo("SUPPLIER_PAYMENT_NEEDS_LOGIN");
        }
        assertThat(summary(orderId).get("pendingPayments").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aPaymentTheSupplierDidNotReceiveIsCheckedAndSentAgainWithAProof() throws Exception {
        long orderId = approvedOrder();
        long paymentId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        // A proof is only for a payment sent again, never the first time
        resend(paymentId, "UTR-1", "Checked", proofPdf(), 409);
        patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), paid("UTR-1"), 200);
        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", false), 400);
        JsonNode lost = data(patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(),
                body("received", false, "reason", "No credit for UTR-1"), 200));
        assertThat(lost.get("status").asText()).isEqualTo("NOT_RECEIVED");
        assertThat(notificationTitles(l1)).contains("Supplier did not receive a payment");
        assertThat(notificationTitles(l2a)).contains("Supplier payment to check");
        assertThat(data(get("/v1/orders/payments/to-make", l1.token(), 200)).findValuesAsText("id")).contains(String.valueOf(paymentId));
        // Still on its way: nothing paid, and its part stays held
        JsonNode summary = summary(orderId);
        assertThat(summary.get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(milestone(summary, "ADVANCE").get("left").decimalValue()).isEqualByComparingTo("0");
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 409);
        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", true), 409);

        // The Finance Manager says what they checked; the reference is still required
        resend(paymentId, "UTR-1", " ", null, 400);
        resend(paymentId, " ", "Bank confirms the credit", null, 400);
        byte[] notAProof = "hello".getBytes();
        perform(MockMvcRequestBuilders.multipart("/v1/orders/payments/" + paymentId + "/resend")
                .file(new MockMultipartFile("proof", "notes.txt", "text/plain", notAProof))
                .param("reference", "UTR-1").param("paidOn", LocalDate.now().toString()).param("note", "Checked")
                .header("Authorization", "Bearer " + l1.token()), 400);
        JsonNode again = data(resend(paymentId, "UTR-1", "Bank confirms the credit on " + LocalDate.now(), proofPdf(), 200));
        assertThat(again.get("status").asText()).isEqualTo("AWAITING_SUPPLIER");
        assertThat(again.get("sendCount").asInt()).isEqualTo(2);
        JsonNode resent = again.get("events").get(again.get("events").size() - 1);
        assertThat(resent.get("type").asText()).isEqualTo("RESENT");
        assertThat(resent.get("proofFileName").asText()).isEqualTo("bank_statement.pdf");
        long proofId = resent.get("proofDocumentId").asLong();

        // Staff and the supplier can open the proof; another supplier can't
        assertThat(download("/v1/orders/payments/" + paymentId + "/proofs/" + proofId, l1.token(), 200)).startsWith("%PDF");
        JsonNode supplierView = data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("entries").get(0);
        assertThat(supplierView.get("events").findValuesAsText("type")).containsExactly("PAID", "NOT_RECEIVED", "RESENT");
        supplierView.get("events").forEach(event -> assertThat(event.get("actorName").isNull()).isTrue());
        assertThat(supplierView.get("events").get(2).get("note").asText()).startsWith("Bank confirms the credit");
        assertThat(download("/v1/supplier-portal/payments/" + paymentId + "/proofs/" + proofId, supplier.token(), 200)).startsWith("%PDF");
        download("/v1/supplier-portal/payments/" + paymentId + "/proofs/" + proofId, supplierWithLogin().token(), 404);
        download("/v1/orders/payments/" + paymentId + "/proofs/" + (proofId + 1000), l1.token(), 404);

        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", true), 200);
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("315.00");
    }

    @Test
    void anApprovedRequestIsNotPaidOnceTheOrderIsCancelled() throws Exception {
        long orderId = approvedOrder();
        long paymentId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        patch("/v1/orders/" + orderId + "/status", l2a.token(), body("status", "CANCELLED", "reason", "Supplier can't deliver"), 200);
        assertThat(patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), paid("UTR-1"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_NOT_DUE");
        // Its requester takes it back
        patch("/v1/orders/payments/" + paymentId + "/withdraw", l2a.token(), null, 403);
        assertThat(data(patch("/v1/orders/payments/" + paymentId + "/withdraw", l1.token(), null, 200)).get("status").asText()).isEqualTo("WITHDRAWN");
    }

    @Test
    void anOverpaidSupplierOwesTheDifferenceOnceUnitsFailTesting() throws Exception {
        long supplierId = paidSupplier();
        long itemId = createItem();
        Map<String, Object> request = orderBody(supplierId, itemId, "10");
        // As much as may be paid before testing: 25 % advance, 25 % midterm
        request.put("advancePercent", new BigDecimal("25"));
        request.put("midtermPercent", new BigDecimal("25"));
        long orderId = data(post("/v1/orders", l1.token(), request, 201)).get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        accept(orderId);
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE", "262.50"), 201)).get("id").asLong());
        moveOrder(orderId, l1, "SHIPPED");
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM", "262.50"), 201)).get("id").asLong());

        // Staff no longer record refunds: the supplier pays them back in their portal. None is owed before testing
        post("/v1/orders/" + orderId + "/payments", l1.token(), refund("10.00"), 400);
        assertThat(supplierRefund(orderId, "10.00", 409).get("errorCode").asText()).isEqualTo("SUPPLIER_REFUND_NOT_DUE");
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();
        long stockId = createStock(orderNumber, "3", "7").get("id").asLong();
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

        // Paid 525.00 for what is worth 3 x 100.00 + 50.00 = 350.00: a claim of 175.00 opens by itself
        JsonNode claim = summary(orderId).get("claim");
        assertThat(claim.get("status").asText()).isEqualTo("OPEN");
        assertThat(claim.get("cause").asText()).isEqualTo("FAILED_TESTING");
        assertThat(claim.get("failedUnits").decimalValue()).isEqualByComparingTo("7");
        assertThat(claim.get("claimedAmount").decimalValue()).isEqualByComparingTo("175.00");
        assertThat(claim.get("refundDue").decimalValue()).isEqualByComparingTo("175.00");
        assertThat(notificationTitles(l1)).contains("Refund due from a supplier");

        supplierRefund(orderId, "175.01", 400);
        long refundId = data(supplierRefund(orderId, "150.00", 201)).get("id").asLong();
        // Checked by a Finance Manager, not a Super Admin
        patch("/v1/orders/payments/" + refundId + "/decision", l2a.token(), body("decision", "APPROVED"), 409);
        patch("/v1/orders/payments/" + refundId + "/repayment-check", l1.token(), body("received", true), 200);
        assertThat(summary(orderId).get("claim").get("refundDue").decimalValue()).isEqualByComparingTo("25.00");

        // The Super Admin agrees with the supplier to waive the rest: the claim is settled
        Map<String, Object> waive = body("agreedAmount", new BigDecimal("150.00"), "reason", "25.00 kept for the repaired unit");
        patch("/v1/orders/" + orderId + "/refund-claim", l1.token(), waive, 403);
        patch("/v1/orders/" + orderId + "/refund-claim", l2a.token(), body("agreedAmount", new BigDecimal("250.00"), "reason", "More"), 400);
        JsonNode settled = data(patch("/v1/orders/" + orderId + "/refund-claim", l2a.token(), waive, 200)).get("claim");
        assertThat(settled.get("status").asText()).isEqualTo("SETTLED");
        assertThat(settled.get("refundDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(settled.get("adjustReason").asText()).isEqualTo("25.00 kept for the repaired unit");
    }

    @Test
    void aConfirmedPaymentIsCorrectedByAReversal() throws Exception {
        long orderId = approvedOrder();
        long paymentId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201)).get("id").asLong();
        // Waiting for approval: only its requester corrects or withdraws it; the amount stays the plan's
        Map<String, Object> byCheque = payment("ADVANCE", "315.00");
        byCheque.put("method", "CHEQUE");
        put("/v1/orders/payments/" + paymentId, l2a.token(), byCheque, 403);
        put("/v1/orders/payments/" + paymentId, l1.token(), payment("ADVANCE", "200.00"), 400);
        JsonNode corrected = data(put("/v1/orders/payments/" + paymentId, l1.token(), byCheque, 200));
        assertThat(corrected.get("method").asText()).isEqualTo("CHEQUE");
        assertThat(corrected.get("amount").decimalValue()).isEqualByComparingTo("315.00");
        assertThat(corrected.get("events").findValuesAsText("type")).containsExactly("REQUESTED", "CORRECTED");
        approve(paymentId);
        put("/v1/orders/payments/" + paymentId, l1.token(), byCheque, 409);

        post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", " "), 400);
        long reversalId = data(post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", "Paid to the wrong account"), 201))
                .get("id").asLong();
        assertThat(post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", "Again"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_ALREADY_REVERSED");
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("315.00");
        // A payment's reversal is paid back by the supplier and checked by a Finance Manager (no Super Admin step)
        repayAndConfirm(reversalId);
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("0");

        JsonNode entries = data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("entries");
        assertThat(entries.get(0).get("reversed").asBoolean()).isTrue();
        assertThat(entries.get(1).get("kind").asText()).isEqualTo("REVERSAL");

        // Reversed, the advance is owed again; a withdrawn request is gone from the queue
        long withdrawnId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + withdrawnId + "/withdraw", l2a.token(), null, 403);
        patch("/v1/orders/payments/" + withdrawnId + "/withdraw", l1.token(), null, 200);
        patch("/v1/orders/payments/" + withdrawnId + "/decision", l2a.token(), body("decision", "APPROVED"), 409);
        // Top-level entry ids only: a nested id may happen to be equal
        assertThat(java.util.stream.StreamSupport.stream(data(get("/v1/orders/payments", l2a.token(), 200)).spliterator(), false)
                .map(queued -> queued.get("id").asLong())).doesNotContain(withdrawnId);
    }

    @Test
    void nothingIsReversedOnceTheOrderIsDeliveredTestedAndFullyPaidWithEveryPaymentReceived() throws Exception {
        long orderId = approvedOrder();
        long advanceId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201)).get("id").asLong();
        approve(advanceId);
        moveOrder(orderId, l1, "SHIPPED");
        long midtermId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM"), 201)).get("id").asLong();
        approve(midtermId);
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();
        long stockId = createStock(orderNumber, "10", "0").get("id").asLong();
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
        // Delivered and tested, but the final part is still owed: the window is open
        assertThat(summary(orderId).get("reversalClosed").asBoolean()).isFalse();

        // The final payment is made; until the supplier confirms it, the window stays open
        long finalId = data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("FINAL"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + finalId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        patch("/v1/orders/payments/" + finalId + "/paid", l1.token(), paid("UTR-FINAL"), 200);
        assertThat(summary(orderId).get("reversalClosed").asBoolean()).isFalse();
        long reversalId = data(post("/v1/orders/payments/" + midtermId + "/reversal", l1.token(), body("reason", "Paid to the wrong account"), 201))
                .get("id").asLong();

        // Received: everything is paid, but a reversal is still open, so the window is too
        patch("/v1/supplier-portal/payments/" + finalId + "/receipt", supplier.token(), body("received", true), 200);
        assertThat(summary(orderId).get("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary(orderId).get("reversalClosed").asBoolean()).isFalse();
        repayAndConfirm(reversalId);
        JsonNode reversed = summary(orderId);
        // The reversed midterm (20 % of 1050.00) is owed again
        assertThat(reversed.get("outstanding").decimalValue()).isEqualByComparingTo("210.00");
        assertThat(reversed.get("reversalClosed").asBoolean()).isFalse();

        // The midterm is paid again and received: delivered, tested, fully paid, all received. The window closes
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("MIDTERM"), 201)).get("id").asLong());
        JsonNode closed = summary(orderId);
        assertThat(closed.get("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(closed.get("reversalClosed").asBoolean()).isTrue();
        for (long paymentId : List.of(advanceId, finalId)) {
            JsonNode refused = post("/v1/orders/payments/" + paymentId + "/reversal", l1.token(), body("reason", "Too late"), 409);
            assertThat(refused.get("errorCode").asText()).isEqualTo("SUPPLIER_PAYMENT_REVERSAL_CLOSED");
            assertThat(refused.get("message").asText()).contains("can no longer be reversed");
        }
        // Nothing new was asked of the supplier, and they see the same closed window
        assertThat(data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("entries").findValuesAsText("kind")
                .stream().filter("REVERSAL"::equals).count()).isEqualTo(1);
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("summary")
                .get("reversalClosed").asBoolean()).isTrue();
    }

    @Test
    void anAdvancePaidForACancelledOrderIsDueBack() throws Exception {
        long orderId = approvedOrder();
        approve(data(post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 201)).get("id").asLong());
        patch("/v1/orders/" + orderId + "/status", l2a.token(), body("status", "CANCELLED", "reason", "Supplier can't deliver"), 200);

        JsonNode summary = summary(orderId);
        assertThat(summary.get("payable").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.get("openMilestones").size()).isZero();
        assertThat(summary.get("claim").get("cause").asText()).isEqualTo("ORDER_CANCELLED");
        assertThat(summary.get("claim").get("refundDue").decimalValue()).isEqualByComparingTo("315.00");
        post("/v1/orders/" + orderId + "/payments", l1.token(), payment("ADVANCE"), 409);
    }

    @Test
    void financeManagersRequestAndPayButOnlyASuperAdminApproves() throws Exception {
        long orderId = approvedOrder();
        // Everyone who sees orders sees the payments; an auditor records nothing
        String auditor = staff("FINANCIAL_AUDITOR", "ADMIN_L2");
        get("/v1/orders/" + orderId + "/payments", auditor, 200);
        post("/v1/orders/" + orderId + "/payments", auditor, payment("ADVANCE", "10.00"), 403);
        get("/v1/orders/payments", auditor, 403);
        get("/v1/orders/payments/to-make", auditor, 403);

        // A Supplier Manager, even at Level 2, neither requests nor approves payments any more
        String supplierManager = staff("SUPPLIER_MANAGER", "ADMIN_L2");
        get("/v1/orders/" + orderId + "/payments", supplierManager, 200);
        post("/v1/orders/" + orderId + "/payments", supplierManager, payment("ADVANCE"), 403);

        // A Level 1 Finance Manager requests; a Level 2 Finance Manager can't approve: only a Super Admin can
        String financeL1 = staff("FINANCE_MANAGER", "ADMIN_L1");
        String financeL2 = staff("FINANCE_MANAGER", "ADMIN_L2");
        long paymentId = data(post("/v1/orders/" + orderId + "/payments", financeL1, payment("ADVANCE"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + paymentId + "/decision", financeL2, body("decision", "APPROVED"), 403);
        get("/v1/orders/payments", financeL2, 403);
        patch("/v1/orders/payments/" + paymentId + "/decision", l2a.token(), body("decision", "APPROVED"), 200);
        // Any Finance Manager pays it
        get("/v1/orders/payments/to-make", financeL2, 200);
        assertThat(data(patch("/v1/orders/payments/" + paymentId + "/paid", financeL2, paid("UTR-FM"), 200)).get("status").asText())
                .isEqualTo("AWAITING_SUPPLIER");
    }

    // A new supplier with a login, who confirms the payments of this test
    private long paidSupplier() throws Exception {
        supplier = supplierWithLogin();
        return supplier.supplierId();
    }

    // Approved by staff and accepted by the supplier: the advance is due
    private long approvedOrder() throws Exception {
        return approvedOrder(paidSupplier());
    }

    private long approvedOrder(long supplierId) throws Exception {
        long orderId = createOrder(supplierId, createItem(), "10").get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        moveOrder(orderId, l2a, "CONFIRMED");
        return orderId;
    }

    // The supplier accepts the order in their portal
    private void accept(long orderId) throws Exception {
        post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200);
    }

    /**
     * Takes an entry to where it counts. A payment request: a Super Admin approves it, it is paid, and the supplier
     * confirms they received it. A refund or reversal: a Super Admin confirms it.
     */
    private void approve(long paymentId) throws Exception {
        JsonNode decided = data(patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200));
        if (!"PAYMENT".equals(decided.get("kind").asText())) {
            assertThat(decided.get("status").asText()).isEqualTo("APPROVED");
            return;
        }
        assertThat(decided.get("status").asText()).isEqualTo("TO_PAY");
        patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), paid("UTR-" + paymentId), 200);
        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", true), 200);
    }

    // The supplier pays back (part of) the refund they owe, on their own, in their portal
    private JsonNode supplierRefund(long orderId, String amount, int expectedStatus) throws Exception {
        return perform(MockMvcRequestBuilders.multipart("/v1/supplier-portal/orders/" + orderId + "/repayments")
                .param("amount", amount).param("method", "ONLINE").param("reference", "TXN-" + amount)
                .param("paidOn", LocalDate.now().toString()).header("Authorization", "Bearer " + supplier.token()), expectedStatus);
    }

    // The supplier pays back what is asked of them, and a Finance Manager confirms it arrived
    private void repayAndConfirm(long paymentId) throws Exception {
        perform(MockMvcRequestBuilders.multipart("/v1/supplier-portal/payments/" + paymentId + "/repayment")
                .param("method", "BANK_TRANSFER").param("reference", "UTR-BACK-" + paymentId).param("paidOn", LocalDate.now().toString())
                .header("Authorization", "Bearer " + supplier.token()), 200);
        patch("/v1/orders/payments/" + paymentId + "/repayment-check", l1.token(), body("received", true), 200);
    }

    private JsonNode resend(long paymentId, String reference, String note, byte[] proof, int expectedStatus) throws Exception {
        var request = MockMvcRequestBuilders.multipart("/v1/orders/payments/" + paymentId + "/resend");
        request.param("reference", reference).param("paidOn", LocalDate.now().toString()).param("note", note)
                .header("Authorization", "Bearer " + l1.token());
        if (proof != null) {
            request.file(new MockMultipartFile("proof", "bank_statement.pdf", "application/pdf", proof));
        }
        return perform(request, expectedStatus);
    }

    private static byte[] proofPdf() {
        return InvoicePdfs.withLines("Bank statement", "Credit UTR-1 315.00");
    }

    // A file download: its body as text (a PDF starts with %PDF)
    private String download(String path, String token, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(expectedStatus);
        return new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private String staff(String designation, String level) throws Exception {
        String username = uniq(designation.toLowerCase().replace("_", ""));
        long userId = insertUser(username, level, "WEB", false, true);
        insertEmployee(userId, designation);
        return login(username, SEED_PASSWORD);
    }

    private JsonNode summary(long orderId) throws Exception {
        return data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("summary");
    }

    private JsonNode entry(long orderId, long paymentId) throws Exception {
        for (JsonNode entry : data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("entries")) {
            if (entry.get("id").asLong() == paymentId) {
                return entry;
            }
        }
        throw new AssertionError("No entry " + paymentId);
    }

    private static JsonNode milestone(JsonNode summary, String milestone) {
        for (JsonNode part : summary.get("milestones")) {
            if (part.get("milestone").asText().equals(milestone)) {
                return part;
            }
        }
        throw new AssertionError("No milestone " + milestone);
    }

    // A request: the amount left out (the system fills in what is left of the part). Its reference and date are ignored
    private static Map<String, Object> payment(String milestone) {
        return body("kind", "PAYMENT", "milestone", milestone, "method", "BANK_TRANSFER", "reference", "UTR-" + milestone,
                "paidOn", LocalDate.now().toString());
    }

    // The amount given: it must be exactly what is left of the part
    private static Map<String, Object> payment(String milestone, String amount) {
        Map<String, Object> request = payment(milestone);
        request.put("amount", new BigDecimal(amount));
        return request;
    }

    private static Map<String, Object> paid(String reference) {
        return body("reference", reference, "paidOn", LocalDate.now().toString());
    }

    private static Map<String, Object> refund(String amount) {
        return body("kind", "REFUND", "amount", new BigDecimal(amount), "method", "ONLINE", "reference", "TXN-" + amount,
                "paidOn", LocalDate.now().toString());
    }
}
