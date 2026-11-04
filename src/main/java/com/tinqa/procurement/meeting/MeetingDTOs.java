package com.tinqa.procurement.meeting;

import com.tinqa.procurement.common.validation.GoogleMapsUrl;
import com.tinqa.procurement.common.validation.UrlInput;
import com.tinqa.procurement.common.validation.ValidationPatterns;
import com.tinqa.procurement.outreach.Rating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** Requests and views of /v1/meetings. */
public final class MeetingDTOs {

    /** The most people invited to one meeting at once. */
    public static final int MAX_INVITEES = 50;

    private static final String OPTIONAL_TEXT = "^\\s*$|" + ValidationPatterns.SINGLE_LINE_TEXT;
    private static final String TEXT_MESSAGE = " " + ValidationPatterns.SINGLE_LINE_TEXT_MESSAGE;

    private MeetingDTOs() {
    }

    // ------------------------------------------------------------------ requests

    /**
     * A meeting's details. ONLINE needs {@code joinUrl} (https); IN_PERSON needs {@code placeName} and {@code address}.
     * {@code attendeeEmployeeIds} is read when scheduling only (the organiser is added automatically).
     */
    public record MeetingRequest(
            @NotNull(message = "Mode is required (ONLINE or IN_PERSON)")
            MeetingMode mode,

            @NotBlank(message = "Title is required")
            @Size(max = 150, message = "Title cannot exceed 150 characters")
            @Pattern(regexp = ValidationPatterns.SINGLE_LINE_TEXT, message = "Title" + TEXT_MESSAGE)
            String title,

            @Size(max = 200, message = "Subject cannot exceed 200 characters")
            String subject,

            @Size(max = 4000, message = "Description cannot exceed 4000 characters")
            String description,

            @NotNull(message = "Meeting type is required")
            MeetingType meetingType,

            @Size(max = 150, message = "Client name cannot exceed 150 characters")
            @Pattern(regexp = OPTIONAL_TEXT, message = "Client name" + TEXT_MESSAGE)
            String clientName,

            @Size(max = 2000, message = "Client description cannot exceed 2000 characters")
            String clientDescription,

            @Size(max = 150, message = "Client contact cannot exceed 150 characters")
            String clientContact,

            Long outreachBusinessId,

            @NotNull(message = "Start time is required")
            OffsetDateTime startsAt,

            @NotNull(message = "End time is required")
            OffsetDateTime endsAt,

            @UrlInput
            @Size(max = 1000, message = "Meeting link cannot exceed 1000 characters")
            String joinUrl,

            @Size(max = 150, message = "Place cannot exceed 150 characters")
            String placeName,

            @Size(max = 500, message = "Address cannot exceed 500 characters")
            String address,

            @UrlInput
            @GoogleMapsUrl
            String googleMapsUrl,

            @Size(max = MAX_INVITEES, message = "At most " + MAX_INVITEES + " people can be invited at once")
            List<Long> attendeeEmployeeIds) {
    }

    /** New invitees; with {@code replacesAttendeeId}, exactly one employee who takes a declined attendee's place. */
    public record AttendeesRequest(
            @NotEmpty(message = "Pick at least one employee to invite")
            @Size(max = MAX_INVITEES, message = "At most " + MAX_INVITEES + " people can be invited at once")
            List<Long> employeeIds,

            Long replacesAttendeeId) {
    }

    public record ReasonRequest(
            @NotBlank(message = "A reason is required")
            @Size(max = 1000, message = "Reason cannot exceed 1000 characters")
            String reason) {
    }

    public record FeedbackRequest(
            @NotBlank(message = "Agenda is required")
            @Size(max = 4000, message = "Agenda cannot exceed 4000 characters")
            String agenda,

            @NotBlank(message = "Feedback is required")
            @Size(max = 4000, message = "Feedback cannot exceed 4000 characters")
            String feedback,

            @NotBlank(message = "Decisions are required")
            @Size(max = 4000, message = "Decisions cannot exceed 4000 characters")
            String decisions,

            Boolean dealConfirmed,

            @Size(max = 2000, message = "Deal notes cannot exceed 2000 characters")
            String dealNotes,

            MeetingType meetingType,

            Rating rating) {
    }

    // ------------------------------------------------------------------ views

    public record Attendee(Long id, Long employeeId, Long userId, String name, String designationLabel, AttendeeStatus status,
                           boolean organizer, OffsetDateTime invitedAt, OffsetDateTime declineDeadline, OffsetDateTime declinedAt,
                           String declineReason, String replacedByName, OffsetDateTime feedbackSubmittedAt) {
    }

    /**
     * A meeting. Lists leave {@code attendees} out (null) and give the counts; {@code myAttendance} is the caller's own
     * attendee row, {@code feedbackPending} whether they still owe feedback on it, {@code canManage} whether they
     * organise it (and may schedule meetings).
     */
    public record Meeting(Long id, String code, MeetingMode mode, String title, String subject, String description,
                          MeetingType meetingType, String clientName, String clientDescription, String clientContact,
                          Long outreachBusinessId, OffsetDateTime startsAt, OffsetDateTime endsAt, String joinUrl,
                          String placeName, String address, String googleMapsUrl, MeetingStatus status, String cancelReason,
                          Long organizerId, String organizerName, List<Attendee> attendees, long feedbackCount,
                          long attendeeCount, OffsetDateTime completedAt, OffsetDateTime createdAt, Attendee myAttendance,
                          boolean canManage, boolean feedbackPending) {
    }

    public record Feedback(Long id, Long attendeeId, String attendeeName, String agenda, String feedback, String decisions,
                           boolean dealConfirmed, String dealNotes, MeetingType meetingType, Rating rating,
                           OffsetDateTime submittedAt) {
    }

    public record StaffOption(Long employeeId, Long userId, String name, String designationLabel) {
    }

    /** An employee already in another scheduled meeting during the asked span; {@code self} is the caller. */
    public record Busy(Long employeeId, String name, boolean self, Long meetingId, String code, OffsetDateTime startsAt,
                       OffsetDateTime endsAt) {
    }

    public record Count(long count) {
    }
}
