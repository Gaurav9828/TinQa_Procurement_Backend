package com.tinqa.procurement.barcode.entity;

import com.tinqa.procurement.barcode.enums.BarcodeRequestStatus;
import com.tinqa.procurement.barcode.enums.BarcodeRequestType;
import com.tinqa.procurement.stock.entity.Stock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/** A change to locked barcodes that a Super Admin decides. */
@Entity
@Table(name = "barcode_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BarcodeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20, updatable = false)
    private BarcodeRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BarcodeRequestStatus status;

    // CORRECTION: the barcode, its code when asked and the code wanted
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "barcode_id", updatable = false)
    private Barcode barcode;

    @Column(name = "current_code", length = 32, updatable = false)
    private String currentCode;

    @Column(name = "new_code", length = 32, updatable = false)
    private String newCode;

    // ASSIGNMENT: the stock entry and the codes for its units that have none
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", updatable = false)
    private Stock stock;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private List<String> codes;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Version
    @Column(nullable = false)
    private Long version;
}
