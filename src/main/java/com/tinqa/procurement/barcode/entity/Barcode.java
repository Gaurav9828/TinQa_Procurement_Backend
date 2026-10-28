package com.tinqa.procurement.barcode.entity;

import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.stock.entity.Stock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One accountable unit and its barcode: a piece of item stock, or a unit of an Ecommerce product. See V13.
 */
@Entity
@Table(name = "barcodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Barcode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 20, updatable = false)
    private BarcodeUnitType unitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BarcodeStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", updatable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", updatable = false)
    private Stock stock;

    @Column(name = "ecommerce_product_id", updatable = false)
    private Long ecommerceProductId;

    @Column(name = "product_title", length = 200)
    private String productTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fitted_into_id")
    private Barcode fittedInto;

    @Column(name = "status_note", length = 500)
    private String statusNote;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by")
    private Long lockedBy;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_by_name", length = 255, updatable = false)
    private String createdByName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    /** Approved: from now on only a Super Admin can change the code. */
    public boolean isLocked() {
        return lockedAt != null;
    }
}
