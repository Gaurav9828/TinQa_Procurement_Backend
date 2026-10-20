package com.tinqa.procurement.order.invoice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One figure compared between the quotation and the invoice. Amounts are plain rupee text ("1180.00"); for the GSTIN
 * check they are the GSTINs. {@code found} is null when the invoice doesn't show it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceCheck {

    public static final String TEXT = "text";
    public static final String GRAND_TOTAL = "grandTotal";
    public static final String TAXABLE_VALUE = "taxableValue";
    public static final String CGST = "cgst";
    public static final String SGST = "sgst";
    public static final String IGST = "igst";
    public static final String CESS = "cess";
    public static final String GSTIN = "gstin";

    private String key;
    private String label;
    private String expected;
    private String found;
    private boolean ok;
    private String message;
}
