package com.tinqa.procurement.order.change;

import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.order.payment.PaymentMethod;
import com.tinqa.procurement.order.payment.PaymentPlan;
import com.tinqa.procurement.order.tax.QuotationTax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the supplier quoted, kept on the order ({@code supplier_quoted_terms}) so that before accepting an approved order
 * the supplier sees every term TinQa changed: what they quoted next to what it is now (e.g. advance 30% → 20%).
 *
 * <p>It is the supplier's own word on each term: set when the supplier raises the quotation, and updated with the
 * fields of each change of theirs that staff approve. Staff edits never touch it. Null when staff prepared the
 * quotation, and then nothing is compared. Values are stored as text so they read back exactly as written.
 */
public final class SupplierQuotedTerms {

    static final String ITEM_ID = "itemId";
    static final String ITEM_NAME = "itemName";
    static final String ORDER_QUANTITY = "orderQuantity";
    static final String UNIT_TYPE = "unitType";
    static final String UNIT_PRICE = "unitPrice";
    static final String SHIPMENT_PRICE = "shipmentPrice";
    static final String SUPPLY_TYPE = "supplyType";
    static final String GST_RATE = "gstRate";
    static final String EXPECTED_DELIVERY = "expectedDelivery";
    static final String ADVANCE_PERCENT = "advancePercent";
    static final String MIDTERM_PERCENT = "midtermPercent";
    static final String EXPECTED_PAYMENT_METHOD = "expectedPaymentMethod";

    /** Longest summary written to the order's status history (its reason column holds 500 characters). */
    private static final int SUMMARY_LIMIT = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Labels as the Web UI's paymentPlan.ts PAYMENT_METHOD_LABELS. */
    private static final Map<PaymentMethod, String> METHOD_LABELS = Map.of(
            PaymentMethod.CASH, "Cash",
            PaymentMethod.ONLINE, "Online payment",
            PaymentMethod.BANK_TRANSFER, "Bank to bank transfer",
            PaymentMethod.CHEQUE, "Cheque");

    /**
     * One term TinQa changed: {@code field} is a fixed key (ITEM, ORDER_QUANTITY, UNIT_TYPE, UNIT_PRICE, SHIPMENT_PRICE,
     * TOTAL_PRICE, GST, EXPECTED_DELIVERY, ADVANCE_PERCENT, MIDTERM_PERCENT, FINAL_PERCENT, PAYMENT_METHOD), the values
     * are ready to show ("30%", "₹1200.00", "7 Nov 2026", "Not given").
     */
    public record TermChange(String field, String label, String quoted, String current) {
    }

    private SupplierQuotedTerms() {
    }

    /** The order's terms now, in the stored shape. */
    public static Map<String, Object> of(Order order) {
        Map<String, Object> terms = new LinkedHashMap<>();
        put(terms, ITEM_ID, order.getItem().getId());
        put(terms, ITEM_NAME, order.getItem().getName());
        put(terms, ORDER_QUANTITY, order.getOrderQuantity());
        put(terms, UNIT_TYPE, order.getUnitType());
        put(terms, UNIT_PRICE, order.getUnitPrice());
        put(terms, SHIPMENT_PRICE, order.getShipmentPrice());
        if (QuotationTax.present(order.getTaxBreakup())) {
            put(terms, SUPPLY_TYPE, order.getTaxBreakup().get(QuotationTax.SUPPLY_TYPE));
            put(terms, GST_RATE, order.getTaxBreakup().get(QuotationTax.GST_RATE));
        }
        put(terms, EXPECTED_DELIVERY, order.getExpectedDelivery());
        put(terms, ADVANCE_PERCENT, order.getAdvancePercent());
        put(terms, MIDTERM_PERCENT, order.getMidtermPercent());
        put(terms, EXPECTED_PAYMENT_METHOD, order.getExpectedPaymentMethod() == null ? null : order.getExpectedPaymentMethod().name());
        return terms;
    }

