package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Store sales agents find a product by scanning its unit's barcode, without seeing the barcode registry. */
class StoreUnitLookupIntegrationTest extends IntegrationTestSupport {

    private static final String LOOKUP = "/v1/store-sales/units/lookup?code=";

    private String staff(String designation) throws Exception {
        String username = uniq(designation.toLowerCase());
        insertEmployee(insertUser(username, "ADMIN_L1", "WEB", false, true), designation);
        return login(username, SEED_PASSWORD);
    }

    @Test
    void anAgentScansAProductUnitAndGetsOnlyItsProduct() throws Exception {
        String agent = staff("SALES_AGENT");
        List<String> units = insertProductUnits(81, "TinQa Hub", 2);

        // Scanners and typing send any case; the code is matched upper-case
        JsonNode unit = data(get(LOOKUP + units.get(0).toLowerCase(), agent, 200));
        assertThat(unit.get("barcode").asText()).isEqualTo(units.get(0));
        assertThat(unit.get("productId").asLong()).isEqualTo(81);
        assertThat(unit.get("productTitle").asText()).isEqualTo("TinQa Hub");
        assertThat(unit.get("inStock").asBoolean()).isTrue();
        assertThat(unit.get("note").isNull()).isTrue();
        // Nothing about parts, batches or dealers
        assertThat(unit.has("parts")).isFalse();
        assertThat(unit.has("events")).isFalse();
        // Agents still can't trace the barcode
        get("/v1/barcodes/trace/" + units.get(0), agent, 403);

        // A unit out of stock still names its product, with the reason
        jdbc.update("UPDATE barcodes SET status = 'REMOVED' WHERE code = ?", units.get(1));
        JsonNode removed = data(get(LOOKUP + units.get(1), agent, 200));
        assertThat(removed.get("productId").asLong()).isEqualTo(81);
        assertThat(removed.get("inStock").asBoolean()).isFalse();
        assertThat(removed.get("note").asText()).isNotBlank();
    }

    @Test
    void piecesUnknownAndMalformedCodesAreRefused() throws Exception {
        String agent = staff("SALES_AGENT");
        long itemId = itemWithApprovedStock("1");
        String piece = jdbc.queryForObject("SELECT code FROM barcodes WHERE item_id = ? LIMIT 1", String.class, itemId);

        assertThat(get(LOOKUP + piece, agent, 409).get("errorCode").asText()).isEqualTo("BARCODE_NOT_AVAILABLE");
        assertThat(get(LOOKUP + "NO-SUCH-UNIT-1", agent, 404).get("errorCode").asText()).isEqualTo("BARCODE_NOT_FOUND");
        get(LOOKUP + "ab", agent, 400);
    }

    @Test
    void onlyStaffWhoBookStoreOrdersMayLookUp() throws Exception {
        String code = insertProductUnits(82, "TinQa Lamp", 1).get(0);
        get(LOOKUP + code, staff("DELIVERY_RIDER"), 403);
        get(LOOKUP + code, staff("STORE_MANAGER"), 200);
    }
}
