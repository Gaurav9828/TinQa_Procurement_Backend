package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every piece of stock and every product unit carries its own barcode: entered when the units are created, locked
 * once approved, traced to batch, purchase order, supplier and product, and changed afterwards only by a Super Admin.
 */
@DisplayName("Barcodes")
@SuppressWarnings("unchecked")
class BarcodeIntegrationTest extends IntegrationTestSupport {

    private static final String MOVEMENTS = "/v1/internal/item-stock/movements";

    private static String held(long productId) {
        return "/v1/internal/item-stock/products/" + productId + "/held";
    }

    private JsonNode internal(HttpMethod method, String path, Object body, int status) throws Exception {
        var builder = MockMvcRequestBuilders.request(method, path).header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return perform(builder, status);
    }

    private Map<String, Object> stockBody(String orderNumber, int passed, Object barcodes) {
        Map<String, Object> request = body("orderNumber", orderNumber, "unitsPassedTest", passed, "defectedUnits", 0,
                "hasTested", true, "dateOfArrival", LocalDate.now().toString());
        if (barcodes != null) {
            request.put("barcodes", barcodes);
        }
        return request;
    }

    private String deliveredOrderNumber(String quantity) throws Exception {
        return deliveredOrder(createSupplier(), createItem(), quantity).get("orderNumber").asText();
    }

    // Builds `units` product units, each from one piece of the item, in a movement that consumes them
    private Map<String, Object> buildUnits(String movementId, long productId, long itemId, List<String> unitCodes,
                                           List<String> pieces, int stockBefore) {
        List<Map<String, Object>> added = new ArrayList<>();
        for (int i = 0; i < unitCodes.size(); i++) {
            added.add(body("barcode", unitCodes.get(i), "parts", List.of(body("itemId", itemId, "barcodes", List.of(pieces.get(i))))));
        }
        return body("movementId", movementId, "source", "ECOMMERCE_PRODUCT", "reference", "product:" + productId,
                "lines", List.of(body("itemId", itemId, "quantity", unitCodes.size())), "performedBy", "admin@tinqa.test",
                "productUnits", body("productId", productId, "productTitle", "Smart Panel " + productId,
                        "stockBefore", stockBefore, "stockAfter", stockBefore + unitCodes.size(), "added", added));
    }

    @Nested
    @DisplayName("Receiving stock")
    class Receiving {

        @Test
        void everyPassedUnitNeedsItsOwnValidUnusedBarcode() throws Exception {
            String orderNumber = deliveredOrderNumber("10");

            JsonNode missing = post("/v1/stocks", l1.token(), stockBody(orderNumber, 3, null), 400);
            assertThat(missing.get("errorCode").asText()).isEqualTo("BARCODES_REQUIRED");
            assertThat(missing.get("message").asText()).isEqualTo("Enter one barcode for each of the 3 passed units (0 entered)");
            post("/v1/stocks", l1.token(), stockBody(orderNumber, 3, barcodes(2)), 400);

            JsonNode malformed = post("/v1/stocks", l1.token(), stockBody(orderNumber, 2, List.of("SHORT", "-BAD-CODE-1")), 400);
            assertThat(malformed.get("errors").findValuesAsText("field")).containsExactly("barcodes[0]", "barcodes[1]");
            String code = barcodes(1).getFirst();
            JsonNode repeated = post("/v1/stocks", l1.token(), stockBody(orderNumber, 2, List.of(code, code.toLowerCase())), 400);
            assertThat(repeated.get("errors").get(0).get("message").asText()).isEqualTo("Barcode " + code + " is entered more than once");

            JsonNode fraction = post("/v1/stocks", l1.token(), body("orderNumber", orderNumber, "unitsPassedTest", new BigDecimal("1.5"),
                    "defectedUnits", 0, "hasTested", true, "dateOfArrival", LocalDate.now().toString(), "barcodes", barcodes(1)), 400);
            assertThat(fraction.get("errors").get(0).get("field").asText()).isEqualTo("unitsPassedTest");

            // Codes are stored upper-case, whatever case they were scanned or typed in
            List<String> codes = barcodes(3);
            JsonNode stock = createStock(orderNumber, "3", "0", codes.stream().map(String::toLowerCase).toList());
            long stockId = stock.get("id").asLong();
            assertThat(stockBarcodes(stockId, "PENDING")).containsExactlyElementsOf(codes);

            JsonNode taken = post("/v1/stocks", l1.token(), stockBody(deliveredOrderNumber("2"), 2, List.of(barcodes(1).getFirst(), codes.get(1))), 409);
            assertThat(taken.get("errorCode").asText()).isEqualTo("BARCODE_TAKEN");
            assertThat(taken.get("errors").get(0).get("field").asText()).isEqualTo("barcodes[1]");
        }

