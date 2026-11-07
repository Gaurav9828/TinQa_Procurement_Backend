package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An order's flow history (GET /v1/orders/{id}/timeline, and the supplier's own GET /v1/supplier-portal/orders/{id}/timeline):
 * who submitted, approved and accepted it, and every step of its payments as a branch. Staff see names and roles; the
 * supplier sees roles only, and of the payments only the steps from the payment on.
 */
class OrderTimelineIntegrationTest extends IntegrationTestSupport {

    @Test
    void theWholeFlowIsOneHistoryWithAPaymentBranch() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
        long orderId = data(post("/v1/supplier-portal/quotations", supplier.token(), quotation(createItem()), 201)).get("id").asLong();
        // A Level 1 Supplier Manager approves the quotation, a Super Admin gives the final approval, the supplier accepts
        String supplierManager = staff("SUPPLIER_MANAGER", "ADMIN_L1");
        patch("/v1/orders/" + orderId + "/status", supplierManager, body("status", "PENDING"), 200);
        patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        post("/v1/supplier-portal/orders/" + orderId + "/response", supplier.token(), body("decision", "ACCEPT"), 200);

        // The advance: requested, approved, paid, not received, sent again, received
        long paymentId = data(post("/v1/orders/" + orderId + "/payments", l1.token(),
                body("kind", "PAYMENT", "milestone", "ADVANCE", "method", "CHEQUE", "note", "As per quotation"), 201)).get("id").asLong();
        patch("/v1/orders/payments/" + paymentId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
        patch("/v1/orders/payments/" + paymentId + "/paid", l1.token(), body("reference", "CHQ-100", "paidOn", LocalDate.now().toString()), 200);
        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", false, "reason", "Cheque bounced"), 200);
        perform(MockMvcRequestBuilders.multipart("/v1/orders/payments/" + paymentId + "/resend")
                .param("reference", "CHQ-101").param("paidOn", LocalDate.now().toString()).param("note", "New cheque issued")
                .header("Authorization", "Bearer " + l1.token()), 200);
        patch("/v1/supplier-portal/payments/" + paymentId + "/receipt", supplier.token(), body("received", true), 200);

        JsonNode staffView = data(get("/v1/orders/" + orderId + "/timeline", l1.token(), 200));
        assertThat(staffView.get("orderId").asLong()).isEqualTo(orderId);
        List<JsonNode> trunk = branch(staffView, null);
        assertThat(titles(trunk)).containsExactly(
                "The supplier submitted the quotation",
                "Supplier Manager approved the quotation and sent it for final approval",
                "Super Admin gave the final approval; the order went to the supplier to accept",
                "The supplier accepted the order");
        assertThat(trunk.get(1).get("actorRole").asText()).isEqualTo("Supplier Manager");
        assertThat(trunk.get(1).get("actorName").isNull()).isFalse();
        assertThat(trunk.get(0).get("actorRole").asText()).isEqualTo("Supplier");
        assertThat(trunk.get(0).get("actorName").isNull()).isTrue();
        assertThat(trunk).allMatch(event -> event.get("kind").asText().equals("STATUS") && event.get("tone").asText().equals("DONE"));

        List<JsonNode> payment = branch(staffView, "payment-" + paymentId);
        assertThat(titles(payment)).containsExactly(
                "Super Admin requested the advance payment",
                "Super Admin approved the payment request",
                "Super Admin paid the supplier",
                "The supplier said the payment did not arrive",
                "Super Admin checked the payment and sent it again",
                "The supplier confirmed the payment was received");
        assertThat(payment.get(0).get("branchTitle").asText()).startsWith("Advance payment ₹").endsWith("(cheque)");
        assertThat(payment.get(0).get("detail").asText()).isEqualTo("As per quotation");
        assertThat(payment.get(2).get("detail").asText()).contains("Cheque number: CHQ-100");
        assertThat(payment.get(3).get("detail").asText()).isEqualTo("Cheque bounced");
        assertThat(payment.get(3).get("tone").asText()).isEqualTo("REJECTED");
        assertThat(payment.get(5).get("tone").asText()).isEqualTo("DONE");
        // The branch starts after the supplier accepted the order (oldest first)
        List<String> ids = ids(staffView);
        assertThat(ids.indexOf(payment.get(0).get("id").asText())).isGreaterThan(ids.indexOf(trunk.get(3).get("id").asText()));

        // The supplier: roles only, and of the payment only what happened from the payment on
        JsonNode supplierView = data(get("/v1/supplier-portal/orders/" + orderId + "/timeline", supplier.token(), 200));
        supplierView.get("events").forEach(event -> assertThat(event.get("actorName").isNull()).as(event.get("title").asText()).isTrue());
        assertThat(titles(branch(supplierView, null))).hasSize(4);
        assertThat(titles(branch(supplierView, "payment-" + paymentId))).containsExactly(
                "Super Admin paid the supplier",
                "The supplier said the payment did not arrive",
                "Super Admin checked the payment and sent it again",
                "The supplier confirmed the payment was received");
        // What the requester wrote stays with staff; what was sent with the payment is shown
        assertThat(branch(supplierView, "payment-" + paymentId).get(2).get("detail").asText()).contains("New cheque issued");
    }

