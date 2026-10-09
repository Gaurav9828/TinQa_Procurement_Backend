package com.tinqa.procurement.common.validation;

import com.tinqa.procurement.dealer.dto.DealerDTOs;
import com.tinqa.procurement.employee.dto.CreateEmployeeRequest;
import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.order.dto.OrderDTOs;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // ---------- dealer ----------

    private DealerDTOs.CreateRequest dealer() {
        DealerDTOs.CreateRequest d = new DealerDTOs.CreateRequest();
        d.setName("Summit Components Pvt. Ltd.");
        d.setEmail("sales@summit.co.in");
        d.setPhoneNumber("+919832671239");
        d.setStreet("#12, 3rd Cross, Sector-62");
        d.setCity("Noida");
        d.setState("Uttar Pradesh");
        d.setCountry("India");
        d.setPincode("201301");
        d.setGstin("27AAPFU0939F1ZV");
        d.setPanNumber("AAPFU0939F");
        d.setGoogleMapsUrl("https://maps.app.goo.gl/abc123");
        d.setBusinessSince(2010);
        d.setCategoryIds(Set.of(1L));
        return d;
    }

    @Test
    void validIndianDealerPasses() {
        assertEquals(Map.of(), errors(dealer()));
    }

    @Test
    void indianAddressNeedsRealStateAndPinCode() {
        DealerDTOs.CreateRequest d = dealer();
        d.setState("Uttar Prades");
        d.setPincode("012345");
        assertEquals(Set.of("state", "pincode"), errors(d).keySet());
    }

    @Test
    void foreignAddressUsesGenericPostalCode() {
        DealerDTOs.CreateRequest d = dealer();
        d.setCountry("Germany");
        d.setState("Bavaria");
        d.setPincode("80331");
        d.setGstin(null);
        d.setPanNumber(null);
        assertEquals(Map.of(), errors(d));
    }

    @Test
    void unknownCountryIsRejected() {
        DealerDTOs.CreateRequest d = dealer();
        d.setCountry("Indi");
        assertTrue(errors(d).containsKey("country"));
    }

    @Test
    void gstinChecksumAndPanMustAgree() {
        DealerDTOs.CreateRequest badChecksum = dealer();
        badChecksum.setGstin("27AAPFU0939F1ZA");
        assertTrue(errors(badChecksum).get("gstin").contains("check digit"));

        DealerDTOs.CreateRequest panMismatch = dealer();
        panMismatch.setPanNumber("KESPS4811R");
        assertTrue(errors(panMismatch).get("panNumber").contains("AAPFU0939F"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "5832671239", "+91 98326 71239", "98326712390", "+0123456789", "phone"})
    void invalidPhonesAreRejected(String phone) {
        DealerDTOs.CreateRequest d = dealer();
        d.setPhoneNumber(phone);
        assertTrue(errors(d).containsKey("phoneNumber"), phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9832671239", "+919832671239", "09832671239", "+14155552671", "+447911123456"})
    void validPhonesAreAccepted(String phone) {
        DealerDTOs.CreateRequest d = dealer();
        d.setPhoneNumber(phone);
        assertFalse(errors(d).containsKey("phoneNumber"), phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b", "a..b@x.com", "user@domain", "@x.com", "user@-x.com", "user name@x.com"})
    void invalidEmailsAreRejected(String email) {
        DealerDTOs.CreateRequest d = dealer();
        d.setEmail(email);
        assertTrue(errors(d).containsKey("email"), email);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://maps.google.com/x", "https://maps.google.com.evil.io/x", "https://evil.io/maps/x",
            "https://user@maps.google.com/x", "https://www.google.com/search?q=x", "https://goo.gl/abc"})
    void onlyHttpsGoogleMapsLinksAreAccepted(String url) {
        DealerDTOs.CreateRequest d = dealer();
        d.setGoogleMapsUrl(url);
        assertTrue(errors(d).containsKey("googleMapsUrl"), url);
    }

    @Test
    void businessSinceCannotBeInTheFuture() {
        DealerDTOs.CreateRequest d = dealer();
        d.setBusinessSince(Year.now().getValue() + 1);
        assertTrue(errors(d).containsKey("businessSince"));
    }

    // ---------- item MRP ----------

    private ItemDTOs.CreateRequest item(String mrp) {
        ItemDTOs.CreateRequest i = new ItemDTOs.CreateRequest();
        i.setCategoryId(1L);
        i.setName("Summit X Mother Board");
        i.setSku("MB-001");
        i.setUnitOfMeasure("PCS");
        i.setMrp(new BigDecimal(mrp));
        i.setCountryOfOrigin("India");
        return i;
    }

    @Test
    void mrpUpToOneCroreIsAccepted() {
        assertEquals(Map.of(), errors(item("10000000.00")));
    }

    @Test
    void mrpAboveOneCroreIsRejectedWithClearMessage() {
        String message = errors(item("10000000.01")).get("mrp");
        assertEquals("MRP cannot exceed ₹1,00,00,000 (1 crore) and can have at most 2 decimal places", message);
    }

    // ---------- orders / dates ----------

    @Test
    void futureOrderDateIsRejected() {
        OrderDTOs.CreateRequest o = OrderDTOs.CreateRequest.builder()
                .dealerId(1L).itemId(1L).orderQuantity(BigDecimal.TEN).unitType("PCS")
                .unitPrice(new BigDecimal("100")).shipmentPrice(BigDecimal.ZERO)
                .orderDate(LocalDate.now().plusDays(1))
                .expectedDelivery(LocalDate.now().plusDays(10))
                .build();
        assertEquals(Set.of("orderDate"), errors(o).keySet());
    }

    @Test
    void unitPriceAboveOneCroreIsRejected() {
        OrderDTOs.CreateRequest o = OrderDTOs.CreateRequest.builder()
                .dealerId(1L).itemId(1L).orderQuantity(BigDecimal.ONE).unitType("PCS")
                .unitPrice(new BigDecimal("20000000")).shipmentPrice(BigDecimal.ZERO).orderDate(LocalDate.now())
                .build();
        assertEquals(Set.of("unitPrice"), errors(o).keySet());
    }

    @Test
    void freeFormMapsAreBounded() {
        OrderDTOs.CreateRequest o = OrderDTOs.CreateRequest.builder()
                .dealerId(1L).itemId(1L).orderQuantity(BigDecimal.ONE).unitType("PCS")
                .unitPrice(BigDecimal.ONE).shipmentPrice(BigDecimal.ZERO).orderDate(LocalDate.now())
                .taxBreakup(Map.of("bad<key>", "x"))
                .build();
        assertTrue(errors(o).containsKey("taxBreakup"));
    }

    // ---------- employees ----------

    @Test
    void employeeMustBeAnAdultWithPastJoiningDate() {
        CreateEmployeeRequest e = CreateEmployeeRequest.builder()
                .username("rohan.mehta").email("rohan@tinqa.com").firstName("Rohan").lastName("Mehta")
                .dateOfBirth(LocalDate.now().minusYears(15)).gender("MALE").designation("Buyer").department("Supply Chain")
                .employmentType("FULL_TIME").joiningDate(LocalDate.now().plusDays(3)).phone("9876543213")
                .salaryCurrency("XYZ").role("SUPERUSER")
                .build();
        assertEquals(Set.of("dateOfBirth", "joiningDate", "salaryCurrency", "role"), errors(e).keySet());
    }

    private Map<String, String> errors(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        return violations.stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage, (a, b) -> a + " | " + b));
    }
}