        @Test
        void approvalLocksTheBarcodesAndRejectionFreesThem() throws Exception {
            List<String> codes = barcodes(2);
            long approved = createStock(deliveredOrderNumber("2"), "2", "0", codes).get("id").asLong();
            JsonNode listed = data(get("/v1/barcodes?stockId=" + approved, l1.token(), 200));
            assertThat(listed.findValuesAsText("status")).containsOnly("PENDING");
            assertThat(listed.get(0).get("locked").asBoolean()).isFalse();

            post("/v1/stocks/" + approved + "/approval", l2a.token(), body("decision", "APPROVED"), 200);
            listed = data(get("/v1/barcodes?stockId=" + approved, l1.token(), 200));
            assertThat(listed.findValuesAsText("status")).containsOnly("IN_STOCK");
            assertThat(listed.get(0).get("locked").asBoolean()).isTrue();

            List<String> rejectedCodes = barcodes(2);
            long rejected = createStock(deliveredOrderNumber("2"), "2", "0", rejectedCodes).get("id").asLong();
            post("/v1/stocks/" + rejected + "/approval", l2a.token(), body("decision", "REJECTED", "rejectionReason", "Failed QA"), 200);
            assertThat(stockBarcodes(rejected, "VOID")).containsExactlyElementsOf(rejectedCodes);
            // A voided registration no longer holds its code
            createStock(deliveredOrderNumber("2"), "2", "0", rejectedCodes);
        }

        @Test
        void addingReducingAndCorrectingUnitsNamesTheirBarcodes() throws Exception {
            // 6 of 8 ordered units received, so more can be added
            long id = createStock(deliveredOrderNumber("8"), "6", "0").get("id").asLong();
            JsonNode stock = data(post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "APPROVED"), 200));
            List<String> inStock = stockBarcodes(id, "IN_STOCK");

