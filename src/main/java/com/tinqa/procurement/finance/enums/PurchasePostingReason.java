package com.tinqa.procurement.finance.enums;

/** Why what is booked for an order changed. */
public enum PurchasePostingReason {
    /** Admin L2 approved a stock entry: its passed units are bought. */
    STOCK_APPROVED,
    /** Admin L2 approved a change to an approved entry (units added or counts corrected): the difference is booked. */
    STOCK_CHANGE_APPROVED,
    /** Admin L2 rejected the stock entry: nothing of it is bought. */
    STOCK_REJECTED,
    /** The quotation's GST terms changed (CGST + SGST to IGST or back): the GST booked under the old terms is taken back. */
    GST_TERMS_CHANGED,
    /** Rows written before finance waited for Admin L2 approval; never written now. */
    STOCK_RECEIVED,
    STOCK_UPDATED,
    STOCK_QUANTITY_ADDED,
    /** The dealer refunded the order: what was booked for it comes back. */
    ORDER_REFUNDED,
    /** Migration rows mirroring what the old rule (whole order on delivery / refund) already booked. */
    LEGACY_DELIVERED,
    LEGACY_REFUND,
    /** Migration row truing an already-received order up to its passed units. */
    STOCK_TRUE_UP,
    /** Migration row truing an order up to its L2-approved units and their GST (V10). */
    APPROVAL_TRUE_UP
}
