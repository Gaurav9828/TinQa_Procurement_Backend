package com.tinqa.procurement.dealer.entity;

import com.tinqa.procurement.common.entity.Category;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "dealers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dealer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "trade_name")
    private String tradeName;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "alternate_phone_number", length = 20)
    private String alternatePhoneNumber;

    // Address
    @Column(nullable = false, columnDefinition = "TEXT")
    private String street;

    private String landmark;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String state;

    @Column(nullable = false, length = 100)
    private String country;

    @Column(nullable = false, length = 20)
    private String pincode;

    @Column(name = "google_maps_url", columnDefinition = "TEXT")
    private String googleMapsUrl;

    // Legal / Govt
    @Column(length = 15, unique = true)
    private String gstin;

    @Column(name = "is_gst_verified", nullable = false)
    @Builder.Default
    private Boolean isGstVerified = false;

    @Column(name = "pan_number", length = 10)
    private String panNumber;

    @Column(name = "business_since")
    private Integer businessSince;

    @Column(name = "employee_count")
    private Integer employeeCount;

    // Capabilities
    @Column(name = "offers_shipping", nullable = false)
    @Builder.Default
    private Boolean offersShipping = false;

    @Column(name = "does_bulk_dealing", nullable = false)
    @Builder.Default
    private Boolean doesBulkDealing = true;

    @Column(name = "does_wholesale_dealing", nullable = false)
    @Builder.Default
    private Boolean doesWholesaleDealing = true;

    // Category Relation
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "dealer_category_mappings",
            joinColumns = @JoinColumn(name = "dealer_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    @Builder.Default
    private Set<Category> categories = new HashSet<>();

    // Status & Audit
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