    @Test
    void eachSideReachesOnlyItsOwnTimeline() throws Exception {
        SupplierLogin supplier = supplierWithLogin();
        long orderId = createOrder(supplier.supplierId(), createItem(), "3").get("id").asLong();
        // A staff-made quotation: created, not submitted, by a role
        JsonNode created = data(get("/v1/orders/" + orderId + "/timeline", l1.token(), 200)).get("events").get(0);
        assertThat(created.get("title").asText()).isEqualTo("Super Admin created the quotation");
        assertThat(created.get("branchId").isNull()).isTrue();

        get("/v1/supplier-portal/orders/" + orderId + "/timeline", supplier.token(), 200);
        get("/v1/supplier-portal/orders/" + orderId + "/timeline", supplierWithLogin().token(), 404);
        get("/v1/supplier-portal/orders/" + orderId + "/timeline", l1.token(), 403);
        get("/v1/orders/" + orderId + "/timeline", supplier.token(), 403);
        get("/v1/orders/" + Long.MAX_VALUE + "/timeline", l1.token(), 404);
        get("/v1/orders/" + orderId + "/timeline", null, 401);
    }

    private Map<String, Object> quotation(long itemId) {
        return body("itemId", itemId, "orderQuantity", new BigDecimal("10"), "unitType", "PCS",
                "unitPrice", new BigDecimal("100.00"), "shipmentPrice", new BigDecimal("50.00"), "note", "Ex-works Pune",
                "priceChangeReason", "Bulk rate", "advancePercent", new BigDecimal("30"), "midtermPercent", new BigDecimal("20"),
                "expectedPaymentMethod", "CHEQUE");
    }

    private String staff(String designation, String level) throws Exception {
        String username = uniq(designation.toLowerCase().replace("_", ""));
        long userId = insertUser(username, level, "WEB", false, true);
        insertEmployee(userId, designation);
        return login(username, SEED_PASSWORD);
    }

    private static List<JsonNode> branch(JsonNode timeline, String branchId) {
        List<JsonNode> events = new ArrayList<>();
        for (JsonNode event : timeline.get("events")) {
            boolean onBranch = branchId == null ? event.get("branchId").isNull() : branchId.equals(event.get("branchId").asText());
            if (onBranch) {
                events.add(event);
            }
        }
        return events;
    }

    private static List<String> titles(List<JsonNode> events) {
        return events.stream().map(event -> event.get("title").asText()).toList();
    }

    private static List<String> ids(JsonNode timeline) {
        List<String> ids = new ArrayList<>();
        timeline.get("events").forEach(event -> ids.add(event.get("id").asText()));
        return ids;
    }
}
