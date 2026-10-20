package com.tinqa.procurement.order.invoice;

import java.math.BigDecimal;
import java.util.List;

/**
 * The figures read from an invoice's text. A figure the invoice doesn't show (or that couldn't be recognised) is null.
 *
 * @param readable whether the PDF had any text at all
 */
public record InvoiceFigures(
        boolean readable,
        String invoiceNumber,
        BigDecimal grandTotal,
        BigDecimal taxableValue,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst,
        BigDecimal cess,
        List<String> gstins
) {
}
