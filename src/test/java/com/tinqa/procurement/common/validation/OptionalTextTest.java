package com.tinqa.procurement.common.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalTextTest {

    @Test
    void blankAndMissingMeanNone() {
        assertNull(OptionalText.blankToNull(null));
        assertNull(OptionalText.blankToNull("   "));
        assertEquals("Kumar", OptionalText.blankToNull(" Kumar "));
    }

    @Test
    void onlyRealDifferencesCountAsChanges() {
        assertFalse(OptionalText.changes(null, ""), "blank vs none is not a change (no needless approval request)");
        assertFalse(OptionalText.changes("", null), "not sent is not a change");
        assertFalse(OptionalText.changes("Kumar", " Kumar "));
        assertTrue(OptionalText.changes("Kumar", ""), "blank removes an existing middle name");
        assertTrue(OptionalText.changes(null, "Kumar"));
    }
}
