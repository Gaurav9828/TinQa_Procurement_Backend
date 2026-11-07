package com.tinqa.procurement.finance.entity;

import com.tinqa.procurement.finance.enums.PurchasePostingReason;
import com.tinqa.procurement.order.entity.Order;
import com.tinqa.procurement.stock.entity.Stock;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Append-only: one change to what is booked as bought from a supplier for an order, written in the same transaction as
 * the stock change behind it. {@code amount} is signed: positive = more paid to the supplier, negative = money back.
 */
@Entity
@Table(name = "purchase_postings")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchasePosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", updatable = false)
    private Stock stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30, updatable = false)
    private PurchasePostingReason reason;

    @Column(name = "units_passed", nullable = false, precision = 14, scale = 3, updatable = false)
    private BigDecimal unitsPassed;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    // GST on the value this row books, signed like amount (zero when the quotation has no GST breakup)
    @Column(name = "cgst", nullable = false, precision = 14, scale = 2, updatable = false)
    @Builder.Default
    private BigDecimal cgst = BigDecimal.ZERO;

    @Column(name = "sgst", nullable = false, precision = 14, scale = 2, updatable = false)
    @Builder.Default
    private BigDecimal sgst = BigDecimal.ZERO;

    @Column(name = "igst", nullable = false, precision = 14, scale = 2, updatable = false)
    @Builder.Default
    private BigDecimal igst = BigDecimal.ZERO;

    @Column(name = "cess", nullable = false, precision = 14, scale = 2, updatable = false)
    @Builder.Default
    private BigDecimal cess = BigDecimal.ZERO;

    @Column(name = "gst_rate", precision = 5, scale = 2, updatable = false)
    private BigDecimal gstRate;

    // INTRA_STATE (CGST + SGST) or INTER_STATE (IGST); null without a GST breakup
    @Column(name = "supply_type", length = 12, updatable = false)
    private String supplyType;

    @Column(name = "hsn_sac", length = 8, updatable = false)
    private String hsnSac;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Column(name = "legacy_history_id", updatable = false)
    private Long legacyHistoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The company account's posting key: legacy rows keep the key the old rule used, so they are never posted twice. */
    public String postingKey() {
        return legacyHistoryId != null ? "PROCUREMENT:" + legacyHistoryId : "PROCUREMENT_PURCHASE:" + id;
    }
}
