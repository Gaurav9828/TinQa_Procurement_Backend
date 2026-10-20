package com.tinqa.procurement.order.invoice;

import com.tinqa.procurement.order.tax.QuotationTax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares the figures read from a dealer's invoice with what the quotation says it should be. Amounts may differ by
 * up to {@link QuotationTax#TOLERANCE} for rounding (an invoice total is often rounded to the rupee).
 */
public final class InvoiceVerifier {

    /**
     * What the invoice should show.
     *
     * @param taxQuoted whether the quotation has a GST breakup; without one no GST is expected on the invoice
     * @param dealerGstin the dealer's GSTIN on record, or null when there is none to check
     */
    public record Expected(BigDecimal grandTotal, boolean taxQuoted, BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst,
                           BigDecimal igst, BigDecimal cess, String dealerGstin) {
    }

    public record Result(InvoiceVerificationStatus status, List<InvoiceCheck> checks) {
    }

    private InvoiceVerifier() {
    }

    public static Result verify(Expected expected, InvoiceFigures found) {
        List<InvoiceCheck> checks = new ArrayList<>();
        if (!found.readable()) {
            checks.add(InvoiceCheck.builder().key(InvoiceCheck.TEXT).label("Readable text").ok(false)
                    .message("No text could be read from the PDF: it is a scanned image or protected. Check it by hand.").build());
            return new Result(InvoiceVerificationStatus.UNVERIFIED, checks);
        }

        checks.add(amountCheck(InvoiceCheck.GRAND_TOTAL, "Invoice total", expected.grandTotal(), found.grandTotal(), true,
                expected.taxQuoted() ? "the quoted price plus GST" : "the quoted price (no GST was quoted)"));
        if (expected.taxQuoted()) {
            // Not every invoice prints the taxable value on its own line; the total and the taxes still check it
            checks.add(amountCheck(InvoiceCheck.TAXABLE_VALUE, "Taxable value", expected.taxableValue(),
                    found.taxableValue(), false, null));
        }
        taxCheck(checks, InvoiceCheck.CGST, "CGST", expected.cgst(), found.cgst(), expected);
        taxCheck(checks, InvoiceCheck.SGST, "SGST", expected.sgst(), found.sgst(), expected);
        taxCheck(checks, InvoiceCheck.IGST, "IGST", expected.igst(), found.igst(), expected);
        taxCheck(checks, InvoiceCheck.CESS, "Cess", expected.cess(), found.cess(), expected);
        if (expected.dealerGstin() != null && !expected.dealerGstin().isBlank()) {
            checks.add(gstinCheck(expected.dealerGstin().trim().toUpperCase(Locale.ROOT), found.gstins()));
        }

        InvoiceVerificationStatus status;
        if (found.grandTotal() == null) {
            status = InvoiceVerificationStatus.UNVERIFIED;
        } else if (checks.stream().allMatch(InvoiceCheck::isOk)) {
            status = InvoiceVerificationStatus.MATCHED;
        } else {
            status = InvoiceVerificationStatus.MISMATCH;
        }
        return new Result(status, checks);
    }

    private static InvoiceCheck amountCheck(String key, String label, BigDecimal expected, BigDecimal found, boolean required,
                                            String expectedMeaning) {
        InvoiceCheck.InvoiceCheckBuilder check = InvoiceCheck.builder().key(key).label(label).expected(plain(expected)).found(plain(found));
        if (found == null) {
            return check.ok(!required).message(required
                    ? "Not found on the invoice. Check it by hand."
                    : "Not shown separately on the invoice; checked through the total and the taxes.").build();
        }
        if (within(expected, found)) {
            return check.ok(true).build();
        }
        return check.ok(false).message("Invoice shows ₹" + plain(found) + ", but by the quotation it should be ₹" + plain(expected)
                + (expectedMeaning != null ? " (" + expectedMeaning + ")" : "") + ".").build();
    }

    private static void taxCheck(List<InvoiceCheck> checks, String key, String label, BigDecimal expected, BigDecimal found,
                                 Expected quotation) {
        boolean expectedNone = expected == null || expected.signum() == 0;
        if (expectedNone) {
            // Only worth showing when the invoice charges a tax the quotation doesn't
            if (found != null && found.signum() > 0) {
                String why = !quotation.taxQuoted() ? "the quotation has no GST"
                        : key.equals(InvoiceCheck.IGST) ? "the quotation is CGST + SGST (dealer in our state)"
                        : key.equals(InvoiceCheck.CESS) ? "the quotation has no cess"
                        : "the quotation is IGST (dealer in another state)";
                checks.add(InvoiceCheck.builder().key(key).label(label).expected(plain(BigDecimal.ZERO)).found(plain(found)).ok(false)
                        .message("Invoice charges " + label + " of ₹" + plain(found) + ", but " + why + ".").build());
            }
            return;
        }
        checks.add(amountCheck(key, label, expected, found, true, null));
    }

    private static InvoiceCheck gstinCheck(String dealerGstin, List<String> found) {
        InvoiceCheck.InvoiceCheckBuilder check = InvoiceCheck.builder().key(InvoiceCheck.GSTIN).label("Dealer GSTIN")
                .expected(dealerGstin).found(found.isEmpty() ? null : String.join(", ", found));
        if (found.contains(dealerGstin)) {
            return check.ok(true).build();
        }
        return check.ok(false).message(found.isEmpty()
                ? "No GSTIN found on the invoice; a tax invoice must show the supplier's GSTIN."
                : "The dealer's GSTIN " + dealerGstin + " is not on the invoice.").build();
    }

    private static boolean within(BigDecimal expected, BigDecimal found) {
        return expected.subtract(found).abs().compareTo(QuotationTax.TOLERANCE) <= 0;
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
