package com.tinqa.procurement.mail;

import java.time.Instant;

/**
 * A meeting email to one attendee. The sender words it, links it to the staff portal's meeting page (and its feedback
 * form for FEEDBACK_REQUEST) and, for INVITE, UPDATED and CANCELLED, attaches a calendar (.ics) file built from these
 * fields.
 *
 * @param kind       INVITE, UPDATED, REMINDER, CANCELLED or FEEDBACK_REQUEST
 * @param uid        the calendar event's stable id, e.g. "MTG-2026-0001@tinqa"
 * @param sequence   the calendar SEQUENCE: higher for every change of time or place
 * @param mode       ONLINE or IN_PERSON
 * @param declineBy  when the recipient's window to decline closes (INVITE; null for the organiser)
 */
public record MeetingEmail(String kind, String to, String name, long meetingId, String code, String uid, int sequence,
                           String title, String subject, String mode, String meetingType, Instant startsAt, Instant endsAt,
                           String joinUrl, String placeName, String address, String googleMapsUrl, String organizerName,
                           String organizerEmail, String cancelReason, Instant declineBy) {
}
