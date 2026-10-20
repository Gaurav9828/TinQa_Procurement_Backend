package com.tinqa.procurement.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Money moves of dealer purchases, read by the Ecommerce service's company account. */
public final class PurchaseEventDTOs {

    private PurchaseEventDTOs() {
    }

    /**
     * One change to what is booked as bought from a dealer: stock received / corrected / rejected (only units that
     * passed testing are bought) or an order refunded. {@code amount} is signed: positive = paid to the dealer
     * (money out), negative = money back. {@code postingKey} never repeats, so the reader posts each event once.
     *
     * <p>{@code amount} is before GST. The GST on it (input tax: what the dealer charges us) is signed the same way, in
     * {@code cgst}/{@code sgst} (dealer in our state) or {@code igst} (another state), plus {@code cess}; all zero when
     * the quotation had no GST breakup. An event may move only GST (amount 0) when the quotation's GST terms changed.
     */
    public record PurchaseEvent(
            Long eventId,
            String postingKey,
            String reason,
            String orderNumber,
            String stockIdentityNumber,
            BigDecimal amount,
            String dealerName,
            String itemName,
            BigDecimal quantity,
            String unitType,
            LocalDate occurredOn,
            Instant changedAt,
            BigDecimal cgst,
            BigDecimal sgst,
            BigDecimal igst,
            BigDecimal cess,
            BigDecimal gstRate,
            String supplyType,
            String hsnSac,
            String dealerGstin,
            String dealerState,
            String dealerInvoiceNumber,
            Instant dealerInvoiceUploadedAt) {
    }

    public record PurchaseEventPage(List<PurchaseEvent> events, boolean hasMore) {
    }
}
