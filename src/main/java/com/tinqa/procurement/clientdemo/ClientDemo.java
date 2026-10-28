package com.tinqa.procurement.clientdemo;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A visit to a client for a demo appointment or a promotion: who the client is, when and for how many days, which staff
 * attend, the proposed budget, and every bill of the visit. See {@link DemoStatus} for its life.
 */
@Entity
@Table(name = "client_demo")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDemo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "demo_type", nullable = false, length = 20)
    private DemoType demoType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DemoStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_category", nullable = false, length = 30)
    private ClientCategory clientCategory;

    @Column(name = "client_category_other", length = 100)
    private String clientCategoryOther;

    @Column(name = "client_name", nullable = false, length = 150)
    private String clientName;

    @Column(name = "contact_person", length = 100)
    private String contactPerson;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "address_line", nullable = false, length = 300)
    private String addressLine;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 10)
    private String pincode;

    @Column(name = "map_link", length = 500)
    private String mapLink;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "number_of_days", nullable = false)
    private Integer numberOfDays;

    @Column(name = "proposed_budget", nullable = false, precision = 12, scale = 2)
    private BigDecimal proposedBudget;

    @Column(length = 1000)
    private String purpose;

    @Column(length = 1000)
    private String outcome;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "reopen_reason", length = 500)
    private String reopenReason;

    // The sum of this demo's postings to the company account
    @Column(name = "booked_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal bookedTotal = BigDecimal.ZERO.setScale(2);

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    // Employee ids of the attending staff
    @ElementCollection
    @CollectionTable(name = "client_demo_staff", joinColumns = @JoinColumn(name = "demo_id"))
    @Column(name = "employee_id")
    @Builder.Default
    private Set<Long> staffEmployeeIds = new LinkedHashSet<>();

    @OneToMany(mappedBy = "demo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayNumber ASC, id ASC")
    @Builder.Default
    private List<DemoExpense> expenses = new ArrayList<>();

    /** The number staff and approvers refer to the demo by. */
    public String reference() {
        return reference(id);
    }

    public static String reference(Long id) {
        return id == null ? null : String.format("DEMO-%05d", id);
    }

    /** The last day of the visit. */
    public LocalDate endDate() {
        return startDate.plusDays(numberOfDays - 1L);
    }

    /** What the visit cost so far: the sum of its bills. */
    public BigDecimal total() {
        return expenses.stream().map(DemoExpense::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Whether it was completed (and booked) before, i.e. it is being corrected. */
    public boolean completedBefore() {
        return completedAt != null;
    }
}
