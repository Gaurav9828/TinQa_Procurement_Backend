package com.tinqa.procurement.order.invoice;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A dealer's invoice uploaded for an order and the result of checking it; the latest row of an order counts. */
@Entity
@Table(name = "order_invoice")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    // As read from the invoice
    @Column(name = "invoice_number", length = 40, updatable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private InvoiceVerificationStatus status;

    // What the invoice should total by the quotation when it was uploaded
    @Column(name = "expected_total", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal expectedTotal;

    @Column(name = "found_total", precision = 14, scale = 2, updatable = false)
    private BigDecimal foundTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private List<InvoiceCheck> checks;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    // An approver accepted a mismatched or unreadable invoice to deliver the order
    @Column(name = "override_by")
    private Long overrideBy;

    @Column(name = "override_at")
    private Instant overrideAt;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;
}
