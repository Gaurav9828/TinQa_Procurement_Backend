package com.tinqa.procurement.clientdemo;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** One bill of a demo: a stay, a meal, a leg of travel or another cost. {@link ExpenseKind} decides which fields count. */
@Entity
@Table(name = "client_demo_expense")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "demo_id", nullable = false, updatable = false)
    private ClientDemo demo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExpenseKind kind;

    // Day of the visit, 1 = the start date
    @Column(name = "day_number")
    private Integer dayNumber;

    // The hotel, restaurant, transport operator or payee
    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "vendor_address", length = 300)
    private String vendorAddress;

    @Column(name = "vendor_map_link", length = 500)
    private String vendorMapLink;

    @Column(name = "vendor_phone", length = 20)
    private String vendorPhone;

    @Column(name = "vendor_gstin", length = 15)
    private String vendorGstin;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", length = 10)
    private MealType mealType;

    @Column(name = "from_place", length = 150)
    private String fromPlace;

    @Column(name = "to_place", length = 150)
    private String toPlace;

    @Enumerated(EnumType.STRING)
    @Column(name = "travel_mode", length = 20)
    private TravelMode travelMode;

    // When a stay (check-in to check-out) or a journey (departure to arrival) starts and ends; it may end the next day
    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "end_time")
    private LocalTime endTime;

    // Invoice number, or ticket / PNR number
    @Column(name = "reference_number", length = 60)
    private String referenceNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "gst_amount", precision = 12, scale = 2)
    private BigDecimal gstAmount;

    @Column(length = 300)
    private String description;

    @Column(length = 500)
    private String reason;

    // The invoice or ticket file
    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "added_by", nullable = false, updatable = false)
    private Long addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
