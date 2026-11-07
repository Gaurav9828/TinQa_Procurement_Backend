package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /v1/approvals/summary counts each queue in one SQL statement (approval_queue_counts, V15). Every count must equal
 * what the queue's own list endpoint returns, so a badge never disagrees with the list behind it.
 */
class ApprovalSummaryIntegrationTest extends IntegrationTestSupport {

    private static final String SUMMARY = "/v1/approvals/summary";

    @Test
    void everyCountMatchesItsListEndpoint() throws Exception {
        // Something in a few queues: an order waiting for L2, a staff quotation (not a supplier's, so not counted), stock
        long supplierId = createSupplier();
        long itemId = createItem();
        long pendingOrder = createOrder(supplierId, itemId, "4").get("id").asLong();
        moveOrder(pendingOrder, l1, "PENDING");
        createOrder(supplierId, itemId, "2");
        createStock(deliveredOrder(supplierId, itemId, "3").get("orderNumber").asText(), "3", "0");
        // A supplier advance waiting for confirmation
        long approvedOrder = createOrder(supplierId, itemId, "2").get("id").asLong();
        moveOrder(approvedOrder, l1, "PENDING");
        patch("/v1/orders/" + approvedOrder + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
        post("/v1/orders/" + approvedOrder + "/payments", l1.token(), body("kind", "PAYMENT", "milestone", "ADVANCE",
                "amount", new java.math.BigDecimal("10.00"), "method", "CASH", "paidOn", java.time.LocalDate.now().toString()), 201);

        JsonNode counts = data(get(SUMMARY, l2a.token(), 200));

        Map<String, Integer> fromLists = new LinkedHashMap<>();
        fromLists.put("DOCUMENTS", size("/v1/documents?status=WAITING_FOR_APPROVAL"));
        fromLists.put("PROFILE_CHANGES", size("/admin/profile-approvals"));
        fromLists.put("STOCK", size("/v1/stocks?status=PENDING"));
        JsonNode quotations = data(get("/v1/orders?status=QUOTATION", l2a.token(), 200));
        fromLists.put("QUOTATIONS", (int) StreamSupport.stream(quotations.spliterator(), false)
                .filter(order -> order.get("raisedBySupplier").asBoolean() && !order.get("supplierChangePending").asBoolean())
                .count());
        fromLists.put("ORDERS", size("/v1/orders?status=PENDING"));
        fromLists.put("RETURNS", size("/v1/orders?status=RETURN_REQUESTED"));
        fromLists.put("PRODUCTS", size("/v1/product-approvals?status=PENDING"));
        fromLists.put("EMPLOYEES", size("/v1/admin/employees/pending-approvals"));
        fromLists.put("SUPPLIER_LOGINS", size("/v1/admin/supplier-logins?status=APPROVAL_PENDING"));
        fromLists.put("QUOTATION_CHANGES", size("/v1/orders/change-requests?status=PENDING"));
        fromLists.put("BARCODES", size("/v1/barcodes/requests?status=PENDING"));
        fromLists.put("CLIENT_DEMOS", size("/v1/admin/client-demos/waiting"));
        fromLists.put("SUPPLIER_PAYMENTS", size("/v1/orders/payments?status=PENDING_APPROVAL"));

        // A Super Admin decides every queue, so every queue is there, in the same order
        assertThat(iterable(counts.fieldNames())).containsExactlyElementsOf(fromLists.keySet());
        fromLists.forEach((queue, listed) ->
                assertThat(counts.get(queue).asInt()).as(queue).isEqualTo(listed));
        assertThat(counts.get("ORDERS").asInt()).isPositive();
        assertThat(counts.get("STOCK").asInt()).isPositive();
        assertThat(counts.get("SUPPLIER_PAYMENTS").asInt()).isPositive();
    }

    @Test
    void queuesTheCallerCannotDecideAreLeftOut() throws Exception {
        // A delivery rider decides nothing
        String username = uniq("rider");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        insertEmployee(userId, "DELIVERY_RIDER");
        JsonNode counts = data(get(SUMMARY, login(username, SEED_PASSWORD), 200));
        assertThat(counts.size()).isZero();

        get(SUMMARY, null, 401);
    }

    private int size(String path) throws Exception {
        return data(get(path, l2a.token(), 200)).size();
    }

    private static Iterable<String> iterable(java.util.Iterator<String> names) {
        return () -> names;
    }
}
