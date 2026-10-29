package com.tinqa.procurement.barcode.enums;

import java.util.EnumSet;
import java.util.Set;

public enum BarcodeStatus {
    // Registered with a stock entry or product that is waiting for approval; not usable yet
    PENDING,
    // Approved and in stock
    IN_STOCK,
    // An item unit built into a product unit
    FITTED,
    // Taken out of stock (damaged, lost, scrapped...), kept for the record
    REMOVED,
    // An approved item unit taken off by a count correction that waits for Admin L2
    WITHDRAWAL_PENDING,
    // A product unit staff took to a client demo: out of stock until it is handed back (or written off)
    ON_DEMO,
    // Registration rejected, reversed or corrected away; the code is free again
    VOID;

    /** Units counted in their stock entry's passed units. */
    public static final Set<BarcodeStatus> COUNTED = EnumSet.of(PENDING, IN_STOCK, FITTED, REMOVED);

    /** Product units still held as stock. */
    public static final Set<BarcodeStatus> HELD = EnumSet.of(PENDING, IN_STOCK);
}
