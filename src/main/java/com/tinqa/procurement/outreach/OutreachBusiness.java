package com.tinqa.procurement.outreach;

import com.tinqa.procurement.clientdemo.ClientCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A property or company we pitch to. The summary columns (contact count, latest contact, follow-up …) are rewritten
 * from its contacts by {@link OutreachBusinessRepository#refreshSummary} and are read-only here.
 */
@Entity
@Table(name = "outreach_business")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutreachBusiness {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 30)
    private ClientCategory businessType;

    @Column(length = 100)
    private String city;

    @Column(length = 500)
    private String address;

    @Column(name = "google_maps_url", length = 500)
    private String googleMapsUrl;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(length = 2000)
    private String notes;

    @Column(name = "contact_count", nullable = false, insertable = false, updatable = false)
    private int contactCount;

    @Column(name = "demo_request_count", nullable = false, insertable = false, updatable = false)
    private int demoRequestCount;

    @Column(name = "latest_contact_id", insertable = false, updatable = false)
    private Long latestContactId;

    @Column(name = "last_contact_at", insertable = false, updatable = false)
    private Instant lastContactAt;

    @Column(name = "last_contact_by", insertable = false, updatable = false)
    private Long lastContactBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_interest", insertable = false, updatable = false, length = 20)
    private Interest latestInterest;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_rating", insertable = false, updatable = false, length = 20)
    private Rating latestRating;

    @Column(name = "next_follow_up_at", insertable = false, updatable = false)
    private Instant nextFollowUpAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