            // Added units wait for L2 with the change: rejected, their barcodes are voided
            List<String> added = barcodes(1);
            JsonNode noCode = post("/v1/stocks/" + id + "/add-quantity", l1.token(), body("quantity", 1, "reason", "Recount"), 400);
            assertThat(noCode.get("errorCode").asText()).isEqualTo("BARCODES_REQUIRED");
            post("/v1/stocks/" + id + "/add-quantity", l1.token(), body("quantity", 1, "reason", "Recount", "barcodes", added), 200);
            assertThat(barcodeStatus(added.getFirst())).isEqualTo("PENDING");
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "REJECTED", "rejectionReason", "Not found"), 200);
            assertThat(barcodeStatus(added.getFirst())).isEqualTo("VOID");

            // Units taken out are named, and must be in stock in this entry
            JsonNode unnamed = post("/v1/stocks/" + id + "/reduce-quantity", l1.token(), body("quantity", 1, "reason", "Broken"), 400);
            assertThat(unnamed.get("errorCode").asText()).isEqualTo("BARCODES_REQUIRED");
            String elsewhere = stockBarcodes(approvedStock("1").get("id").asLong(), "IN_STOCK").getFirst();
            JsonNode foreign = post("/v1/stocks/" + id + "/reduce-quantity", l1.token(),
                    body("quantity", 1, "reason", "Broken", "barcodes", List.of(elsewhere)), 409);
            assertThat(foreign.get("errorCode").asText()).isEqualTo("BARCODE_NOT_AVAILABLE");
            post("/v1/stocks/" + id + "/reduce-quantity", l1.token(),
                    body("quantity", 1, "reason", "Broken", "barcodes", List.of(inStock.getFirst())), 200);
            assertThat(barcodeStatus(inStock.getFirst())).isEqualTo("REMOVED");

            // A count correction takes named units off until L2 decides; rejected, they come back
            Map<String, Object> correction = body("batchNumber", stock.get("batchNumber").asText(), "supplierId", stock.get("supplierId").asLong(),
                    "itemId", stock.get("itemId").asLong(), "unitsPassedTest", 5, "defectedUnits", 1, "hasTested", true,
                    "dateOfArrival", LocalDate.now().toString());
            JsonNode notNamed = put("/v1/stocks/" + id, l1.token(), correction, 400);
            assertThat(notNamed.get("errors").get(0).get("field").asText()).isEqualTo("removedBarcodes");
            correction.put("removedBarcodes", List.of(inStock.get(1)));
            assertThat(data(put("/v1/stocks/" + id, l1.token(), correction, 200)).get("availableUnits").decimalValue())
                    .isEqualByComparingTo("4");
            assertThat(barcodeStatus(inStock.get(1))).isEqualTo("WITHDRAWAL_PENDING");
            post("/v1/stocks/" + id + "/approval", l2a.token(), body("decision", "REJECTED", "rejectionReason", "Miscount"), 200);
            assertThat(barcodeStatus(inStock.get(1))).isEqualTo("IN_STOCK");
            assertThat(stockBarcodes(id, "IN_STOCK")).hasSize(5);
            assertThat(availableUnits(stock.get("itemId").asLong())).isEqualByComparingTo("5");
        }
    }

    @Nested
    @DisplayName("Product units")
    class ProductUnits {

        @Test
        void productUnitsAreBuiltFromScannedPiecesAndTracedBothWays() throws Exception {
            JsonNode stock = approvedStock("4");
            long item = stock.get("itemId").asLong();
            List<String> pieces = stockBarcodes(stock.get("id").asLong(), "IN_STOCK");
            long productId = next();
            List<String> units = barcodes(2);

            // Every piece used is named by barcode: stock with barcodes can't be taken without them
            Map<String, Object> unscanned = body("movementId", randomUuid(), "source", "ECOMMERCE_PRODUCT", "reference", "product:" + productId,
                    "lines", List.of(body("itemId", item, "quantity", 1)), "performedBy", "admin@tinqa.test");
            assertThat(internal(HttpMethod.POST, MOVEMENTS, unscanned, 400).get("errorCode").asText()).isEqualTo("BARCODES_REQUIRED");
            Map<String, Object> wrongCount = buildUnits(randomUuid(), productId, item, units, pieces, 0);
            ((Map<String, Object>) wrongCount.get("productUnits")).put("stockAfter", 5);
            internal(HttpMethod.POST, MOVEMENTS, wrongCount, 400);
            JsonNode wrongItem = internal(HttpMethod.POST, MOVEMENTS, buildUnits(randomUuid(), productId, createItem(), units, pieces, 0), 409);
            assertThat(wrongItem.get("errorCode").asText()).isEqualTo("BARCODE_NOT_AVAILABLE");

            String build = randomUuid();
            internal(HttpMethod.POST, MOVEMENTS, buildUnits(build, productId, item, units, pieces, 0), 200);
            assertThat(availableUnits(item)).isEqualByComparingTo("2");
            assertThat(barcodeStatus(pieces.get(0))).isEqualTo("FITTED");
            // A product created before the approval workflow counts as approved: its units are in stock at once
            assertThat(barcodeStatus(units.get(0))).isEqualTo("IN_STOCK");
            JsonNode reused = internal(HttpMethod.POST, MOVEMENTS, buildUnits(randomUuid(), productId, item, barcodes(1), pieces, 2), 409);
            assertThat(reused.get("errorCode").asText()).isEqualTo("BARCODE_NOT_AVAILABLE");

            // A product unit leads to every piece inside and where it came from...
            JsonNode product = data(get("/v1/barcodes/trace/" + units.get(0).toLowerCase(), l1.token(), 200));
            assertThat(product.get("unit").get("unitType").asText()).isEqualTo("PRODUCT_UNIT");
            assertThat(product.get("unit").get("productTitle").asText()).isEqualTo("Smart Panel " + productId);
            assertThat(product.get("parts")).hasSize(1);
            assertThat(product.get("parts").get(0).get("unit").get("code").asText()).isEqualTo(pieces.get(0));
            assertThat(product.get("parts").get(0).get("source").get("orderNumber").asText()).isNotBlank();
            assertThat(product.get("parts").get(0).get("source").get("supplierName").asText()).startsWith("Supplier ");
            // ...and a piece to the product unit it is fitted into, with its whole history
            JsonNode piece = data(get("/v1/barcodes/trace/" + pieces.get(0), l1.token(), 200));
            assertThat(piece.get("fittedInto").get("code").asText()).isEqualTo(units.get(0));
            assertThat(piece.get("unit").get("productTitle").asText()).isEqualTo("Smart Panel " + productId);
            assertThat(piece.get("source").get("batchNumber").asText()).isEqualTo(stock.get("batchNumber").asText());
            assertThat(piece.get("history").findValuesAsText("eventType")).containsExactly("REGISTERED", "APPROVED", "FITTED");
            JsonNode listed = data(get("/v1/barcodes/products/" + productId, l1.token(), 200));
            assertThat(listed).hasSize(2);
            assertThat(listed.get(1).get("parts").get(0).get("code").asText()).isEqualTo(pieces.get(1));
            get("/v1/barcodes/trace/NOSUCHCODE1", l1.token(), 404);
            get("/v1/barcodes/trace/bad", l1.token(), 400);

            // Reversing the movement undoes the barcodes too
            internal(HttpMethod.POST, MOVEMENTS + "/" + build + "/reverse", null, 200);
            assertThat(barcodeStatus(units.get(0))).isEqualTo("VOID");
            assertThat(barcodeStatus(pieces.get(0))).isEqualTo("IN_STOCK");
            assertThat(availableUnits(item)).isEqualByComparingTo("4");
        }

        @Test
        void unitsTakenOutReturnOrScrapTheirPieces() throws Exception {
            JsonNode stock = approvedStock("3");
            long item = stock.get("itemId").asLong();
            List<String> pieces = stockBarcodes(stock.get("id").asLong(), "IN_STOCK");
            long productId = next();
            List<String> units = barcodes(3);
            internal(HttpMethod.POST, MOVEMENTS, buildUnits(randomUuid(), productId, item, units, pieces, 0), 200);

            // Taken out with its pieces returned
            Map<String, Object> giveBack = body("movementId", randomUuid(), "source", "ECOMMERCE_PRODUCT", "reference", "product:" + productId,
                    "lines", List.of(body("itemId", item, "quantity", -1)), "performedBy", "admin@tinqa.test",
                    "productUnits", body("productId", productId, "stockBefore", 3, "stockAfter", 2, "removed", List.of()));
            JsonNode unnamed = internal(HttpMethod.POST, MOVEMENTS, giveBack, 400);
            assertThat(unnamed.get("errorCode").asText()).isEqualTo("BARCODES_REQUIRED");
            ((Map<String, Object>) giveBack.get("productUnits")).put("removed", List.of(units.get(0)));
            internal(HttpMethod.POST, MOVEMENTS, giveBack, 200);
            assertThat(barcodeStatus(units.get(0))).isEqualTo("REMOVED");
            assertThat(barcodeStatus(pieces.get(0))).isEqualTo("IN_STOCK");
            assertThat(availableUnits(item)).isEqualByComparingTo("1");

            // Scrapped: the pieces leave with the unit and nothing returns to stock
            Map<String, Object> scrap = body("movementId", randomUuid(), "source", "ECOMMERCE_PRODUCT", "reference", "product:" + productId,
                    "lines", List.of(), "performedBy", "admin@tinqa.test",
                    "productUnits", body("productId", productId, "stockBefore", 2, "stockAfter", 1, "removed", List.of(units.get(1)),
                            "returnParts", false));
            internal(HttpMethod.POST, MOVEMENTS, scrap, 200);
            assertThat(barcodeStatus(units.get(1))).isEqualTo("REMOVED");
            assertThat(barcodeStatus(pieces.get(1))).isEqualTo("REMOVED");
            assertThat(availableUnits(item)).isEqualByComparingTo("1");
        }

        @Test
        void whatAProductHoldsIsReadOnlyWithTheSharedKey() throws Exception {
            long productId = next();
            JsonNode none = data(internal(HttpMethod.GET, held(productId), null, 200));
            assertThat(none.get("heldUnitCodes").size()).isZero();
            assertThat(none.get("heldPiecesByItem").size()).isZero();
            assertThat(get(held(productId), null, 401).get("errorCode").asText()).isEqualTo("INTERNAL_AUTH_FAILED");
            get(held(productId), l2a.token(), 401);
        }

        @Test
        void aNewProductsUnitsAreLockedWhenTheProductIsApprovedAndWithdrawnWhenRejected() throws Exception {
            JsonNode stock = approvedStock("2");
            long item = stock.get("itemId").asLong();
            List<String> pieces = stockBarcodes(stock.get("id").asLong(), "IN_STOCK");

            for (String decision : List.of("APPROVED", "REJECTED")) {
                long productId = next();
                List<String> unit = barcodes(1);
                Map<String, Object> created = buildUnits(randomUuid(), productId, item, unit,
                        List.of(pieces.get(decision.equals("APPROVED") ? 0 : 1)), 0);
                ((Map<String, Object>) created.get("productUnits")).put("awaitingApproval", true);
                internal(HttpMethod.POST, MOVEMENTS, created, 200);
                assertThat(barcodeStatus(unit.getFirst())).isEqualTo("PENDING");
                // Ecommerce reads what the product holds: the pending unit and the piece inside it
                JsonNode held = data(internal(HttpMethod.GET, held(productId), null, 200));
                assertThat(held.get("heldUnitCodes").get(0).asText()).isEqualTo(unit.getFirst());
                assertThat(held.get("heldUnitCodes").size()).isEqualTo(1);
                assertThat(held.get("heldPiecesByItem").get(String.valueOf(item)).decimalValue()).isEqualByComparingTo("1");

                long requestId = data(post("/v1/product-approvals", l1.token(), body("ecommerceProductId", productId, "productTitle", "Panel " + productId,
                        "snapshot", body("title", "Panel " + productId, "price", 999, "stockQuantity", 1,
                                "components", List.of(body("itemId", item, "itemName", "Board", "quantity", 1)))), 201)).get("id").asLong();
                patch("/v1/product-approvals/" + requestId + "/decision", l2a.token(),
                        body("decision", decision, "reason", "Checked"), 200);
                held = data(internal(HttpMethod.GET, held(productId), null, 200));
                if (decision.equals("APPROVED")) {
                    assertThat(held.get("heldUnitCodes").size()).isEqualTo(1);
                    assertThat(barcodeStatus(unit.getFirst())).isEqualTo("IN_STOCK");
                    assertThat(data(get("/v1/barcodes/products/" + productId, l1.token(), 200)).get(0).get("unit").get("locked").asBoolean()).isTrue();
                } else {
                    assertThat(barcodeStatus(unit.getFirst())).isEqualTo("VOID");
                    assertThat(barcodeStatus(pieces.get(1))).isEqualTo("IN_STOCK");
                    // Released: it holds nothing, so Ecommerce may change its components without moving stock
                    assertThat(held.get("heldUnitCodes").size()).isZero();
                    assertThat(held.get("heldPiecesByItem").size()).isZero();
                }
            }
        }
    }

    @Nested
    @DisplayName("Corrections")
    class Corrections {

        @Test
        void lockedBarcodesAreCorrectedOnlyThroughASuperAdmin() throws Exception {
            long stockId = approvedStock("2").get("id").asLong();
            List<String> codes = stockBarcodes(stockId, "IN_STOCK");
            long barcodeId = data(get("/v1/barcodes/trace/" + codes.get(0), l1.token(), 200)).get("unit").get("id").asLong();
            String corrected = barcodes(1).getFirst();

            // Level 1 asks; a Level 2 Super Admin decides
            JsonNode asked = post("/v1/barcodes/requests", l1.token(), body("requestType", "CORRECTION", "barcodeId", barcodeId,
                    "newCode", corrected, "reason", "Label misprinted"), 201);
            assertThat(asked.get("message").asText()).isEqualTo("Barcode request sent to a Super Admin for approval");
            long requestId = data(asked).get("id").asLong();
            assertThat(data(asked).get("status").asText()).isEqualTo("PENDING");
            assertThat(notificationTitles(l2a)).contains("Barcode request awaiting approval");
            JsonNode twice = post("/v1/barcodes/requests", l1.token(), body("requestType", "CORRECTION", "barcodeId", barcodeId,
                    "newCode", barcodes(1).getFirst(), "reason", "Again"), 409);
            assertThat(twice.get("errorCode").asText()).isEqualTo("BARCODE_CORRECTION_PENDING");
            post("/v1/barcodes/requests", l1.token(), body("requestType", "CORRECTION", "barcodeId", barcodeId,
                    "newCode", codes.get(1), "reason", "Taken"), 409);

            get("/v1/barcodes/requests", l1.token(), 403);
            patch("/v1/barcodes/requests/" + requestId + "/decision", l1.token(), body("decision", "APPROVED"), 403);
            assertThat(data(get("/v1/barcodes/requests", l2a.token(), 200)).findValues("id").stream().map(JsonNode::asLong)).contains(requestId);
            patch("/v1/barcodes/requests/" + requestId + "/decision", l2a.token(), body("decision", "REJECTED"), 400);
            JsonNode approved = data(patch("/v1/barcodes/requests/" + requestId + "/decision", l2a.token(), body("decision", "APPROVED"), 200));
            assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
            assertThat(notificationTitles(l1)).contains("Barcode request approved");
            patch("/v1/barcodes/requests/" + requestId + "/decision", l2a.token(), body("decision", "APPROVED"), 409);

            JsonNode traced = data(get("/v1/barcodes/trace/" + corrected, l1.token(), 200));
            assertThat(traced.get("unit").get("id").asLong()).isEqualTo(barcodeId);
            assertThat(traced.get("history").findValuesAsText("eventType")).contains("CODE_CORRECTED");
            assertThat(traced.get("requests").get(0).get("status").asText()).isEqualTo("APPROVED");
            get("/v1/barcodes/trace/" + codes.get(0), l1.token(), 404);

            // A Super Admin's own correction applies at once
            String again = barcodes(1).getFirst();
            JsonNode direct = post("/v1/barcodes/requests", l2a.token(), body("requestType", "CORRECTION", "barcodeId", barcodeId,
                    "newCode", again, "reason", "Relabelled"), 201);
            assertThat(direct.get("message").asText()).isEqualTo("Barcode change applied");
            assertThat(data(direct).get("decidedBy").asLong()).isEqualTo(l2a.userId());
            assertThat(barcodeStatus(again)).isEqualTo("IN_STOCK");
        }

        @Test
        void stockReceivedWithoutBarcodesIsLabelledThroughASuperAdmin() throws Exception {
            long item = itemWithStockWithoutBarcodes("3");
            long stockId = jdbc.queryForObject("SELECT id FROM stock WHERE item_id = ?", Long.class, item);
            assertThat(data(get("/v1/stocks/" + stockId, l1.token(), 200)).get("unitsWithoutBarcode").decimalValue()).isEqualByComparingTo("3");

            JsonNode tooMany = post("/v1/barcodes/requests", l1.token(), body("requestType", "ASSIGNMENT", "stockId", stockId,
                    "codes", barcodes(4), "reason", "Old stock"), 400);
            assertThat(tooMany.get("errorCode").asText()).isEqualTo("BARCODES_REQUIRED");
            List<String> codes = barcodes(2);
            long requestId = data(post("/v1/barcodes/requests", l1.token(), body("requestType", "ASSIGNMENT", "stockId", stockId,
                    "codes", codes, "reason", "Old stock"), 201)).get("id").asLong();
            JsonNode rejected = data(patch("/v1/barcodes/requests/" + requestId + "/decision", l2b.token(),
                    body("decision", "REJECTED", "reason", "Wrong labels"), 200));
            assertThat(rejected.get("decisionReason").asText()).isEqualTo("Wrong labels");
            assertThat(stockBarcodes(stockId, "IN_STOCK")).isEmpty();

            requestId = data(post("/v1/barcodes/requests", l1.token(), body("requestType", "ASSIGNMENT", "stockId", stockId,
                    "codes", codes, "reason", "Old stock"), 201)).get("id").asLong();
            patch("/v1/barcodes/requests/" + requestId + "/decision", l2b.token(), body("decision", "APPROVED"), 200);
            assertThat(stockBarcodes(stockId, "IN_STOCK")).containsExactlyElementsOf(codes);
            assertThat(data(get("/v1/stocks/" + stockId, l1.token(), 200)).get("unitsWithoutBarcode").decimalValue()).isEqualByComparingTo("1");
            assertThat(data(get("/v1/barcodes?itemId=" + item, l1.token(), 200))).hasSize(2);
        }

        @Test
        void barcodesNeedTheirPermissions() throws Exception {
            get("/v1/barcodes/trace/TQ00000001", inspectorToken(), 403);
            get("/v1/barcodes?stockId=1", null, 401);
            get("/v1/barcodes", l1.token(), 400);
        }
    }
}
