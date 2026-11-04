package com.tinqa.procurement.meeting;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One employee invited to a meeting (the organiser included), with their reminder and the email waiting for them. */
@Entity
@Table(name = "meeting_attendee")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingAttendee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private Long meetingId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private boolean organizer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendeeStatus status;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt;

    @Column(name = "decline_deadline", nullable = false)
    private Instant declineDeadline;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "decline_reason", length = 1000)
    private String declineReason;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "feedback_submitted_at")
    private Instant feedbackSubmittedAt;

    @Column(name = "reminder_due_at")
    private Instant reminderDueAt;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_pending", length = 30)
    private MeetingEmailKind emailPending;

    @Column(name = "email_seq", nullable = false)
    private int emailSeq;

    @Column(name = "email_attempts", nullable = false)
    private int emailAttempts;

    @Column(name = "email_claimed_until")
    private Instant emailClaimedUntil;

    @Column(name = "email_last_attempt_at")
    private Instant emailLastAttemptAt;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @Column(name = "email_error", length = 500)
    private String emailError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Queues an email for this attendee (merged with one still waiting, see {@link MeetingEmailKind#merge}). */
    public void queueEmail(MeetingEmailKind kind) {
        MeetingEmailKind merged = MeetingEmailKind.merge(emailPending, kind);
        if (merged == null && emailPending == null || kind == MeetingEmailKind.REMINDER && merged == emailPending) {
            return;
        }
        // A new seq: a send of the older content that is still in flight won't clear this one
        emailPending = merged;
        emailSeq++;
        emailAttempts = 0;
        emailError = null;
    }

    /** Drops the email waiting, if any. */
    public void dropEmail() {
        if (emailPending != null) {
            emailPending = null;
            emailSeq++;
            emailAttempts = 0;
        }
    }

    public boolean live() {
        return status == AttendeeStatus.INVITED;
    }
}
