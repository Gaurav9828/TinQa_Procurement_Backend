package com.tinqa.procurement.order.invoice;

import com.tinqa.procurement.support.InvoicePdfs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading real invoice layouts and checking them against a quotation of ₹1,050 + 18% GST (in state). */
class InvoiceVerificationTest {

    private static final String SUPPLIER_GSTIN = "29ABCDE1234F1Z5";
    private static final InvoiceVerifier.Expected INTRA_18 = new InvoiceVerifier.Expected(new BigDecimal("1239.00"), true,
            new BigDecimal("1050.00"), new BigDecimal("94.50"), new BigDecimal("94.50"), BigDecimal.ZERO, BigDecimal.ZERO, SUPPLIER_GSTIN);

    private final InvoicePdfReader reader = new InvoicePdfReader();

    private InvoiceFigures read(byte[] pdf) {
        return InvoiceFigureExtractor.extract(reader.readText(pdf));
    }

    @Test
    void summaryLinesWithIndianGroupingAndRates() {
        InvoiceFigures figures = read(InvoicePdfs.withLines(
                "TAX INVOICE",
                "Invoice No: INV/2026/0042   Date: 18/10/2026",
                "Supplier GSTIN: " + SUPPLIER_GSTIN + "   Buyer GSTIN: 29AAACT1234K1Z9",
                "Copper wire 7408   10 PCS   100.00   1,000.00",
                "Freight   50.00",
                "Taxable Value   Rs. 1,050.00",
                "CGST @ 9%   94.50",
                "SGST @ 9%   94.50",
                "Grand Total   Rs. 1,239.00",
                "Amount in words: Rupees One Thousand Two Hundred Thirty Nine only"));

        assertThat(figures.readable()).isTrue();
        assertThat(figures.invoiceNumber()).isEqualTo("INV/2026/0042");
        assertThat(figures.grandTotal()).isEqualByComparingTo("1239.00");
        assertThat(figures.taxableValue()).isEqualByComparingTo("1050.00");
        assertThat(figures.cgst()).isEqualByComparingTo("94.50");
        assertThat(figures.sgst()).isEqualByComparingTo("94.50");
        assertThat(figures.igst()).isNull();
        assertThat(figures.gstins()).contains(SUPPLIER_GSTIN);

        InvoiceVerifier.Result result = InvoiceVerifier.verify(INTRA_18, figures);
        assertThat(result.status()).isEqualTo(InvoiceVerificationStatus.MATCHED);
        assertThat(result.checks()).allMatch(InvoiceCheck::isOk);
    }

    @Test
    void tableHeaderWithAmountsOnTheNextLine() {
        InvoiceFigures figures = read(InvoicePdfs.withLines(
                "Bill No. B-778",
                "GSTIN " + SUPPLIER_GSTIN,
                "Taxable Amount   CGST Rate   CGST Amount   SGST Rate   SGST Amount   Total",
                "1050.00   9%   94.50   9%   94.50   1239.00"));

        assertThat(figures.taxableValue()).isEqualByComparingTo("1050.00");
        assertThat(figures.cgst()).isEqualByComparingTo("94.50");
        assertThat(figures.sgst()).isEqualByComparingTo("94.50");
        assertThat(figures.grandTotal()).isEqualByComparingTo("1239.00");
        assertThat(InvoiceVerifier.verify(INTRA_18, figures).status()).isEqualTo(InvoiceVerificationStatus.MATCHED);
    }

    @Test
    void roundedTotalStillMatches() {
        InvoiceFigures figures = read(InvoicePdfs.withLines("GSTIN: " + SUPPLIER_GSTIN, "Taxable value 1050.00",
                "CGST 94.50", "SGST 94.50", "Round off -0.00", "Total Amount After Tax 1,239"));
        assertThat(InvoiceVerifier.verify(INTRA_18, figures).status()).isEqualTo(InvoiceVerificationStatus.MATCHED);
    }

    @Test
    void wrongTaxTypeAndTotalAreMismatches() {
        InvoiceFigures figures = read(InvoicePdfs.withLines("GSTIN: " + SUPPLIER_GSTIN, "Taxable value 1050.00",
                "IGST @ 18%   189.00", "Grand Total 1,300.00"));
        InvoiceVerifier.Result result = InvoiceVerifier.verify(INTRA_18, figures);

        assertThat(result.status()).isEqualTo(InvoiceVerificationStatus.MISMATCH);
        assertThat(result.checks()).filteredOn(check -> !check.isOk()).extracting(InvoiceCheck::getKey)
                .containsExactlyInAnyOrder("grandTotal", "cgst", "sgst", "igst");
        assertThat(result.checks()).filteredOn(check -> check.getKey().equals("igst")).first()
                .extracting(InvoiceCheck::getMessage).asString().contains("CGST + SGST");
    }

    @Test
    void anotherSuppliersGstinIsAMismatch() {
        InvoiceFigures figures = read(InvoicePdfs.withLines("GSTIN: 27AAPFU0939F1ZV", "CGST 94.50", "SGST 94.50", "Grand Total 1239.00"));
        InvoiceVerifier.Result result = InvoiceVerifier.verify(INTRA_18, figures);
        assertThat(result.status()).isEqualTo(InvoiceVerificationStatus.MISMATCH);
        assertThat(result.checks()).filteredOn(check -> !check.isOk()).extracting(InvoiceCheck::getKey).containsExactly("gstin");
    }

    @Test
    void scannedInvoiceCannotBeChecked() {
        InvoiceFigures figures = read(InvoicePdfs.withoutText());
        assertThat(figures.readable()).isFalse();
        assertThat(InvoiceVerifier.verify(INTRA_18, figures).status()).isEqualTo(InvoiceVerificationStatus.UNVERIFIED);
        assertThat(reader.readText("not a pdf".getBytes())).isEmpty();
    }

    @Test
    void invoiceWithoutATotalCannotBeChecked() {
        InvoiceFigures figures = read(InvoicePdfs.withLines("Delivery challan for copper wire", "Quantity 10 PCS, packed in 2 boxes"));
        assertThat(InvoiceVerifier.verify(INTRA_18, figures).status()).isEqualTo(InvoiceVerificationStatus.UNVERIFIED);
    }

    @Test
    void withoutQuotedGstAnyGstOnTheInvoiceIsAMismatch() {
        InvoiceVerifier.Expected noTax = new InvoiceVerifier.Expected(new BigDecimal("1050.00"), false, new BigDecimal("1050.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertThat(InvoiceVerifier.verify(noTax, read(InvoicePdfs.withLines("Invoice No 77", "Total 1050.00"))).status())
                .isEqualTo(InvoiceVerificationStatus.MATCHED);
        InvoiceVerifier.Result taxed = InvoiceVerifier.verify(noTax, read(InvoicePdfs.withLines("CGST 94.50", "SGST 94.50", "Grand Total 1239.00")));
        assertThat(taxed.status()).isEqualTo(InvoiceVerificationStatus.MISMATCH);
        assertThat(taxed.checks()).filteredOn(check -> check.getKey().equals("cgst")).first()
                .extracting(InvoiceCheck::getMessage).asString().contains("no GST");
    }
}
