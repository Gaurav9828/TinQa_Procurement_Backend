package com.tinqa.procurement.stock.entity;

import com.tinqa.procurement.common.enums.ApprovalStatus;
import com.tinqa.procurement.supplier.entity.Supplier;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.order.entity.Order;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_identity_number", nullable = false, unique = true, length = 100)
    private String stockIdentityNumber;

    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_number", referencedColumnName = "order_number", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "units_passed_test", nullable = false, precision = 14, scale = 3)
    private BigDecimal unitsPassedTest;

    @Column(name = "defected_units", nullable = false, precision = 14, scale = 3)
    private BigDecimal defectedUnits;

    @Column(name = "available_units", nullable = false, precision = 14, scale = 3)
    private BigDecimal availableUnits;

    @Column(name = "has_tested", nullable = false)
    @Builder.Default
    private Boolean hasTested = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "additional_info", columnDefinition = "jsonb")
    private Map<String, Object> additionalInfo;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 30)
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // The counts Admin L2 last approved: what finance books. Null until the entry is first approved. A change to an
    // approved entry leaves these as they were until L2 approves (or rejects) the change.
    @Column(name = "approved_units_passed", precision = 14, scale = 3)
    private BigDecimal approvedUnitsPassed;

    @Column(name = "approved_defected_units", precision = 14, scale = 3)
    private BigDecimal approvedDefectedUnits;

    @Column(name = "date_of_arrival", nullable = false)
    private LocalDate dateOfArrival;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** An approved entry whose counts were changed since L2 approved them: the change waits for L2. */
    public boolean hasChangeAwaitingApproval() {
        return approvalStatus == ApprovalStatus.APPROVED && approvedUnitsPassed != null
                && (unitsPassedTest.compareTo(approvedUnitsPassed) != 0 || defectedUnits.compareTo(approvedDefectedUnits) != 0);
    }

    /** Passed units added since L2's approval, held back from use until L2 approves them. */
    public BigDecimal heldUnits() {
        if (approvalStatus != ApprovalStatus.APPROVED || approvedUnitsPassed == null) {
            return BigDecimal.ZERO;
        }
        return unitsPassedTest.subtract(approvedUnitsPassed).max(BigDecimal.ZERO);
    }
}
