package com.tinqa.procurement.common.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.order.dto.OrderDTOs;
import com.tinqa.procurement.security.dto.ChangePasswordRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeStringDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .registerModule(new InputSafetyJacksonConfig().inputSafetyModule());

    @Test
    void unsafeFieldIsRejectedWithItsPath() {
        UnsafeInputException exception = assertThrows(UnsafeInputException.class,
                () -> mapper.readValue("{\"name\":\"<script>alert(1)</script>\"}", DealerDTOs.CreateRequest.class));

        assertEquals("name", exception.getFieldPath());
        assertEquals("must not contain HTML tags", exception.getReason());
    }

    @Test
    void stringsInsideFreeFormMapsAreScreenedToo() {
        UnsafeInputException exception = assertThrows(UnsafeInputException.class, () -> mapper.readValue(
                "{\"additionalInfo\":{\"notes\":[\"fine\",\"<img src=x onerror=alert(1)>\"]}}", OrderDTOs.CreateRequest.class));

        assertTrue(exception.getFieldPath().startsWith("additionalInfo.notes"), exception.getFieldPath());
    }

    @Test
    void passwordsAcceptAnyCharacter() throws Exception {
        ChangePasswordRequest request = mapper.readValue(
                "{\"currentPassword\":\"<a>Old#1\",\"newPassword\":\"${x}New#1\",\"confirmNewPassword\":\"${x}New#1\"}",
                ChangePasswordRequest.class);

        assertEquals("<a>Old#1", request.getCurrentPassword());
    }

    @Test
    void mapsLinkFieldAcceptsLinksButNotScripts() throws Exception {
        DealerDTOs.CreateRequest ok = mapper.readValue("{\"googleMapsUrl\":\"https://maps.app.goo.gl/x1\"}", DealerDTOs.CreateRequest.class);
        assertEquals("https://maps.app.goo.gl/x1", ok.getGoogleMapsUrl());

        assertThrows(UnsafeInputException.class,
                () -> mapper.readValue("{\"googleMapsUrl\":\"javascript:alert(1)\"}", DealerDTOs.CreateRequest.class));
        assertThrows(UnsafeInputException.class,
                () -> mapper.readValue("{\"street\":\"see https://evil.example\"}", DealerDTOs.CreateRequest.class));
    }

    @Test
    void proposedProductChangesAreKeptVerbatimButChangeSummaryIsStillScreened() throws Exception {
        var request = mapper.readValue("""
                {"proposedChanges":{"description":"Line 1\\nDetails at https://tinqa.com/x","warranties":[{"generalTermsAndConditions":"T&C"}]},
                 "changeSummary":[{"label":"Image","oldValue":"https://cdn.tinqa.com/a.png","newValue":"https://cdn.tinqa.com/b.png"}]}
                """, com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs.SubmitRequest.class);
        assertEquals("Line 1\nDetails at https://tinqa.com/x", request.getProposedChanges().get("description").asText());
        assertEquals("https://cdn.tinqa.com/b.png", request.getChangeSummary().getFirst().getNewValue());

        assertThrows(UnsafeInputException.class, () -> mapper.readValue(
                "{\"changeSummary\":[{\"label\":\"Title\",\"newValue\":\"<script>alert(1)</script>\"}]}",
                com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs.SubmitRequest.class));
    }
}