    /**
     * The supplier's word once a change of theirs was applied to the order: the fields they changed as they now are, the
     * rest as they quoted them. A quotation staff prepared takes every term as it now is: the supplier saw them all
     * when asking for the change.
     */
    public static Map<String, Object> afterSupplierChange(Order order, OrderChangeDTOs.Terms changes) {
        Map<String, Object> now = of(order);
        Map<String, Object> quoted = order.getSupplierQuotedTerms();
        if (quoted == null) {
            return now;
        }
        Map<String, Object> merged = new LinkedHashMap<>(quoted);
        for (String key : changedKeys(changes)) {
            if (now.containsKey(key)) {
                merged.put(key, now.get(key));
            } else {
                merged.remove(key);
            }
        }
        return merged;
    }

    /** Every term that differs from what the supplier quoted, in the order a quotation reads; empty without a quote. */
    public static List<TermChange> changes(Order order) {
        Map<String, Object> quoted = order.getSupplierQuotedTerms();
        if (quoted == null) {
            return List.of();
        }
        Map<String, Object> now = of(order);
        List<TermChange> changes = new ArrayList<>();
        if (!Objects.equals(text(quoted, ITEM_ID), text(now, ITEM_ID))) {
            changes.add(new TermChange("ITEM", "Item", orNotGiven(text(quoted, ITEM_NAME)), orNotGiven(text(now, ITEM_NAME))));
        }
        compareNumber(changes, quoted, now, ORDER_QUANTITY, "ORDER_QUANTITY", "Quantity", Quantities::plain);
        compareText(changes, quoted, now, UNIT_TYPE, "UNIT_TYPE", "Unit", Function.identity());
        compareNumber(changes, quoted, now, UNIT_PRICE, "UNIT_PRICE", "Unit price (before GST)", SupplierQuotedTerms::rupees);
        compareNumber(changes, quoted, now, SHIPMENT_PRICE, "SHIPMENT_PRICE", "Shipment", SupplierQuotedTerms::rupees);
        BigDecimal quotedTotal = total(quoted);
        BigDecimal currentTotal = total(now);
        if (quotedTotal != null && currentTotal != null && quotedTotal.compareTo(currentTotal) != 0) {
            changes.add(new TermChange("TOTAL_PRICE", "Total before GST", rupees(quotedTotal), rupees(currentTotal)));
        }
        String quotedGst = gst(quoted);
        String currentGst = gst(now);
        if (!quotedGst.equals(currentGst)) {
            changes.add(new TermChange("GST", "GST", quotedGst, currentGst));
        }
        compareText(changes, quoted, now, EXPECTED_DELIVERY, "EXPECTED_DELIVERY", "Expected delivery",
                value -> LocalDate.parse(value).format(DATE));
        compareNumber(changes, quoted, now, ADVANCE_PERCENT, "ADVANCE_PERCENT", "Advance payment", SupplierQuotedTerms::percent);
        compareNumber(changes, quoted, now, MIDTERM_PERCENT, "MIDTERM_PERCENT", "Midterm payment (once shipped)", SupplierQuotedTerms::percent);
        BigDecimal quotedFinal = PaymentPlan.finalPercent(number(quoted, ADVANCE_PERCENT), number(quoted, MIDTERM_PERCENT));
        BigDecimal currentFinal = PaymentPlan.finalPercent(number(now, ADVANCE_PERCENT), number(now, MIDTERM_PERCENT));
        if (quotedFinal != null && currentFinal != null && quotedFinal.compareTo(currentFinal) != 0) {
            changes.add(new TermChange("FINAL_PERCENT", "Final payment (after testing)", percent(quotedFinal), percent(currentFinal)));
        }
        compareText(changes, quoted, now, EXPECTED_PAYMENT_METHOD, "PAYMENT_METHOD", "Payment method",
                value -> METHOD_LABELS.getOrDefault(PaymentMethod.valueOf(value), value));
        return changes;
    }

