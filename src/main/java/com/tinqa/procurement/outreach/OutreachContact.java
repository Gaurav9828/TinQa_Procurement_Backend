package com.tinqa.procurement.outreach;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One call, email, WhatsApp message or visit with a business, as logged by the person who made it. */
@Entity
@Table(name = "outreach_contact")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutreachContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "contacted_at", nullable = false)
    private Instant contactedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContactChannel channel;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(nullable = false, length = 4000)
    private String summary;

    @Column(length = 2000)
    private String response;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Rating rating;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Interest interest;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_action", nullable = false, length = 30)
    private NextAction nextAction;

    @Column(name = "follow_up_at")
    private Instant followUpAt;

    // When the follow-up reminder went out, or was no longer needed
    @Column(name = "follow_up_reminded_at")
    private Instant followUpRemindedAt;

    @Column(name = "logged_by", nullable = false, updatable = false)
    private Long loggedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
