package com.tinqa.procurement.meeting;

/** The email waiting for an attendee (meeting_attendee.email_pending). */
public enum MeetingEmailKind {
    /** You're invited (with an .ics) */
    INVITE,
    /** The time or place changed (with an updated .ics) */
    UPDATED,
    /** It starts in 24 hours */
    REMINDER,
    /** Cancelled, with the reason (with a cancelling .ics) */
    CANCELLED,
    /** It was completed: please give feedback */
    FEEDBACK_REQUEST;

    /**
     * What to send when {@code next} happens while {@code pending} still waits: an unsent invite stays an invite (it
     * shows the latest details), a cancellation of an invite nobody received sends nothing, anything else is replaced.
     */
    public static MeetingEmailKind merge(MeetingEmailKind pending, MeetingEmailKind next) {
        if (pending == INVITE && (next == UPDATED || next == REMINDER)) {
            return INVITE;
        }
        if (pending == INVITE && next == CANCELLED) {
            return null;
        }
        if (next == REMINDER && pending != null) {
            // The waiting email carries the latest details already
            return pending;
        }
        return next;
    }
}
