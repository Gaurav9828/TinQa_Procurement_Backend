package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.InvoicePdfs;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quotation-to-delivery GST trail: a quotation may carry its GST breakup, which follows every change to the order,
 * and the order is delivered only against a dealer's invoice that matches it (or that a Level 2 approver accepts).
 */
@DisplayName("Quotation GST and invoice verification")
class OrderInvoiceIntegrationTest extends IntegrationTestSupport {

    private static final Map<String, Object> INTRA_18 = Map.of("supplyType", "INTRA_STATE", "gstRate", 18, "hsnSac", "7408");

    // 10 x 100 + 50 shipment = 1050 taxable; 18% in state = 94.50 + 94.50; 1239 in all
    private long taxedOrder() throws Exception {
        Map<String, Object> order = orderBody(createDealer(), createItem(), "10");
        order.put("taxBreakup", INTRA_18);
        return data(post("/v1/orders", l1.token(), order, 201)).get("id").asLong();
    }

    private long shipped(long orderId) throws Exception {
        moveOrder(orderId, l1, "PENDING");
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "DEALER_LEVEL_PENDING"), 200);
        moveOrder(orderId, l2a, "CONFIRMED");
        moveOrder(orderId, l1, "SHIPPED");
        return orderId;
    }

    private Map<String, Object> deliver(String overrideReason) {
        Map<String, Object> request = body("status", "DELIVERED", "actualDelivery", LocalDate.now().toString());
        if (overrideReason != null) {
            request.put("invoiceOverrideReason", overrideReason);
        }
        return request;
    }

    @Test
    void theGstBreakupIsCheckedAndFollowsTheOrder() throws Exception {
        long id = taxedOrder();
        JsonNode tax = data(get("/v1/orders/" + id, l1.token(), 200)).get("taxBreakup");
        assertThat(tax.get("taxableValue").decimalValue()).isEqualByComparingTo("1050.00");
        assertThat(tax.get("cgst").decimalValue()).isEqualByComparingTo("94.50");
        assertThat(tax.get("sgst").decimalValue()).isEqualByComparingTo("94.50");
        assertThat(tax.get("grandTotal").decimalValue()).isEqualByComparingTo("1239.00");

        // Twice the quantity: the GST follows at its rate
        JsonNode edited = data(put("/v1/orders/" + id, l1.token(), body("orderQuantity", 20), 200)).get("taxBreakup");
        assertThat(edited.get("cgst").decimalValue()).isEqualByComparingTo("184.50");
        assertThat(edited.get("grandTotal").decimalValue()).isEqualByComparingTo("2419.00");

        JsonNode mixed = put("/v1/orders/" + id, l1.token(), body("taxBreakup", Map.of("supplyType", "INTRA_STATE", "gstRate", 18, "igst", 369)), 400);
        assertThat(mixed.get("errors").get(0).get("field").asText()).isEqualTo("taxBreakup.igst");
        // {} removes it: GST is optional
        assertThat(data(put("/v1/orders/" + id, l1.token(), body("taxBreakup", Map.of()), 200)).get("taxBreakup").size()).isZero();
    }

    @Test
    void aMatchingInvoiceLetsTheOrderBeDelivered() throws Exception {
        long id = shipped(taxedOrder());
        JsonNode missing = patch("/v1/orders/" + id + "/status", l1.token(), deliver(null), 409);
        assertThat(missing.get("errorCode").asText()).isEqualTo("ORDER_INVOICE_REQUIRED");
        get("/v1/orders/" + id + "/invoice", l1.token(), 200);

        JsonNode invoice = data(uploadInvoice(id, l1, matchingInvoice(id), 200));
        assertThat(invoice.get("status").asText()).isEqualTo("MATCHED");
        assertThat(invoice.get("invoiceNumber").asText()).isEqualTo("T-" + id);
        assertThat(invoice.get("expectedTotal").decimalValue()).isEqualByComparingTo("1239.00");

        JsonNode delivered = data(patch("/v1/orders/" + id + "/status", l1.token(), deliver(null), 200));
        assertThat(delivered.get("orderStatus").asText()).isEqualTo("DELIVERED");

        // The stored PDF comes back as an attachment to anyone who sees orders
        MvcResult file = mockMvc.perform(MockMvcRequestBuilders.get("/v1/orders/" + id + "/invoice/file")
                .header("Authorization", "Bearer " + l2b.token())).andReturn();
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(file.getResponse().getContentAsByteArray()).startsWith("%PDF-".getBytes());

        // Too late for another invoice
        JsonNode late = uploadInvoice(id, l1, matchingInvoice(id), 409);
        assertThat(late.get("errorCode").asText()).isEqualTo("ORDER_INVOICE_NOT_ALLOWED");
    }

    @Test
    void aMismatchedInvoiceNeedsALevelTwoOverrideWithAReason() throws Exception {
        long id = shipped(taxedOrder());
        byte[] wrong = InvoicePdfs.withLines("Invoice No: W-1", "Taxable Value 1050.00", "IGST @ 18% 189.00", "Grand Total 1,239.00");
        JsonNode invoice = data(uploadInvoice(id, l1, wrong, 200));
        assertThat(invoice.get("status").asText()).isEqualTo("MISMATCH");
        assertThat(invoice.get("matched").asBoolean()).isFalse();
        assertThat(invoice.get("checks").findValuesAsText("key")).contains("igst", "cgst", "sgst");
        assertThat(notificationTitles(l2a)).contains("Dealer invoice needs a check");

        JsonNode refused = patch("/v1/orders/" + id + "/status", l1.token(), deliver("Looks fine to me"), 409);
        assertThat(refused.get("errorCode").asText()).isEqualTo("ORDER_INVOICE_MISMATCH");
        assertThat(refused.get("message").asText()).contains("IGST");
        JsonNode noReason = patch("/v1/orders/" + id + "/status", l2a.token(), deliver(" "), 400);
        assertThat(noReason.get("errors").get(0).get("field").asText()).isEqualTo("invoiceOverrideReason");

        patch("/v1/orders/" + id + "/status", l2a.token(), deliver("Dealer billed IGST by mistake; credit note promised"), 200);
        JsonNode accepted = data(get("/v1/orders/" + id + "/invoice", l1.token(), 200));
        assertThat(accepted.get("overrideReason").asText()).startsWith("Dealer billed IGST");
        assertThat(accepted.get("overrideByUsername").asText()).isEqualTo(l2a.username());
        String historyReason = jdbc.queryForObject("SELECT reason FROM order_status_history WHERE order_id = ? AND to_status = 'DELIVERED'",
                String.class, id);
        assertThat(historyReason).startsWith("Invoice accepted although it did not match the quotation");
    }

    @Test
    void aScannedInvoiceCannotBeCheckedAndAReuploadReplacesIt() throws Exception {
        long id = shipped(taxedOrder());
        assertThat(data(uploadInvoice(id, l1, InvoicePdfs.withoutText(), 200)).get("status").asText()).isEqualTo("UNVERIFIED");
        patch("/v1/orders/" + id + "/status", l1.token(), deliver(null), 409);

        // Only PDFs
        uploadInvoice(id, l1, "not a pdf".getBytes(), 400);
        // The latest upload is the one that counts
        assertThat(data(uploadInvoice(id, l1, matchingInvoice(id), 200)).get("status").asText()).isEqualTo("MATCHED");
        patch("/v1/orders/" + id + "/status", l1.token(), deliver(null), 200);
    }

    @Test
    void withoutQuotedGstTheInvoiceMustShowNone() throws Exception {
        long id = shipped(createOrder(createDealer(), createItem(), "10").get("id").asLong());
        byte[] taxed = InvoicePdfs.withLines("CGST 94.50", "SGST 94.50", "Grand Total 1239.00");
        assertThat(data(uploadInvoice(id, l1, taxed, 200)).get("status").asText()).isEqualTo("MISMATCH");
        assertThat(data(uploadInvoice(id, l1, InvoicePdfs.withLines("Invoice No 12", "Total 1050.00"), 200)).get("status").asText())
                .isEqualTo("MATCHED");
    }

    @Test
    void uploadingNeedsTheDeliveryPermission() throws Exception {
        long id = shipped(taxedOrder());
        uploadInvoice(id, new Actor(null, null, "inspector", null, inspectorToken()), matchingInvoice(id), 403);
        JsonNode early = uploadInvoice(taxedOrder(), l1, matchingInvoice(id), 409);
        assertThat(early.get("errorCode").asText()).isEqualTo("ORDER_INVOICE_NOT_ALLOWED");
    }

    @Test
    void dealersQuoteGstAndChangeItThroughApproval() throws Exception {
        long dealerId = createDealer();
        long itemId = createItem();
        String username = uniq("dealer");
        post("/v1/admin/dealers/" + dealerId + "/login", l1.token(), body("username", username, "email", username + "@dealer.test"), 201);
        patch("/v1/admin/dealers/" + dealerId + "/login/approval", l2a.token(), body("decision", "APPROVED"), 200);
        String first = login(username, DEFAULT_EMPLOYEE_PASSWORD);
        String password = "Dealer!Passw0rd#2026";
        post("/auth/first-login/password", first, body("newPassword", password, "confirmNewPassword", password), 200);
        String dealer = login(username, password);

        JsonNode quotation = data(post("/v1/dealer-portal/quotations", dealer, body("itemId", itemId, "orderQuantity", new BigDecimal("10"),
                "unitType", "PCS", "unitPrice", new BigDecimal("100.00"), "shipmentPrice", new BigDecimal("50.00"),
                "priceChangeReason", "Bulk rate", "taxBreakup", Map.of("supplyType", "INTER_STATE", "gstRate", 5)), 201));
        assertThat(quotation.get("taxBreakup").get("igst").decimalValue()).isEqualByComparingTo("52.50");
        long orderId = quotation.get("id").asLong();

        // Same terms again is no change; a new rate is
        post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer,
                body("taxBreakup", Map.of("supplyType", "INTER_STATE", "gstRate", "5.0")), 409);
        JsonNode change = data(post("/v1/dealer-portal/quotations/" + orderId + "/change-requests", dealer,
                body("taxBreakup", Map.of("supplyType", "INTER_STATE", "gstRate", 18), "reason", "Wrong rate"), 201));
        assertThat(change.get("changes").get("taxBreakup").get("igst").decimalValue()).isEqualByComparingTo("189.00");
        assertThat(change.get("previous").get("taxBreakup").get("igst").decimalValue()).isEqualByComparingTo("52.50");

        patch("/v1/orders/change-requests/" + change.get("id").asLong() + "/decision", l2a.token(), body("decision", "APPROVED"), 200);
        JsonNode applied = data(get("/v1/orders/" + orderId, l1.token(), 200)).get("taxBreakup");
        assertThat(applied.get("gstRate").decimalValue()).isEqualByComparingTo("18");
        assertThat(applied.get("grandTotal").decimalValue()).isEqualByComparingTo("1239.00");
    }
}
