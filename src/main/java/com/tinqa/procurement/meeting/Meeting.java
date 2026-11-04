package com.tinqa.procurement.meeting;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A meeting with its time, place or link, organiser and status. Attendees are {@link MeetingAttendee} rows. */
@Entity
@Table(name = "meeting")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingMode mode;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 200)
    private String subject;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "meeting_type", nullable = false, length = 30)
    private MeetingType meetingType;

    @Column(name = "client_name", length = 150)
    private String clientName;

    @Column(name = "client_description", length = 2000)
    private String clientDescription;

    @Column(name = "client_contact", length = 150)
    private String clientContact;

    @Column(name = "outreach_business_id")
    private Long outreachBusinessId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "join_url", length = 1000)
    private String joinUrl;

    @Column(name = "place_name", length = 150)
    private String placeName;

    @Column(length = 500)
    private String address;

    @Column(name = "google_maps_url", length = 500)
    private String googleMapsUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeetingStatus status;

    @Column(name = "cancel_reason", length = 1000)
    private String cancelReason;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private Long organizerId;

    @Column(name = "schedule_sequence", nullable = false)
    private int scheduleSequence;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