    /** "Advance payment 30% → 20%; Final payment (after testing) 40% → 50%", cut to fit the status history. */
    public static String summary(List<TermChange> changes) {
        String text = changes.stream().map(change -> change.label() + " " + change.quoted() + " → " + change.current())
                .collect(Collectors.joining("; "));
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT - 1) + "…";
    }

    /** "advance payment, final payment (after testing)" for notifications. */
    public static String labels(List<TermChange> changes) {
        return changes.stream().map(change -> change.label().toLowerCase(Locale.ENGLISH)).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> changedKeys(OrderChangeDTOs.Terms changes) {
        Set<String> keys = new LinkedHashSet<>();
        if (changes.getItemId() != null) {
            keys.add(ITEM_ID);
            keys.add(ITEM_NAME);
        }
        if (changes.getOrderQuantity() != null) keys.add(ORDER_QUANTITY);
        if (changes.getUnitType() != null) keys.add(UNIT_TYPE);
        if (changes.getUnitPrice() != null) keys.add(UNIT_PRICE);
        if (changes.getShipmentPrice() != null) keys.add(SHIPMENT_PRICE);
        if (changes.getTaxBreakup() != null) {
            keys.add(SUPPLY_TYPE);
            keys.add(GST_RATE);
        }
        if (changes.getExpectedDelivery() != null) keys.add(EXPECTED_DELIVERY);
        if (changes.getAdvancePercent() != null) keys.add(ADVANCE_PERCENT);
        if (changes.getMidtermPercent() != null) keys.add(MIDTERM_PERCENT);
        if (changes.getExpectedPaymentMethod() != null) keys.add(EXPECTED_PAYMENT_METHOD);
        return keys;
    }

    private static void put(Map<String, Object> terms, String key, Object value) {
        if (value == null) {
            return;
        }
        terms.put(key, value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : value.toString());
    }

    private static String text(Map<String, Object> terms, String key) {
        Object value = terms.get(key);
        return value == null ? null : value.toString();
    }

    private static BigDecimal number(Map<String, Object> terms, String key) {
        String value = text(terms, key);
        return value == null ? null : new BigDecimal(value);
    }

    private static void compareText(List<TermChange> changes, Map<String, Object> quoted, Map<String, Object> now, String key,
                                    String field, String label, Function<String, String> format) {
        String before = text(quoted, key);
        String after = text(now, key);
        if (!Objects.equals(before, after)) {
            changes.add(new TermChange(field, label, before == null ? "Not given" : format.apply(before),
                    after == null ? "Not given" : format.apply(after)));
        }
    }

    private static void compareNumber(List<TermChange> changes, Map<String, Object> quoted, Map<String, Object> now, String key,
                                      String field, String label, Function<BigDecimal, String> format) {
        BigDecimal before = number(quoted, key);
        BigDecimal after = number(now, key);
        boolean same = before == null ? after == null : after != null && before.compareTo(after) == 0;
        if (!same) {
            changes.add(new TermChange(field, label, before == null ? "Not given" : format.apply(before),
                    after == null ? "Not given" : format.apply(after)));
        }
    }

    // Unit price x quantity + shipment, as Order.recalculateTotalPrice
    private static BigDecimal total(Map<String, Object> terms) {
        BigDecimal price = number(terms, UNIT_PRICE);
        BigDecimal quantity = number(terms, ORDER_QUANTITY);
        BigDecimal shipment = number(terms, SHIPMENT_PRICE);
        return price == null || quantity == null || shipment == null ? null : price.multiply(quantity).add(shipment);
    }

    // "18% · CGST + SGST", "18% · IGST" or "No GST"
    private static String gst(Map<String, Object> terms) {
        BigDecimal rate = number(terms, GST_RATE);
        if (rate == null) {
            return "No GST";
        }
        String supply = QuotationTax.INTER_STATE.equals(text(terms, SUPPLY_TYPE)) ? "IGST" : "CGST + SGST";
        return percent(rate) + " · " + supply;
    }

    private static String orNotGiven(String value) {
        return value == null ? "Not given" : value;
    }

    private static String rupees(BigDecimal amount) {
        return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + "%";
    }
}
