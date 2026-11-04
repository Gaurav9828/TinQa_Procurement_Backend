package com.tinqa.procurement.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MeetingAttendeeRepository extends JpaRepository<MeetingAttendee, Long> {

    List<MeetingAttendee> findByMeetingIdOrderByIdAsc(Long meetingId);

    // The caller's own rows on a page of meetings (myAttendance)
    List<MeetingAttendee> findByMeetingIdInAndUserIdAndStatusNot(Collection<Long> meetingIds, Long userId, AttendeeStatus excluded);

    /** Per meeting: attendees (not replaced) and feedback given, for a page of meetings in one statement. */
    @Query(value = """
            SELECT meeting_id, COUNT(*) FILTER (WHERE status <> 'REPLACED'), COUNT(feedback_submitted_at)
            FROM meeting_attendee WHERE meeting_id IN (:meetingIds) GROUP BY meeting_id
            """, nativeQuery = true)
    List<Object[]> countsByMeeting(@Param("meetingIds") Collection<Long> meetingIds);

    /**
     * The scheduled meetings these employees attend (invited, organiser included) that overlap {@code from} to {@code to},
     * other than {@code excludeMeetingId} (0 for none). A meeting lasts at most 12 hours, so {@code earliestStart} (from
     * minus 12 hours) bounds the search. Index: meeting_attendee (employee_id, meeting_id) WHERE status = 'INVITED'.
     */
    @Query("""
            SELECT new com.tinqa.procurement.meeting.MeetingClash(a.employeeId, e.displayName, m.id, m.code, m.startsAt, m.endsAt)
            FROM MeetingAttendee a JOIN Meeting m ON m.id = a.meetingId JOIN Employee e ON e.id = a.employeeId
            WHERE a.employeeId IN :employeeIds AND a.status = com.tinqa.procurement.meeting.AttendeeStatus.INVITED
              AND m.status = com.tinqa.procurement.meeting.MeetingStatus.SCHEDULED AND m.id <> :excludeMeetingId
              AND m.startsAt < :to AND m.endsAt > :from AND m.startsAt > :earliestStart
            ORDER BY m.startsAt, m.id, e.displayName
            """)
    List<MeetingClash> findClashes(@Param("employeeIds") Collection<Long> employeeIds, @Param("from") Instant from,
                                   @Param("to") Instant to, @Param("earliestStart") Instant earliestStart,
                                   @Param("excludeMeetingId") Long excludeMeetingId);

    /**
     * Locks these employees' rows in one statement (in id order), so two meetings booked for the same person at the same
     * moment are checked for clashes one after the other. NO KEY UPDATE still lets attendee rows reference them.
     */
    @Query(value = "SELECT id FROM employee WHERE id IN (:employeeIds) ORDER BY id FOR NO KEY UPDATE", nativeQuery = true)
    List<Long> lockEmployees(@Param("employeeIds") Collection<Long> employeeIds);

    /** Completed meetings the user attended and hasn't given feedback on yet (the menu badge). */
    @Query(value = """
            SELECT COUNT(*) FROM meeting_attendee a JOIN meeting m ON m.id = a.meeting_id
            WHERE a.user_id = :userId AND a.status = 'INVITED' AND a.feedback_submitted_at IS NULL AND m.status = 'COMPLETED'
            """, nativeQuery = true)
    long countPendingFeedback(@Param("userId") Long userId);

    /** 24-hour reminders due, of meetings still scheduled; claimed so two instances never send one twice. */
    @Query(value = """
            SELECT a.id FROM meeting_attendee a JOIN meeting m ON m.id = a.meeting_id
            WHERE a.reminder_due_at IS NOT NULL AND a.reminder_due_at <= :now AND a.status = 'INVITED'
              AND m.status = 'SCHEDULED' AND m.starts_at > :now
            ORDER BY a.reminder_due_at, a.id
            LIMIT :limit
            FOR UPDATE OF a SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockRemindersDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Reminders that can no longer go out (the meeting started, or isn't scheduled any more). */
    @Query(value = """
            SELECT a.id FROM meeting_attendee a JOIN meeting m ON m.id = a.meeting_id
            WHERE a.reminder_due_at IS NOT NULL AND a.reminder_due_at <= :now
              AND (a.status <> 'INVITED' OR m.status <> 'SCHEDULED' OR m.starts_at <= :now)
            LIMIT :limit
            FOR UPDATE OF a SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockStaleReminders(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Emails waiting: not given up on, not being sent by another run, and a failed one only after a pause. Claimed with
     * a skip-locked row lock.
     */
    @Query(value = """
            SELECT id FROM meeting_attendee
            WHERE email_pending IS NOT NULL AND email_attempts < :maxAttempts
              AND (email_claimed_until IS NULL OR email_claimed_until < :now)
              AND (email_attempts = 0 OR email_last_attempt_at < :retryBefore)
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockEmailsDue(@Param("now") Instant now, @Param("retryBefore") Instant retryBefore,
                             @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);
}
