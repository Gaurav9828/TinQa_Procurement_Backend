package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ecommerce checks a stock entry draft of product units before saving it: which unit codes are taken and which
 * piece codes can't be fitted, with the messages the movement itself would give on submit. Nothing is locked or
 * written.
 */
@DisplayName("Product unit draft check")
class ProductUnitCheckIntegrationTest extends IntegrationTestSupport {

    private static final String CHECK = "/v1/internal/item-stock/product-units/check";

    @Autowired
    private MeterRegistry meters;

    private JsonNode check(Object body, int status) throws Exception {
        return perform(request(body).header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY), status);
    }

    private MockHttpServletRequestBuilder request(Object body) throws Exception {
        return MockMvcRequestBuilders.post(CHECK).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private static Map<String, Object> unit(String barcode, Map<String, Object>... parts) {
        return body("barcode", barcode, "parts", Arrays.asList(parts));
    }

    private static Map<String, Object> part(Long itemId, String... barcodes) {
        return body("itemId", itemId, "barcodes", Arrays.asList(barcodes));
    }

    private static Map<String, Object> draft(Object... units) {
        return body("units", Arrays.asList(units));
    }

    private List<String> pieces(long itemId) {
        return jdbc.queryForList("SELECT code FROM barcodes WHERE item_id = ? AND status = 'IN_STOCK' ORDER BY id", String.class, itemId);
    }

    private String itemName(long itemId) {
        return jdbc.queryForObject("SELECT name FROM items WHERE id = ?", String.class, itemId);
    }

    private static List<String> codes(JsonNode response) {
        return data(response).get("problems").findValuesAsText("code");
    }

    private static List<String> messages(JsonNode response) {
        return data(response).get("problems").findValuesAsText("message");
    }

    @Test
    void aDraftOfFreeUnitsAndPiecesInStockHasNoProblems() throws Exception {
        long itemId = itemWithApprovedStock("2");
        List<String> pieces = pieces(itemId);
        List<String> units = barcodes(2);

        // Codes in any case, as scanned or typed
        JsonNode response = check(draft(unit(units.get(0).toLowerCase(), part(itemId, pieces.get(0).toLowerCase())),
                unit(units.get(1), part(itemId, pieces.get(1)))), 200);

        assertThat(data(response).get("problems")).isEmpty();
        // Nothing was written: the pieces are still in stock and the unit codes still free
        assertThat(pieces(itemId)).containsExactlyElementsOf(pieces);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM barcodes WHERE code IN (?, ?)", Long.class, units.get(0), units.get(1)))
                .isZero();
    }

    @Test
    void eachUnusableCodeIsReportedOnceInTheOrderItAppears() throws Exception {
        long itemId = itemWithApprovedStock("3");
        long otherItemId = itemWithApprovedStock("1");
        List<String> pieces = pieces(itemId);
        String otherPiece = pieces(otherItemId).getFirst();
        List<String> productUnits = insertProductUnits(91, "Smart Panel", 2);
        String takenUnit = productUnits.get(0);
        String unknown = barcodes(1).getFirst();
        // One piece is fitted into a product unit already
        String fitted = pieces.get(1);
        jdbc.update("UPDATE barcodes SET status = 'FITTED', fitted_into_id = (SELECT id FROM barcodes WHERE code = ?) WHERE code = ?",
                productUnits.get(1), fitted);
        String freeUnit = barcodes(1).getFirst();

        JsonNode response = check(draft(
                unit(takenUnit, part(itemId, pieces.get(0), unknown)),
                unit(freeUnit, part(itemId, otherPiece, fitted, productUnits.get(1)))), 200);

        assertThat(codes(response)).containsExactly(takenUnit, unknown, otherPiece, fitted, productUnits.get(1));
        assertThat(messages(response)).containsExactly(
                "Barcode " + takenUnit + " is already in use",
                "Barcode " + unknown + " is not registered",
                "Barcode " + otherPiece + " is a piece of " + itemName(otherItemId) + ", not of the component it is listed under",
                "Barcode " + fitted + " (" + itemName(itemId) + ") is not in stock: it is fitted in a product",
                "Barcode " + productUnits.get(1) + " is a product unit, not a component piece");

        // A code repeated in the draft is reported once (repeats themselves are Ecommerce's to report)
        JsonNode repeated = check(draft(unit(takenUnit), unit(takenUnit, part(itemId, pieces.get(0), pieces.get(0)))), 200);
        assertThat(codes(repeated)).containsExactly(takenUnit);
    }

    @Test
    void blankMalformedAndUnfinishedEntriesOfAPartlyFilledDraftAreSkipped() throws Exception {
        long itemId = itemWithApprovedStock("1");
        String piece = pieces(itemId).getFirst();
        List<Object> pieceCodes = new ArrayList<>(Arrays.asList("", null, "  ", "ab", piece));

        JsonNode response = check(draft(
                body("barcode", null, "parts", List.of(body("itemId", itemId, "barcodes", pieceCodes))),
                body("barcode", "   ", "parts", List.of()),
                body("barcode", "-BAD-CODE-"),
                // The component is not chosen yet: the piece is still checked, only not against an item
                body("barcode", "", "parts", List.of(body("barcodes", List.of(piece))))), 200);
        assertThat(data(response).get("problems")).isEmpty();

        assertThat(data(check(draft(), 200)).get("problems")).isEmpty();
    }

    @Test
    void oversizedDraftsAreRefused() throws Exception {
        List<Object> units = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            units.add(unit(null));
        }
        JsonNode tooManyUnits = check(body("units", units), 400);
        assertThat(tooManyUnits.get("errors").findValuesAsText("field")).containsExactly("units");

        // 5 units of 1001 pieces each would also break the per-component limit; 6 components of 900 stay within it
        List<String> codes = barcodes(900);
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            big.add(body("barcode", null, "parts", List.of(body("itemId", 1, "barcodes", codes))));
        }
        JsonNode tooManyCodes = check(body("units", big), 400);
        assertThat(tooManyCodes.get("message").asText()).isEqualTo("At most 5000 barcodes can be checked at once (5400 entered)");

        check(body("units", null), 400);
    }

    @Test
    void onlyServicesWithTheInternalKeyMayCheck() throws Exception {
        Object body = draft(unit(barcodes(1).getFirst()));
        perform(request(body), 401);
        perform(request(body).header(INTERNAL_KEY_HEADER, "wrong-key"), 401);
        // An admin's token is not enough either
        perform(request(body).header("Authorization", "Bearer " + l2a.token()), 401);
    }

    @Test
    void aLargeDraftIsCheckedInOneSqlStatement() throws Exception {
        long itemId = itemWithApprovedStock("3");
        long otherItemId = itemWithApprovedStock("3");
        List<String> pieces = pieces(itemId);
        List<String> otherPieces = pieces(otherItemId);
        List<String> taken = insertProductUnits(92, "Smart Hub", 2);
        List<Object> units = new ArrayList<>();
        List<String> unitCodes = barcodes(200);
        for (int i = 0; i < unitCodes.size(); i++) {
            // Real pieces of two items (some under the wrong item) and unknown codes, so item names are needed
            units.add(unit(i < 2 ? taken.get(i) : unitCodes.get(i),
                    part(itemId, pieces.get(i % 3), barcodes(1).getFirst()),
                    part(otherItemId, otherPieces.get(i % 3), pieces.get((i + 1) % 3))));
        }

        DistributionSummary statements = statementsSummary();
        long callsBefore = statements == null ? 0 : statements.count();
        double totalBefore = statements == null ? 0 : statements.totalAmount();
        JsonNode response = check(body("units", units), 200);
        statements = statementsSummary();

        assertThat(codes(response)).contains(taken.get(0), taken.get(1)).hasSize(2 + 200 + 3);
        assertThat(statements).isNotNull();
        assertThat(statements.count() - callsBefore).isEqualTo(1);
        assertThat(statements.totalAmount() - totalBefore).as("SQL statements for one check").isLessThanOrEqualTo(2);
    }

    // What ApiCallRegistry records per request for this endpoint
    private DistributionSummary statementsSummary() {
        return meters.find("api.db.statements").tag("method", "POST").tag("uri", CHECK).summary();
    }
}
