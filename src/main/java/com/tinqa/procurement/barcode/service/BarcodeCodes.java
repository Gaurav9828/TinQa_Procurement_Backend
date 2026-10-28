package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.validation.ValidationPatterns;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How barcodes are written: trimmed and upper-case, so a code scanned or typed in any case is the same code.
 */
public final class BarcodeCodes {

    /** Most codes one request can carry. */
    public static final int MAX_CODES = 5000;

    private static final Pattern FORMAT = Pattern.compile(ValidationPatterns.BARCODE);

    private BarcodeCodes() {
    }

    /** The code as stored, or null for a blank one. */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String normalized) {
        return normalized != null && FORMAT.matcher(normalized).matches();
    }

    /**
     * Normalizes a list of codes, collecting an error for each blank, malformed or repeated one (field[i]).
     * A null list is treated as empty.
     */
    public static List<String> normalizeAll(List<String> codes, String field, List<ApiResponse.ApiErrorItem> errors) {
        List<String> normalized = new ArrayList<>();
        if (codes == null) {
            return normalized;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = normalize(codes.get(i));
            String path = field + "[" + i + "]";
            if (code == null) {
                errors.add(ApiErrors.error(path, "Barcode is required"));
            } else if (!isValid(code)) {
                errors.add(ApiErrors.error(path, "Barcode " + code + " " + ValidationPatterns.BARCODE_MESSAGE));
            } else if (!seen.add(code)) {
                errors.add(ApiErrors.error(path, "Barcode " + code + " is entered more than once"));
            } else {
                normalized.add(code);
            }
        }
        return normalized;
    }

    /**
     * Pieces are counted whole: every one carries its own barcode, so a unit count with a fraction can't be labelled.
     * Returns the count, or -1 (with an error collected) when it isn't a whole number.
     */
    public static int wholeUnits(BigDecimal units, String field, List<ApiResponse.ApiErrorItem> errors) {
        BigDecimal stripped = units.stripTrailingZeros();
        if (stripped.scale() > 0 || units.signum() < 0) {
            errors.add(ApiErrors.error(field, "Must be a whole number of pieces: every piece gets its own barcode"));
            return -1;
        }
        if (stripped.compareTo(BigDecimal.valueOf(MAX_CODES)) > 0) {
            errors.add(ApiErrors.error(field, "At most " + MAX_CODES + " pieces can be barcoded at once"));
            return -1;
        }
        return stripped.intValueExact();
    }

}
