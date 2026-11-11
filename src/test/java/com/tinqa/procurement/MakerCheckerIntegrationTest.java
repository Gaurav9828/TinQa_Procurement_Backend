package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maker-checker on the two decisions that move money: approving a purchase order (POST /v1/orders/{id}/approval) and
 * approving a stock entry (POST /v1/stocks/{id}/approval, which books the purchase and settles supplier payments).
 * Whoever raised or last edited the record cannot decide it; another Admin L2 can.
 */
class MakerCheckerIntegrationTest extends IntegrationTestSupport {

    @Test
    void theAdminWhoRaisedAnOrderCannotApproveIt() throws Exception {
        long orderId = data(post("/v1/orders", l2a.token(), orderBody(createSupplier(), createItem(), "5"), 201)).get("id").asLong();
        moveOrder(orderId, l2a, "PENDING");

        JsonNode self = patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 403);
        assertThat(self.get("errorCode").asText()).isEqualTo("ORDER_APPROVAL_SELF_DECISION");

        JsonNode approved = data(patch("/v1/orders/" + orderId + "/approval", l2b.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200));
        assertThat(approved.get("orderStatus").asText()).isEqualTo("SUPPLIER_LEVEL_PENDING");
    }

    @Test
    void theAdminWhoSubmittedSomeoneElsesOrderCannotApproveIt() throws Exception {
        long orderId = createOrder(createSupplier(), createItem(), "5").get("id").asLong();
        // L1 drafted it; l2a submitted it for approval, so l2a is its last editor
        moveOrder(orderId, l2a, "PENDING");

        JsonNode self = patch("/v1/orders/" + orderId + "/approval", l2a.token(), body("decision", "CANCELLED", "rejectionReason", "No"), 403);
        assertThat(self.get("errorCode").asText()).isEqualTo("ORDER_APPROVAL_SELF_DECISION");
        patch("/v1/orders/" + orderId + "/approval", l2b.token(), body("decision", "SUPPLIER_LEVEL_PENDING"), 200);
    }

    @Test
    void theAdminWhoEnteredStockCannotApproveIt() throws Exception {
        String orderNumber = deliveredOrder(createSupplier(), createItem(), "4").get("orderNumber").asText();
        long stockId = data(post("/v1/stocks", l2a.token(), body("orderNumber", orderNumber,
                "unitsPassedTest", new BigDecimal("4"), "defectedUnits", BigDecimal.ZERO, "hasTested", true,
                "dateOfArrival", LocalDate.now().toString(), "barcodes", barcodes(4)), 201)).get("id").asLong();

        JsonNode self = post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 403);
        assertThat(self.get("errorCode").asText()).isEqualTo("STOCK_APPROVAL_SELF_DECISION");
        // Nothing was decided or booked
        assertThat(jdbc.queryForObject("SELECT approval_status FROM stock WHERE id = ?", String.class, stockId)).isEqualTo("PENDING");

        JsonNode approved = data(post("/v1/stocks/" + stockId + "/approval", l2b.token(), body("decision", "APPROVED"), 200));
        assertThat(approved.get("approvalStatus").asText()).isEqualTo("APPROVED");
    }

    @Test
    void theAdminWhoLastEditedPendingStockCannotApproveIt() throws Exception {
        long supplierId = createSupplier();
        long itemId = createItem();
        String orderNumber = deliveredOrder(supplierId, itemId, "6").get("orderNumber").asText();
        JsonNode stock = createStock(orderNumber, "5", "1");
        long stockId = stock.get("id").asLong();
        put("/v1/stocks/" + stockId, l2a.token(), body("batchNumber", "BATCH-MC1", "supplierId", supplierId, "itemId", itemId,
                "unitsPassedTest", 5, "defectedUnits", 1, "hasTested", true, "dateOfArrival", LocalDate.now().toString()), 200);

        JsonNode self = post("/v1/stocks/" + stockId + "/approval", l2a.token(), body("decision", "APPROVED"), 403);
        assertThat(self.get("errorCode").asText()).isEqualTo("STOCK_APPROVAL_SELF_DECISION");
        post("/v1/stocks/" + stockId + "/approval", l2b.token(), body("decision", "APPROVED"), 200);
    }
}
