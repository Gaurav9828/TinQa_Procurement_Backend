package com.tinqa.procurement.barcode.enums;

public enum BarcodeRequestType {
    // A different code for a locked barcode
    CORRECTION,
    // Codes for units of an approved stock entry received before barcodes were recorded
    ASSIGNMENT
}
