package com.tinqa.procurement.order.invoice;

/** What checking a dealer's invoice against the quotation found. Anything but MATCHED needs an approver to deliver. */
public enum InvoiceVerificationStatus {
    /** The total, the taxes and the dealer's GSTIN all agree with the quotation */
    MATCHED,
    /** At least one of them differs */
    MISMATCH,
    /** No text could be read (a scanned image or a protected PDF), or the invoice total could not be found */
    UNVERIFIED
}
