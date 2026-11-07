package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import com.tinqa.procurement.support.InvoicePdfs;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of docs/supplier-returns-and-repayments.md (Web UI repo), through the HTTP API:
 * <ul>
 *     <li>money back from the supplier (a payment's reversal, a refund due, a return's repayment) is paid by the supplier
 *     and checked by a Finance Manager, with no Super Admin step;</li>
 *     <li>payment requests are approved and paid in plan order;</li>
 *     <li>failed pieces go back to the supplier: a Supplier Manager raises the return, a Super Admin approves it, the
 *     supplier accepts it, the pieces are shipped, and the supplier repays them and the shipping;</li>
 *     <li>every confirmed repayment is in the company account's feed.</li>
 * </ul>
 * The order fixture is 10 PCS x 100.00 + 50.00 shipment = 1050.00 (no GST).
 */
class SupplierReturnsAndRepaymentsIntegrationTest extends IntegrationTestSupport {

    private SupplierLogin supplier;

    @Test
    void aReversalGoesToTheSupplierWhoPaysItBackAndAFinanceManagerChecksIt() throws Exception {
        long orderId = acceptedOrder("30", "20");
        long paymentId = request(orderId, "ADVANCE");
        payThrough(paymentId);

        String financeManager = staff("FINANCE_MANAGER", "ADMIN_L1");
        long reversalId = data(post("/v1/orders/payments/" + paymentId + "/reversal", financeManager,
                body("reason", "Paid twice by the bank"), 201)).get("id").asLong();
        JsonNode reversal = entry(orderId, reversalId);
        assertThat(reversal.get("status").asText()).isEqualTo("REPAYMENT_DUE");
        assertThat(reversal.get("amount").decimalValue()).isEqualByComparingTo("315.00");
        // Not the Super Admin's: it never reaches their queue, and they can't decide it
        // Top-level entry ids only: findValuesAsText would also match a nested id that happens to be equal
        assertThat(StreamSupport.stream(data(get("/v1/orders/payments", l2a.token(), 200)).spliterator(), false)
                .map(queued -> queued.get("id").asLong())).doesNotContain(reversalId);
        patch("/v1/orders/payments/" + reversalId + "/decision", l2a.token(), body("decision", "APPROVED"), 409);

        // The supplier sees what is asked, with the reason, and flags the order
        JsonNode supplierView = data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("entries");
        JsonNode asked = find(supplierView, reversalId);
        assertThat(asked.get("note").asText()).isEqualTo("Paid twice by the bank");
        assertThat(asked.get("recordedByUsername").isNull()).isTrue();
        assertThat(portalOrder(orderId).get("repaymentsDue").asInt()).isEqualTo(1);

        // A bank transfer needs its UTR; nothing can be checked before the supplier pays
        patch("/v1/orders/payments/" + reversalId + "/repayment-check", financeManager, body("received", true), 409);
        repay(reversalId, "BANK_TRANSFER", null, null, 400);
        repay(reversalId, "BANK_TRANSFER", "UTR-BACK-1", null, 200);
        assertThat(entry(orderId, reversalId).get("status").asText()).isEqualTo("REPAYMENT_SENT");
        assertThat(data(get("/v1/orders/payments/repayments-to-check", financeManager, 200)).findValuesAsText("id")).contains(String.valueOf(reversalId));
        repay(reversalId, "BANK_TRANSFER", "UTR-BACK-2", null, 409);

        // Not in the bank: the reason is required, and goes back to the supplier, who sends it again with a receipt
        patch("/v1/orders/payments/" + reversalId + "/repayment-check", financeManager, body("received", false), 400);
        patch("/v1/orders/payments/" + reversalId + "/repayment-check", financeManager,
                body("received", false, "reason", "No credit with UTR-BACK-1 in the statement"), 200);
        JsonNode missing = find(data(get("/v1/supplier-portal/orders/" + orderId + "/payments", supplier.token(), 200)).get("entries"), reversalId);
        assertThat(missing.get("status").asText()).isEqualTo("REPAYMENT_MISSING");
        assertThat(missing.get("decisionReason").asText()).isEqualTo("No credit with UTR-BACK-1 in the statement");
        repay(reversalId, "BANK_TRANSFER", "UTR-BACK-1", InvoicePdfs.withLines("Bank receipt", "UTR-BACK-1 315.00"), 200);

        assertThat(data(patch("/v1/orders/payments/" + reversalId + "/repayment-check", financeManager, body("received", true), 200))
                .get("status").asText()).isEqualTo("APPROVED");
        assertThat(summary(orderId).get("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(find(data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("entries"), reversalId)
                .get("events").findValuesAsText("type")).containsExactly("REPAYMENT_REQUESTED", "REPAYMENT_SENT", "REPAYMENT_MISSING",
                "REPAYMENT_RESENT", "REPAYMENT_RECEIVED");

        // The company account's feed: the reversal with its UTR and the supplier's receipt
        JsonNode receipt = receipt(reversalId);
        assertThat(receipt.get("reason").asText()).isEqualTo("PAYMENT_REVERSAL");
        assertThat(receipt.get("direction").asText()).isEqualTo("IN");
        assertThat(receipt.get("reference").asText()).isEqualTo("UTR-BACK-1");
        assertThat(receipt.get("receiptFileName").asText()).isEqualTo("receipt.pdf");
        assertThat(receipt.get("note").asText()).isEqualTo("Paid twice by the bank");
        assertThat(receipt.get("postingKey").asText()).isEqualTo("PROCUREMENT_SUPPLIER_RECEIPT:" + reversalId);
    }

    @Test
    void paymentRequestsAreApprovedAndPaidInPlanOrder() throws Exception {
        long orderId = acceptedOrder("30", "20");
        moveOrder(orderId, l1, "SHIPPED");
        // Both parts may be requested at once
        long advance = request(orderId, "ADVANCE");
        long midterm = request(orderId, "MIDTERM");
        assertThat(entry(orderId, midterm).get("waitsFor").asText()).isEqualTo("ADVANCE");
        assertThat(entry(orderId, advance).get("waitsFor").isNull()).isTrue();
        assertThat(find(data(get("/v1/orders/payments", l2a.token(), 200)), midterm).get("waitsFor").asText()).isEqualTo("ADVANCE");

        // ...but the midterm is approved only after the advance
        assertThat(patch("/v1/orders/payments/" + midterm + "/decision", l2a.token(), body("decision", "APPROVED"), 409)
                .get("errorCode").asText()).isEqualTo("SUPPLIER_PAYMENT_OUT_OF_ORDER");
        patch("/v1/orders/payments/" + advance + "/decision", l2a.token(), body("decision", "APPROVED"), 200);
        assertThat(entry(orderId, midterm).get("waitsFor").isNull()).isTrue();
        patch("/v1/orders/payments/" + midterm + "/decision", l2a.token(), body("decision", "APPROVED"), 200);

        // ...and paid after it
        assertThat(patch("/v1/orders/payments/" + midterm + "/paid", l1.token(), paid("UTR-M"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_PAYMENT_OUT_OF_ORDER");
        patch("/v1/orders/payments/" + advance + "/paid", l1.token(), paid("UTR-A"), 200);
        patch("/v1/orders/payments/" + midterm + "/paid", l1.token(), paid("UTR-M"), 200);

        // An earlier part never requested also holds a later one back; rejecting is always possible
        long other = acceptedOrder("30", "20");
        moveOrder(other, l1, "SHIPPED");
        long alone = request(other, "MIDTERM");
        assertThat(entry(other, alone).get("waitsFor").asText()).isEqualTo("ADVANCE");
        assertThat(patch("/v1/orders/payments/" + alone + "/decision", l2a.token(), body("decision", "APPROVED"), 409)
                .get("message").asText()).contains("has not been requested yet");
        patch("/v1/orders/payments/" + alone + "/decision", l2a.token(), body("decision", "REJECTED", "reason", "Advance first"), 200);
    }

    @Test
    void failedPiecesGoBackToTheSupplierWhoRepaysThemAndTheShipping() throws Exception {
        // As much as may be paid before testing (25 / 25: 525.00), then 7 of 10 pieces fail: worth 350.00, 175.00 overpaid
        long orderId = acceptedOrder("25", "25");
        payThrough(request(orderId, "ADVANCE"));
        moveOrder(orderId, l1, "SHIPPED");
        payThrough(request(orderId, "MIDTERM"));
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();

        // No return before testing
        String supplierManager = staff("SUPPLIER_MANAGER", "ADMIN_L1");
        assertThat(data(get("/v1/orders/" + orderId + "/returns", supplierManager, 200)).get("canRaise").asBoolean()).isFalse();
        assertThat(post("/v1/orders/" + orderId + "/returns", supplierManager, raise("1", "TINQA_SHIPS"), 409).get("errorCode").asText())
                .isEqualTo("SUPPLIER_RETURN_NOT_ALLOWED");
        long stockId = createStock(orderNumber, "3", "7").get("id").asLong();
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

        JsonNode returns = data(get("/v1/orders/" + orderId + "/returns", supplierManager, 200));
        assertThat(returns.get("canRaise").asBoolean()).isTrue();
        assertThat(returns.get("unitsLeft").decimalValue()).isEqualByComparingTo("7");
        // A Finance Manager raises no returns; at most the failed pieces go back
        post("/v1/orders/" + orderId + "/returns", staff("FINANCE_MANAGER", "ADMIN_L2"), raise("7", "TINQA_SHIPS"), 403);
        post("/v1/orders/" + orderId + "/returns", supplierManager, raise("8", "TINQA_SHIPS"), 400);
        JsonNode raised = data(post("/v1/orders/" + orderId + "/returns", supplierManager, raise("7", "TINQA_SHIPS"), 201));
        long returnId = raised.get("id").asLong();
        assertThat(raised.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(raised.get("piecesValue").decimalValue()).isEqualByComparingTo("700.00");

        // The supplier sees nothing until a Super Admin approves; a Supplier Manager can't approve it
        assertThat(data(get("/v1/supplier-portal/orders/" + orderId + "/returns", supplier.token(), 200)).get("returns").size()).isZero();
        patch("/v1/orders/returns/" + returnId + "/decision", staff("SUPPLIER_MANAGER", "ADMIN_L2"), body("decision", "APPROVED"), 403);
        assertThat(data(get("/v1/orders/returns/waiting", l2a.token(), 200)).findValuesAsText("id")).contains(String.valueOf(returnId));
        assertThat(data(get("/v1/approvals/summary", l2a.token(), 200)).get("SUPPLIER_RETURNS").asInt())
                .isEqualTo(data(get("/v1/orders/returns/waiting", l2a.token(), 200)).size());
        patch("/v1/orders/returns/" + returnId + "/decision", l2a.token(), body("decision", "APPROVED"), 200);

        // The supplier declines first; the Supplier Manager sends it again; then they accept
        assertThat(portalOrder(orderId).get("returnsToAnswer").asInt()).isEqualTo(1);
        patch("/v1/supplier-portal/returns/" + returnId + "/response", supplier.token(), body("accepted", false), 400);
        patch("/v1/supplier-portal/returns/" + returnId + "/response", supplier.token(), body("accepted", false, "reason", "Send photos first"), 200);
        patch("/v1/orders/returns/" + returnId + "/dispatch", supplierManager, dispatch("Bluedart", "AWB1", "80.00"), 409);
        patch("/v1/orders/returns/" + returnId + "/resend", supplierManager, body("note", "Photos are in the piece details"), 200);
        patch("/v1/supplier-portal/returns/" + returnId + "/response", supplier.token(), body("accepted", true), 200);

        // TinQa ships them: courier, tracking number and the charge are required
        patch("/v1/orders/returns/" + returnId + "/dispatch", supplierManager, dispatch("Bluedart", null, "80.00"), 400);
        patch("/v1/orders/returns/" + returnId + "/dispatch", supplierManager, dispatch("Bluedart", "AWB1", null), 400);
        assertThat(data(patch("/v1/orders/returns/" + returnId + "/dispatch", supplierManager, dispatch("Bluedart", "AWB1", "80.00"), 200))
                .get("status").asText()).isEqualTo("IN_TRANSIT");
        patch("/v1/orders/returns/" + returnId + "/cancel", supplierManager, body("note", "Too late"), 409);

        // The pieces arrive: the supplier owes what was paid for them (the 175.00 overpaid) and the 80.00 shipping
        JsonNode arrived = data(patch("/v1/supplier-portal/returns/" + returnId + "/received", supplier.token(), body(), 200));
        assertThat(arrived.get("status").asText()).isEqualTo("RECEIVED_BY_SUPPLIER");
        assertThat(arrived.get("amountDue").decimalValue()).isEqualByComparingTo("255.00");
        assertThat(arrived.get("requestedByUsername").isNull()).isTrue();
        long repaymentId = arrived.get("repaymentId").asLong();
        JsonNode ask = entry(orderId, repaymentId);
        assertThat(ask.get("status").asText()).isEqualTo("REPAYMENT_DUE");
        assertThat(ask.get("amount").decimalValue()).isEqualByComparingTo("175.00");
        assertThat(ask.get("chargeAmount").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(ask.get("returnId").asLong()).isEqualTo(returnId);

        repay(repaymentId, "ONLINE", "TXN-RET-1", null, 200);
        patch("/v1/orders/payments/" + repaymentId + "/repayment-check", l1.token(), body("received", true), 200);

        // The return and the claim are settled; the 80.00 shipping never counts towards the claim
        JsonNode settled = data(get("/v1/orders/" + orderId + "/returns", l1.token(), 200));
        assertThat(settled.get("returns").get(0).get("status").asText()).isEqualTo("SETTLED");
        assertThat(settled.get("unitsLeft").decimalValue()).isEqualByComparingTo("0");
        JsonNode claim = summary(orderId).get("claim");
        assertThat(claim.get("status").asText()).isEqualTo("SETTLED");
        assertThat(claim.get("refunded").decimalValue()).isEqualByComparingTo("175.00");

        JsonNode receipt = receipt(repaymentId);
        assertThat(receipt.get("reason").asText()).isEqualTo("FAILED_PIECES_RETURN");
        assertThat(receipt.get("claimCause").asText()).isEqualTo("FAILED_TESTING");
        assertThat(receipt.get("amount").decimalValue()).isEqualByComparingTo("175.00");
        assertThat(receipt.get("chargeAmount").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(receipt.get("returnId").asLong()).isEqualTo(returnId);

        // The flow history: a branch for the return; the supplier sees it from the approval on, by role only
        List<String> staffTitles = titles(get("/v1/orders/" + orderId + "/timeline", l1.token(), 200), "return-" + returnId);
        assertThat(staffTitles).first().asString().endsWith("raised a return of the failed pieces");
        assertThat(staffTitles).last().asString().endsWith("settled the return");
        JsonNode supplierTimeline = data(get("/v1/supplier-portal/orders/" + orderId + "/timeline", supplier.token(), 200)).get("events");
        assertThat(StreamSupport.stream(supplierTimeline.spliterator(), false)
                .filter(event -> ("return-" + returnId).equals(event.path("branchId").asText()))
                .map(event -> event.get("title").asText())).noneMatch(title -> title.contains("raised"))
                .allMatch(title -> !title.isBlank());
        assertThat(supplierTimeline.findValues("actorName")).allMatch(JsonNode::isNull);
    }

    @Test
    void aSuperAdminNeverDecidesTheirOwnReturnAndARejectedReturnFreesItsPieces() throws Exception {
        long orderId = acceptedOrder("30", "20");
        moveOrder(orderId, l1, "SHIPPED");
        String orderNumber = moveOrder(orderId, l1, "DELIVERED").get("orderNumber").asText();
        long stockId = createStock(orderNumber, "9", "1").get("id").asLong();
        post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 200);

        long returnId = data(post("/v1/orders/" + orderId + "/returns", l2a.token(), raise("1", "SUPPLIER_PICKUP"), 201)).get("id").asLong();
        assertThat(patch("/v1/orders/returns/" + returnId + "/decision", l2a.token(), body("decision", "APPROVED"), 403)
                .get("errorCode").asText()).isEqualTo("SUPPLIER_RETURN_SELF_DECISION");
        patch("/v1/orders/returns/" + returnId + "/decision", l2b.token(), body("decision", "REJECTED"), 400);
        patch("/v1/orders/returns/" + returnId + "/decision", l2b.token(), body("decision", "REJECTED", "reason", "Repair it here"), 200);
        assertThat(data(get("/v1/orders/" + orderId + "/returns", l1.token(), 200)).get("unitsLeft").decimalValue()).isEqualByComparingTo("1");

        // The supplier collects the piece themselves: no tracking number, no charge. Nothing was paid for it: settled at once
        long second = data(post("/v1/orders/" + orderId + "/returns", l1.token(), raise("1", "SUPPLIER_PICKUP"), 201)).get("id").asLong();
        patch("/v1/orders/returns/" + second + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        patch("/v1/supplier-portal/returns/" + second + "/response", supplier.token(), body("accepted", true), 200);
        patch("/v1/orders/returns/" + second + "/dispatch", l1.token(), dispatch(null, null, "50.00"), 400);
        patch("/v1/orders/returns/" + second + "/dispatch", l1.token(), dispatch("Collected by their driver", null, null), 200);
        JsonNode arrived = data(patch("/v1/supplier-portal/returns/" + second + "/received", supplier.token(), body(), 200));
        assertThat(arrived.get("status").asText()).isEqualTo("SETTLED");
        assertThat(arrived.get("repaymentId").isNull()).isTrue();
        // Another supplier sees none of it
        get("/v1/supplier-portal/orders/" + orderId + "/returns", supplierWithLogin().token(), 404);
    }

    @Test
    void aSupplierPaysARefundDueOnTheirOwnAndStaffNoLongerRecordRefunds() throws Exception {
        long orderId = acceptedOrder("30", "20");
        payThrough(request(orderId, "ADVANCE"));
        patch("/v1/orders/" + orderId + "/status", l2a.token(), body("status", "CANCELLED", "reason", "Supplier can't deliver"), 200);

        post("/v1/orders/" + orderId + "/payments", l1.token(), body("kind", "REFUND", "amount", new BigDecimal("315.00"),
                "method", "ONLINE", "reference", "TXN-1", "paidOn", LocalDate.now().toString()), 400);
        supplierRefund(orderId, "315.01", 400);
        long refundId = data(supplierRefund(orderId, "315.00", 201)).get("id").asLong();
        // Being checked, it counts as on its way: nothing more can be offered
        supplierRefund(orderId, "1.00", 400);
        patch("/v1/orders/payments/" + refundId + "/repayment-check", l1.token(), body("received", true), 200);
        assertThat(summary(orderId).get("claim").get("status").asText()).isEqualTo("SETTLED");
        assertThat(receipt(refundId).get("reason").asText()).isEqualTo("REFUND_CLAIM");
        assertThat(receipt(refundId).get("claimCause").asText()).isEqualTo("ORDER_CANCELLED");
    }

    // ------------------------------------------------------------------ helpers

    // A new order with a supplier login, approved and accepted by the supplier: the advance is due
    private long acceptedOrder(String advance, String midterm) throws Exception {
        supplier = supplierWithLogin();
        Map<String, Object> request = orderBody(supplier.supplierId(), createItem(), "10");
        request.put("advancePercent", new BigDecimal(advance));
        request.put("midtermPercent", new BigDecimal(midterm));
        long orderId = data(post("/v1/orders", l1.token(), request, 201)).get("id").asLong();
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200);
        return orderId;
    }

    private long request(long orderId, String milestone) throws Exception {
        return data(post("/v1/orders/" + orderId + "/payments", l1.token(), body("kind", "PAYMENT", "milestone", milestone,
                "method", "BANK_TRANSFER"), 201)).get("id").asLong();
    }

    // Approved by a Super Admin, paid, and confirmed by the supplier: it counts
    private void payThrough(long paymentId) throws Exception {
        patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), paid("UTR-" + paymentId), 200);
        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", true), 200);
    }

    private JsonNode repay(long paymentId, String method, String reference, byte[] receipt, int expectedStatus) throws Exception {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/v1/supplier-portal/payments/" + paymentId + "/repayment");
        request.param("method", method).param("paidOn", LocalDate.now().toString()).header("Authorization", "Bearer " + supplier.token());
        if (reference != null) {
            request.param("reference", reference);
        }
        if (receipt != null) {
            request.file(new MockMultipartFile("receipt", "receipt.pdf", "application/pdf", receipt));
        }
        return perform(request, expectedStatus);
    }

    private JsonNode supplierRefund(long orderId, String amount, int expectedStatus) throws Exception {
        return perform(MockMvcRequestBuilders.multipart("/v1/supplier-portal/orders/" + orderId + "/repayments")
                .param("amount", amount).param("method", "ONLINE").param("reference", "TXN-" + amount)
                .param("paidOn", LocalDate.now().toString()).header("Authorization", "Bearer " + supplier.token()), expectedStatus);
    }

    private JsonNode receipt(long paymentId) throws Exception {
        JsonNode feed = data(perform(MockMvcRequestBuilders.get("/v1/internal/finance/supplier-receipts").param("limit", "500")
                .header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), 200));
        for (JsonNode receipt : feed.get("receipts")) {
            if (receipt.get("receiptId").asLong() == paymentId) {
                return receipt;
            }
        }
        throw new AssertionError("No receipt " + paymentId + " in the feed");
    }

    private JsonNode portalOrder(long orderId) throws Exception {
        return find(data(get("/v1/supplier-portal/orders", supplier.token(), 200)), orderId);
    }

    private JsonNode summary(long orderId) throws Exception {
        return data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("summary");
    }

    private JsonNode entry(long orderId, long paymentId) throws Exception {
        return find(data(get("/v1/orders/" + orderId + "/payments", l1.token(), 200)).get("entries"), paymentId);
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode node : list) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        throw new AssertionError("No entry " + id);
    }

    private static List<String> titles(JsonNode response, String branchId) {
        return StreamSupport.stream(data(response).get("events").spliterator(), false)
                .filter(event -> branchId.equals(event.path("branchId").asText()))
                .map(event -> event.get("title").asText()).toList();
    }

    private static Map<String, Object> raise(String units, String mode) {
        return body("units", new BigDecimal(units), "reason", "No power on testing", "pieceDetails", "Serials S1, S2",
                "shippingMode", mode);
    }

    private static Map<String, Object> dispatch(String courier, String tracking, String charge) {
        Map<String, Object> request = body("dispatchedOn", LocalDate.now().toString());
        if (courier != null) request.put("courier", courier);
        if (tracking != null) request.put("trackingNumber", tracking);
        if (charge != null) request.put("shippingCharge", new BigDecimal(charge));
        return request;
    }

    private static Map<String, Object> paid(String reference) {
        return body("reference", reference, "paidOn", LocalDate.now().toString());
    }

    private String staff(String designation, String level) throws Exception {
        String username = uniq(designation.toLowerCase().replace("_", ""));
        long userId = insertUser(username, level, "WEB", false, true);
        insertEmployee(userId, designation);
        return login(username, SEED_PASSWORD);
    }
}
