package com.tinqa.procurement.common.validation;

import java.util.List;

/**
 * One canonical form per phone number, so 9876543210, 09876543210 and +91 98765-43210 are recognised as the
 * same number.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    // Indian mobiles become +91XXXXXXXXXX; other numbers keep their +<country code> form without separators
    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String compact = phone.replaceAll("[\\s()-]", "");
        if (compact.isEmpty()) {
            return null;
        }
        if (compact.matches("^0?[6-9]\\d{9}$")) {
            return "+91" + compact.substring(compact.length() - 10);
        }
        if (compact.matches("^91[6-9]\\d{9}$")) {
            return "+" + compact;
        }
        return compact;
    }

    // Spellings older rows may have been stored with, for duplicate lookups
    public static List<String> storedVariants(String phone) {
        String normalized = normalize(phone);
        if (normalized == null) {
            return List.of();
        }
        if (normalized.matches("^\\+91[6-9]\\d{9}$")) {
            String local = normalized.substring(3);
            return List.of(normalized, local, "0" + local, "91" + local);
        }
        return List.of(normalized);
    }
}
