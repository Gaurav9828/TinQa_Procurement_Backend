package com.tinqa.procurement.order.tax;

import com.tinqa.procurement.common.exception.ApiErrors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The optional GST breakup of a quotation, kept in the order's {@code taxBreakup}. Quoted prices are before GST, as on a
 * B2B purchase: the taxable value is the order's total (unit price x quantity + shipment, since freight billed with the
 * goods is taxed at the goods' rate as a composite supply, CGST Act s.8), and GST is added on top of it.
 *
 * <p>The law decides the shape: one rate per item, and either CGST + SGST (each half the rate, supplier and us in the
 * same state) or IGST (different states), never both (IGST Act s.7-8). Amounts may be typed to match the dealer's own
 * rounding, but must be within {@link #TOLERANCE} of the rate; when the order's taxable value later changes they are
 * worked out again from the rate.
 *
 * <p>Stored keys: supplyType, gstRate, hsnSac, taxableValue, cgst, sgst, igst, cess, totalTax, grandTotal.
 * An empty map means no breakup was given.
 */
public final class QuotationTax {

    public static final String SUPPLY_TYPE = "supplyType";
    public static final String GST_RATE = "gstRate";
    public static final String HSN_SAC = "hsnSac";
    public static final String TAXABLE_VALUE = "taxableValue";
    public static final String CGST = "cgst";
    public static final String SGST = "sgst";
    public static final String IGST = "igst";
    public static final String CESS = "cess";
    public static final String TOTAL_TAX = "totalTax";
    public static final String GRAND_TOTAL = "grandTotal";

    public static final String INTRA_STATE = "INTRA_STATE";
    public static final String INTER_STATE = "INTER_STATE";

    /** Rounding a dealer may apply to each tax amount, and an invoice to its total. */
    public static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    /** Rates in force since 22 Sep 2025, as in the Web UI's productTax.ts and Ecommerce BE GstRates; change all three together. */
    public static final List<BigDecimal> GST_RATES = List.of(new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("1.5"),
            new BigDecimal("3"), new BigDecimal("5"), new BigDecimal("18"), new BigDecimal("40"));

    private static final Pattern HSN_SAC_PATTERN = Pattern.compile("^[0-9]{4}([0-9]{2}){0,2}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO_HUNDRED = new BigDecimal("200");

    private QuotationTax() {
    }

    public static boolean present(Map<String, Object> breakup) {
        return breakup != null && breakup.get(GST_RATE) != null;
    }

    /**
     * Checks a breakup sent with a quotation against the order's taxable value and returns it as stored: every amount
     * filled in, rounded to paise, with the totals. Null or empty means no breakup and returns an empty map.
     */
    public static Map<String, Object> normalise(Map<String, Object> sent, BigDecimal taxableValue) {
        if (sent == null || sent.values().stream().allMatch(QuotationTax::blank)) {
            return new LinkedHashMap<>();
        }
        String supplyType = text(sent.get(SUPPLY_TYPE));
        if (!INTRA_STATE.equals(supplyType) && !INTER_STATE.equals(supplyType)) {
            throw field(SUPPLY_TYPE, "Choose how GST applies: CGST + SGST (dealer in our state) or IGST (dealer in another state).");
        }
        BigDecimal rate = amount(sent, GST_RATE);
        if (rate == null) {
            throw field(GST_RATE, "Choose the GST rate.");
        }
        BigDecimal allowedRate = GST_RATES.stream().filter(allowed -> allowed.compareTo(rate) == 0).findFirst()
                .orElseThrow(() -> field(GST_RATE, "GST rate must be one of the rates in force: " + ratesLabel() + "."));
        String hsnSac = text(sent.get(HSN_SAC));
        if (hsnSac != null && !HSN_SAC_PATTERN.matcher(hsnSac).matches()) {
            throw field(HSN_SAC, "HSN/SAC must be 4, 6 or 8 digits.");
        }

        BigDecimal taxable = money(taxableValue);
        boolean intraState = INTRA_STATE.equals(supplyType);
        BigDecimal cgst = BigDecimal.ZERO.setScale(2);
        BigDecimal sgst = cgst;
        BigDecimal igst = cgst;
        if (intraState) {
            refuseAmount(sent, IGST, "IGST doesn't apply when the dealer is in our state: GST is CGST + SGST.");
            BigDecimal half = money(taxable.multiply(allowedRate).divide(TWO_HUNDRED, 2, RoundingMode.HALF_UP));
            cgst = typedOrCalculated(sent, CGST, half, "CGST");
            sgst = typedOrCalculated(sent, SGST, cgst, "SGST");
            if (sgst.compareTo(cgst) != 0) {
                throw field(SGST, "SGST must equal CGST: each is half of the GST rate.");
            }
        } else {
            refuseAmount(sent, CGST, "CGST doesn't apply when the dealer is in another state: GST is IGST.");
            refuseAmount(sent, SGST, "SGST doesn't apply when the dealer is in another state: GST is IGST.");
            BigDecimal full = money(taxable.multiply(allowedRate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            igst = typedOrCalculated(sent, IGST, full, "IGST");
        }
        BigDecimal cess = Objects.requireNonNullElse(amount(sent, CESS), BigDecimal.ZERO);
        if (cess.signum() < 0) {
            throw field(CESS, "Cess cannot be negative.");
        }
        cess = money(cess);
        BigDecimal totalTax = cgst.add(sgst).add(igst).add(cess);

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(SUPPLY_TYPE, supplyType);
        stored.put(GST_RATE, allowedRate);
        if (hsnSac != null) {
            stored.put(HSN_SAC, hsnSac);
        }
        stored.put(TAXABLE_VALUE, taxable);
        stored.put(CGST, cgst);
        stored.put(SGST, sgst);
        stored.put(IGST, igst);
        stored.put(CESS, cess);
        stored.put(TOTAL_TAX, totalTax);
        stored.put(GRAND_TOTAL, taxable.add(totalTax));
        return stored;
    }

    /**
     * The stored breakup worked out again for a new taxable value (the quantity, price or shipment changed): GST from the
     * rate, cess kept. Unchanged when there is no breakup or the taxable value is the same.
     */
    public static Map<String, Object> recalculate(Map<String, Object> stored, BigDecimal taxableValue) {
        if (!present(stored)) {
            return stored;
        }
        BigDecimal storedTaxable = amount(stored, TAXABLE_VALUE);
        if (storedTaxable != null && storedTaxable.compareTo(taxableValue) == 0) {
            return stored;
        }
        Map<String, Object> rateOnly = new LinkedHashMap<>(stored);
        rateOnly.keySet().removeAll(List.of(CGST, SGST, IGST, TAXABLE_VALUE, TOTAL_TAX, GRAND_TOTAL));
        return normalise(rateOnly, taxableValue);
    }

    /** The same GST terms: supply type, rate, HSN/SAC and amounts (the totals follow from them). */
    public static boolean sameTerms(Map<String, Object> a, Map<String, Object> b) {
        if (!present(a) || !present(b)) {
            return present(a) == present(b);
        }
        return Objects.equals(text(a.get(SUPPLY_TYPE)), text(b.get(SUPPLY_TYPE)))
                && Objects.equals(text(a.get(HSN_SAC)), text(b.get(HSN_SAC)))
                && List.of(GST_RATE, CGST, SGST, IGST, CESS).stream().allMatch(key -> amountOf(a, key).compareTo(amountOf(b, key)) == 0);
    }

    /** What the dealer's invoice should total: the taxable value plus the quoted GST, or the order total without a breakup. */
    public static BigDecimal grandTotal(Map<String, Object> breakup, BigDecimal orderTotal) {
        BigDecimal grandTotal = present(breakup) ? amount(breakup, GRAND_TOTAL) : null;
        return money(grandTotal != null ? grandTotal : orderTotal);
    }

    /** One amount of a stored breakup, zero when absent. */
    public static BigDecimal amountOf(Map<String, Object> breakup, String key) {
        BigDecimal value = breakup == null ? null : amount(breakup, key);
        return money(Objects.requireNonNullElse(value, BigDecimal.ZERO));
    }

    private static BigDecimal typedOrCalculated(Map<String, Object> sent, String key, BigDecimal calculated, String label) {
        BigDecimal typed = amount(sent, key);
        if (typed == null) {
            return calculated;
        }
        if (typed.signum() < 0) {
            throw field(key, label + " cannot be negative.");
        }
        if (typed.subtract(calculated).abs().compareTo(TOLERANCE) > 0) {
            throw field(key, label + " at the chosen rate is ₹" + calculated.toPlainString() + "; ₹" + money(typed).toPlainString()
                    + " is more than ₹1 away from it. Check the rate or the amount.");
        }
        return money(typed);
    }

    private static void refuseAmount(Map<String, Object> sent, String key, String message) {
        BigDecimal value = amount(sent, key);
        if (value != null && value.signum() != 0) {
            throw field(key, message);
        }
    }

    private static BigDecimal amount(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (blank(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException exception) {
            throw field(key, "Must be a number.");
        }
    }

    private static String text(Object value) {
        return blank(value) ? null : value.toString().trim();
    }

    private static boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String ratesLabel() {
        return String.join(", ", GST_RATES.stream().map(rate -> rate.toPlainString() + "%").toList());
    }

    private static RuntimeException field(String key, String message) {
        return ApiErrors.fieldError("taxBreakup." + key, message);
    }
}
