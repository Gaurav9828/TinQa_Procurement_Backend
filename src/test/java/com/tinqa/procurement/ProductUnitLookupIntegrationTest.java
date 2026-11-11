package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Before an order moves to SHIPPED, Ecommerce looks up the serials (product unit barcodes) being shipped: which product
 * each belongs to and whether it is still held as stock. Pieces, unknown and voided codes are left out.
 */
class ProductUnitLookupIntegrationTest extends IntegrationTestSupport {

    private static final String LOOKUP = "/v1/internal/item-stock/product-units/lookup";

    private JsonNode lookup(List<String> codes, boolean withKey, int status) throws Exception {
        var request = MockMvcRequestBuilders.post(LOOKUP).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body("codes", codes)));
        if (withKey) {
            request.header(INTERNAL_KEY_HEADER, INTERNAL_API_KEY);
        }
        return perform(request, status);
    }

    @Test
    void namesTheProductOfEachLiveUnitAndWhetherItIsHeld() throws Exception {
        List<String> units = insertProductUnits(91, "TinQa Clock", 2);
        jdbc.update("UPDATE barcodes SET status = 'ON_DEMO' WHERE code = ?", units.get(1));
        long itemId = itemWithApprovedStock("1");
        String piece = jdbc.queryForObject("SELECT code FROM barcodes WHERE item_id = ? LIMIT 1", String.class, itemId);

        JsonNode found = data(lookup(List.of(units.get(0).toLowerCase(), units.get(1), piece, "NO-SUCH-1"), true, 200)).get("units");

        assertThat(found).hasSize(2);
        assertThat(found.findValuesAsText("code")).containsExactlyInAnyOrder(units.get(0), units.get(1));
        for (JsonNode unit : found) {
            assertThat(unit.get("productId").asLong()).isEqualTo(91);
            assertThat(unit.get("held").asBoolean()).isEqualTo(unit.get("code").asText().equals(units.get(0)));
        }
    }

    @Test
    void onlyOtherServicesMayLookUp() throws Exception {
        lookup(List.of("ANY-CODE-1"), false, 401);
    }
}
