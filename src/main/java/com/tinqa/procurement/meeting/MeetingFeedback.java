package com.tinqa.procurement.meeting;

import com.tinqa.procurement.outreach.Rating;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One attendee's feedback on a completed meeting; final once submitted. */
@Entity
@Table(name = "meeting_feedback")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private Long meetingId;

    @Column(name = "attendee_id", nullable = false, updatable = false)
    private Long attendeeId;

    @Column(nullable = false, length = 4000)
    private String agenda;

    @Column(nullable = false, length = 4000)
    private String feedback;

    @Column(nullable = false, length = 4000)
    private String decisions;

    @Column(name = "deal_confirmed", nullable = false)
    private boolean dealConfirmed;

    @Column(name = "deal_notes", length = 2000)
    private String dealNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "meeting_type", length = 30)
    private MeetingType meetingType;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Rating rating;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;
}
