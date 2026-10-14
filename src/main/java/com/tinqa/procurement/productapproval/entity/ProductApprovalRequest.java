package com.tinqa.procurement.productapproval.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.productapproval.dto.ProductApprovalDTOs;
import com.tinqa.procurement.productapproval.enums.ProductApprovalRequestType;
import com.tinqa.procurement.productapproval.enums.ProductApprovalStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "product_approval_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Product id in the Ecommerce service (different database)
    @Column(name = "ecommerce_product_id", nullable = false, updatable = false)
    private Long ecommerceProductId;

    @Column(name = "product_title", nullable = false, length = 150)
    private String productTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20, updatable = false)
    private ProductApprovalRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductApprovalStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private ProductApprovalDTOs.Snapshot snapshot;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_changes", columnDefinition = "jsonb", updatable = false)
    private JsonNode proposedChanges;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_snapshot", columnDefinition = "jsonb", updatable = false)
    private ProductApprovalDTOs.Snapshot previousSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", columnDefinition = "jsonb", updatable = false)
    private List<ProductApprovalDTOs.ChangeSummaryEntry> changeSummary;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "applied_by")
    private Long appliedBy;

    @Version
    @Column(nullable = false)
    private Long version;
}
