package com.tinqa.procurement.meeting;

import java.time.Instant;

/** One employee's place in another scheduled meeting that overlaps a span of time. */
public record MeetingClash(Long employeeId, String name, Long meetingId, String code, Instant startsAt, Instant endsAt) {
}
