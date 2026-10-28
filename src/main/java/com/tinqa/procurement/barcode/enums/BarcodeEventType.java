package com.tinqa.procurement.barcode.enums;

public enum BarcodeEventType {
    REGISTERED,
    // What the barcode belongs to was approved: the code is locked
    APPROVED,
    // The registration was rejected or withdrawn (stock entry rejected or corrected, product rejected or cancelled)
    VOIDED,
    FITTED,
    // Taken out of a product unit and back into item stock
    RETURNED,
    REMOVED,
    WITHDRAWAL_REQUESTED,
    WITHDRAWAL_APPROVED,
    WITHDRAWAL_REJECTED,
    CODE_CORRECTED,
    // An item stock movement was reversed, undoing what it did to the barcode
    REVERSED
}
