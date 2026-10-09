package com.tinqa.procurement.item.entity;

import com.tinqa.procurement.item.enums.WarrantyDurationUnit;
import com.tinqa.procurement.item.enums.WarrantyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "item_warranties")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemWarranty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Enumerated(EnumType.STRING)
    @Column(name = "warranty_type", nullable = false, length = 30)
    private WarrantyType warrantyType;

    @Column(nullable = false)
    private String title;

    @Column(name = "duration_value", nullable = false)
    private Integer durationValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_unit", nullable = false, length = 10)
    private WarrantyDurationUnit durationUnit;

    @Column
    private String provider;

    @Column(columnDefinition = "TEXT")
    private String coverage;

    @Column(columnDefinition = "TEXT")
    private String exclusions;

    @Column(name = "terms_and_conditions", columnDefinition = "TEXT")
    private String termsAndConditions;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}
