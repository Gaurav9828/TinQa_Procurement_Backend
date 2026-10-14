package com.tinqa.procurement.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "09876543210", "+919876543210", "919876543210", "+91 98765-43210", "(+91) 98765 43210"})
    void indianSpellingsShareOneForm(String phone) {
        assertEquals("+919876543210", PhoneNumbers.normalize(phone));
    }

    @Test
    void internationalNumbersKeepTheirCountryCode() {
        assertEquals("+14155552671", PhoneNumbers.normalize("+1 415-555-2671"));
    }

    @Test
    void lookupCoversOlderStoredSpellings() {
        assertEquals(List.of("+919876543210", "9876543210", "09876543210", "919876543210"), PhoneNumbers.storedVariants("98765 43210"));
    }
}
