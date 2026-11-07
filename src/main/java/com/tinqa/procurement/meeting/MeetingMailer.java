package com.tinqa.procurement.meeting;

import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.StaffNames;
import com.tinqa.procurement.mail.MeetingEmail;
import com.tinqa.procurement.mail.StaffMailException;
import com.tinqa.procurement.mail.StaffMailer;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.security.model.User;
import com.tinqa.procurement.security.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The meeting jobs (run by {@link MeetingJobs}): 24-hour reminders, and the emails queued on attendee rows.
 *
 * <p>Emails never go out inside a database transaction: a short transaction claims a batch (skip-locked, so two
 * instances never take the same row, and leased for a few minutes in case this one dies), the emails are sent through
 * the e-commerce service, then a second short transaction records each result, only if nothing newer was queued for
 * that attendee meanwhile (email_seq). A failed email is retried a few times, some minutes apart. When nothing is due
 * each job is one indexed query.
 */
@Slf4j
@Service
public class MeetingMailer {

    static final int MAX_ATTEMPTS = 5;
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final int EMAIL_BATCH = 25;
    private static final int REMINDER_BATCH = 200;
    private static final int ERROR_MAX = 500;

    private final MeetingAttendeeRepository attendeeRepository;
    private final MeetingRepository meetingRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final StaffNames staffNames;
    private final NotificationService notificationService;
    private final StaffMailer staffMailer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MeetingMailer(MeetingAttendeeRepository attendeeRepository, MeetingRepository meetingRepository,
                         EmployeeRepository employeeRepository, UserRepository userRepository, StaffNames staffNames,
                         NotificationService notificationService, StaffMailer staffMailer, JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.attendeeRepository = attendeeRepository;
        this.meetingRepository = meetingRepository;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.staffNames = staffNames;
        this.notificationService = notificationService;
        this.staffMailer = staffMailer;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reminders

    /** In-app reminders for meetings starting within 24 hours, each with a reminder email queued; returns how many. */
    public int sendReminders() {
        Integer sent = transaction.execute(status -> remindBatch(clock.instant()));
        return sent == null ? 0 : sent;
    }

    private int remindBatch(Instant now) {
        // Reminders that can no longer go out (meeting started or no longer scheduled) are dropped
        List<Long> stale = attendeeRepository.lockStaleReminders(now, REMINDER_BATCH);
        if (!stale.isEmpty()) {
            jdbc.batchUpdate("UPDATE meeting_attendee SET reminder_due_at = NULL WHERE id = ?", stale, stale.size(),
                    (ps, id) -> ps.setLong(1, id));
        }
        List<Long> due = attendeeRepository.lockRemindersDue(now, REMINDER_BATCH);
        if (due.isEmpty()) {
            return 0;
        }
        List<MeetingAttendee> attendees = attendeeRepository.findAllById(due);
        Map<Long, Meeting> meetings = meetingRepository.findAllById(attendees.stream().map(MeetingAttendee::getMeetingId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Meeting::getId, Function.identity()));
        List<NotificationService.Message> messages = new ArrayList<>();
        for (MeetingAttendee attendee : attendees) {
            Meeting meeting = meetings.get(attendee.getMeetingId());
            attendee.queueEmail(MeetingEmailKind.REMINDER);
            attendee.setReminderDueAt(null);
            attendee.setReminderSentAt(now);
            attendee.setUpdatedAt(now);
            messages.add(new NotificationService.Message(attendee.getUserId(), "Meeting tomorrow",
                    meeting.getTitle() + " (" + meeting.getCode() + ") starts " + MeetingService.when(meeting) + ", "
                            + MeetingService.where(meeting) + ".", MeetingService.link(meeting)));
        }
        attendeeRepository.saveAll(attendees);
        notificationService.createEach(messages);
        return attendees.size();
    }

    // ------------------------------------------------------------------ emails

    private record Claimed(long attendeeId, int seq, MeetingEmail email, String unsendable) {
    }

    private record Result(Claimed claimed, boolean sent, String error) {
    }

    /** Sends the emails that are due; returns how many went out. */
    public int sendEmails() {
        List<Claimed> claimed = transaction.execute(status -> claim(clock.instant()));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        List<Result> results = new ArrayList<>();
        for (Claimed item : claimed) {
            if (item.unsendable() != null) {
                results.add(new Result(item, false, item.unsendable()));
                continue;
            }
            try {
                staffMailer.sendMeetingEmail(item.email());
                results.add(new Result(item, true, null));
            } catch (StaffMailException exception) {
                if (log.isWarnEnabled()) {
                    log.warn("Meeting email {} to attendee {} failed: {}", item.email().kind(), item.attendeeId(), exception.getMessage());
                }
                results.add(new Result(item, false, exception.isNotConfigured()
                        ? "Email is not set up yet (Company Profile › Email)." : exception.getMessage()));
            } catch (RuntimeException exception) {
                if (log.isErrorEnabled()) {
                    log.error("Meeting email to attendee {} failed", item.attendeeId(), exception);
                }
                results.add(new Result(item, false, "The email could not be sent."));
            }
        }
        transaction.executeWithoutResult(status -> record(results, clock.instant()));
        return (int) results.stream().filter(Result::sent).count();
    }

    private List<Claimed> claim(Instant now) {
        List<Long> ids = attendeeRepository.lockEmailsDue(now, now.minus(RETRY_AFTER), MAX_ATTEMPTS, EMAIL_BATCH);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<MeetingAttendee> attendees = attendeeRepository.findAllById(ids);
        Map<Long, Meeting> meetings = meetingRepository.findAllById(attendees.stream().map(MeetingAttendee::getMeetingId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Meeting::getId, Function.identity()));
        Map<Long, Employee> employees = employeeRepository.findAllById(attendees.stream().map(MeetingAttendee::getEmployeeId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        Set<Long> userIds = new HashSet<>();
        attendees.forEach(a -> userIds.add(a.getUserId()));
        meetings.values().forEach(m -> userIds.add(m.getOrganizerId()));
        Map<Long, User> users = userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, String> names = staffNames.of(userIds);

        List<Claimed> claimed = new ArrayList<>();
        for (MeetingAttendee attendee : attendees) {
            Meeting meeting = meetings.get(attendee.getMeetingId());
            Employee employee = employees.get(attendee.getEmployeeId());
            User account = users.get(attendee.getUserId());
            User organizer = users.get(meeting.getOrganizerId());
            attendee.setEmailAttempts(attendee.getEmailAttempts() + 1);
            attendee.setEmailLastAttemptAt(now);
            attendee.setEmailClaimedUntil(now.plus(CLAIM_LEASE));
            String to = recipient(employee, account);
            MeetingEmailKind kind = attendee.getEmailPending();
            MeetingEmail email = new MeetingEmail(kind.name(), to, employee == null ? null : employee.getDisplayName(),
                    meeting.getId(), meeting.getCode(), meeting.getCode() + "@tinqa", meeting.getScheduleSequence()
                    + (kind == MeetingEmailKind.CANCELLED ? 1 : 0), meeting.getTitle(), meeting.getSubject(), meeting.getMode().name(),
                    meeting.getMeetingType().label(), meeting.getStartsAt(), meeting.getEndsAt(), meeting.getJoinUrl(),
                    meeting.getPlaceName(), meeting.getAddress(), meeting.getGoogleMapsUrl(), names.get(meeting.getOrganizerId()),
                    organizer == null ? null : organizer.getEmail(), meeting.getCancelReason(),
                    kind == MeetingEmailKind.INVITE && !attendee.isOrganizer() ? attendee.getDeclineDeadline() : null);
            claimed.add(new Claimed(attendee.getId(), attendee.getEmailSeq(), email,
                    to == null ? "The employee has no email address." : null));
        }
        attendeeRepository.saveAll(attendees);
        return claimed;
    }

    // Each result counts only if nothing newer was queued for the attendee since it was claimed
    private void record(List<Result> results, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<Result> done = results.stream().filter(r -> r.sent() || r.claimed().unsendable() != null).toList();
        List<Result> failed = results.stream().filter(r -> !r.sent() && r.claimed().unsendable() == null).toList();
        if (!done.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE meeting_attendee SET email_pending = NULL, email_attempts = 0, email_claimed_until = NULL,
                           email_sent_at = CASE WHEN ? THEN ? ELSE email_sent_at END, email_error = ?, updated_at = ?
                    WHERE id = ? AND email_seq = ?""", done, done.size(), (ps, r) -> {
                ps.setBoolean(1, r.sent());
                ps.setTimestamp(2, at);
                ps.setString(3, r.sent() ? null : truncate(r.error()));
                ps.setTimestamp(4, at);
                ps.setLong(5, r.claimed().attendeeId());
                ps.setInt(6, r.claimed().seq());
            });
        }
        if (!failed.isEmpty()) {
            jdbc.batchUpdate("UPDATE meeting_attendee SET email_error = ?, email_claimed_until = NULL WHERE id = ? AND email_seq = ?",
                    failed, failed.size(), (ps, r) -> {
                        ps.setString(1, truncate(r.error()));
                        ps.setLong(2, r.claimed().attendeeId());
                        ps.setInt(3, r.claimed().seq());
                    });
        }
    }

    /** The employee's personal email (verified when the account was created), else their sign-in email. */
    static String recipient(Employee employee, User account) {
        return Stream.of(employee == null ? null : employee.getPersonalEmail(), account == null ? null : account.getEmail())
                .filter(email -> email != null && !email.isBlank()).map(String::trim).findFirst().orElse(null);
    }

    private static String truncate(String error) {
        return error == null || error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
    }
}
