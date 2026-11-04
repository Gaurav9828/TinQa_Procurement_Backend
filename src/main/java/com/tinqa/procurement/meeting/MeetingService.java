package com.tinqa.procurement.meeting;

import com.tinqa.procurement.common.constant.ErrorCodes;
import com.tinqa.procurement.common.exception.ApiErrors;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.util.BusinessTime;
import com.tinqa.procurement.common.util.Pages;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.employee.enums.EmployeeStatus;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.notification.NotificationLinks;
import com.tinqa.procurement.notification.service.NotificationService;
import com.tinqa.procurement.outreach.OutreachBusinessRepository;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Meetings: scheduled without approval by anyone with MEETING_SCHEDULE; only the organiser changes, cancels or
 * completes one. Invitees decline within 24 hours of their invite; afterwards they must attend. Every change that
 * attendees should hear about creates in-app notifications here and queues an email on their attendee row, which
 * {@link MeetingMailer} sends after commit.
 */
@Service
@RequiredArgsConstructor
public class MeetingService {

    static final Duration DECLINE_WINDOW = Duration.ofHours(24);
    static final Duration REMINDER_BEFORE = Duration.ofHours(24);
    static final Duration MAX_LENGTH = Duration.ofHours(12);
    private static final Set<EmployeeStatus> INVITABLE = Set.of(EmployeeStatus.ACTIVE, EmployeeStatus.FIRST_LOGIN);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    // GET /busy: at most two days at a time (a day in any time zone)
    private static final Duration MAX_BUSY_SPAN = Duration.ofHours(48);

    private final MeetingRepository meetingRepository;
    private final MeetingAttendeeRepository attendeeRepository;
    private final MeetingFeedbackRepository feedbackRepository;
    private final EmployeeRepository employeeRepository;
    private final OutreachBusinessRepository outreachBusinessRepository;
    private final MeetingViews views;
    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentAccess currentAccess;
    private final Clock clock;

    // ------------------------------------------------------------------ reads

    /** Every meeting: scheduled ones soonest first, then the rest latest first (or just one status). */
    @Transactional(readOnly = true)
    public Page<MeetingDTOs.Meeting> list(MeetingStatus status, LocalDate from, LocalDate to, String search, Pageable pageable) {
        Specification<Meeting> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("startsAt"), BusinessTime.startOf(from)));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("startsAt"), BusinessTime.startOf(to.plusDays(1))));
            }
            String term = OptionalText.lowerCaseOrNull(search);
            if (term != null) {
                String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("clientName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("code")), pattern, '\\')));
            }
            order(root, query, cb, status);
            return cb.and(where.toArray(Predicate[]::new));
        };
        return page(meetingRepository.findAll(spec, Pages.capped(pageable)));
    }

    /** The meetings the caller organises or is invited to (not those they were replaced on). */
    @Transactional(readOnly = true)
    public Page<MeetingDTOs.Meeting> mine(MeetingStatus status, Pageable pageable) {
        Long me = currentUserProvider.getCurrentUserId();
        Specification<Meeting> spec = (root, query, cb) -> {
            Subquery<Long> attends = query.subquery(Long.class);
            var attendee = attends.from(MeetingAttendee.class);
            attends.select(attendee.get("id")).where(cb.equal(attendee.get("meetingId"), root.get("id")),
                    cb.equal(attendee.get("userId"), me), cb.notEqual(attendee.get("status"), AttendeeStatus.REPLACED));
            List<Predicate> where = new ArrayList<>(List.of(cb.exists(attends)));
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            order(root, query, cb, status);
            return cb.and(where.toArray(Predicate[]::new));
        };
        return page(meetingRepository.findAll(spec, Pages.capped(pageable)));
    }

    /** Completed meetings the caller attended and owes feedback on (one SQL count). */
    @Transactional(readOnly = true)
    public long pendingFeedbackCount() {
        return attendeeRepository.countPendingFeedback(currentUserProvider.getCurrentUserId());
    }

    @Transactional(readOnly = true)
    public MeetingDTOs.Meeting get(Long id) {
        Meeting meeting = find(id);
        requireMayView(meeting);
        return views.detail(meeting, currentUserProvider.getCurrentUserId());
    }

    /** Active employees who can be invited, by name; no contact details. */
    @Transactional(readOnly = true)
    public Page<MeetingDTOs.StaffOption> invitableStaff(String search, Pageable pageable) {
        String term = OptionalText.lowerCaseOrNull(search);
        Pageable page = Pages.capped(pageable);
        Page<Employee> employees = term == null ? employeeRepository.findByStatusInOrderByName(INVITABLE, page)
                : employeeRepository.searchByStatusInOrderByName(INVITABLE,
                "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", page);
        return employees.map(e -> new MeetingDTOs.StaffOption(e.getId(), e.getUserId(), e.getDisplayName(), e.designationLabel()));
    }

    /**
     * Who of these employees, and the caller, is already in another scheduled meeting between {@code from} and
     * {@code to} (at most two days), other than {@code excludeMeetingId}: the clock greys that time out. One statement;
     * small by design (at most one invite list, over two days).
     */
    @Transactional(readOnly = true)
    public List<MeetingDTOs.Busy> busy(OffsetDateTime from, OffsetDateTime to, List<Long> employeeIds, Long excludeMeetingId) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_BUSY_SPAN) > 0) {
            throw ApiErrors.fieldError("to", "Ask for a span of up to two days, ending after it starts.");
        }
        Set<Long> ids = new LinkedHashSet<>(employeeIds == null ? List.of() : employeeIds.stream().filter(Objects::nonNull).toList());
        if (ids.size() > MeetingDTOs.MAX_INVITEES) {
            throw ApiErrors.fieldError("employeeIds", "Ask for at most " + MeetingDTOs.MAX_INVITEES + " people at once.");
        }
        Long self = employeeRepository.findByUserId(currentUserProvider.getCurrentUserId()).map(Employee::getId).orElse(null);
        if (self != null) {
            ids.add(self);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return clashes(ids, from.toInstant(), to.toInstant(), excludeMeetingId).stream()
                .map(clash -> new MeetingDTOs.Busy(clash.employeeId(), clash.name(), clash.employeeId().equals(self), clash.meetingId(),
                        clash.code(), BusinessTime.of(clash.startsAt()), BusinessTime.of(clash.endsAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MeetingDTOs.Feedback myFeedback(Long meetingId) {
        Meeting meeting = find(meetingId);
        MeetingAttendee mine = myAttendance(meeting, attendeeRepository.findByMeetingIdOrderByIdAsc(meetingId));
        MeetingFeedback feedback = mine == null ? null : feedbackRepository.findByAttendeeId(mine.getId()).orElse(null);
        if (feedback == null) {
            throw new ResourceNotFoundException("You haven't given feedback on this meeting.");
        }
        Employee employee = employeeRepository.findById(mine.getEmployeeId()).orElse(null);
        return views.feedback(feedback, employee == null ? null : employee.getDisplayName());
    }

    /** Every attendee's feedback: for the organiser and those who see every meeting. Small by design. */
    @Transactional(readOnly = true)
    public List<MeetingDTOs.Feedback> feedback(Long meetingId) {
        Meeting meeting = find(meetingId);
        if (!meeting.getOrganizerId().equals(currentUserProvider.getCurrentUserId()) && !currentAccess.has(Permission.MEETING_VIEW_ALL)) {
            throw ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "Only the organiser can read this meeting's feedback.");
        }
        List<MeetingAttendee> attendees = attendeeRepository.findByMeetingIdOrderByIdAsc(meetingId);
        Map<Long, MeetingAttendee> byId = attendees.stream().collect(Collectors.toMap(MeetingAttendee::getId, Function.identity()));
        Map<Long, Employee> employees = views.employees(attendees);
        return feedbackRepository.findByMeetingIdOrderByIdAsc(meetingId).stream().map(feedback -> {
            MeetingAttendee attendee = byId.get(feedback.getAttendeeId());
            Employee employee = attendee == null ? null : employees.get(attendee.getEmployeeId());
            return views.feedback(feedback, employee == null ? null : employee.getDisplayName());
        }).toList();
    }

    // ------------------------------------------------------------------ organiser

    @Transactional
    public MeetingDTOs.Meeting schedule(MeetingDTOs.MeetingRequest request) {
        Long me = currentUserProvider.getCurrentUserId();
        Employee organizer = employeeRepository.findByUserId(me).orElseThrow(() -> ApiErrors.forbidden(ErrorCodes.FORBIDDEN,
                "Only employees can organise meetings."));
        Instant now = clock.instant();
        Meeting meeting = Meeting.builder()
                .code(String.format("MTG-%d-%04d", BusinessTime.today(clock).getYear(), meetingRepository.nextCodeNumber()))
                .status(MeetingStatus.SCHEDULED)
                .organizerId(me)
                .createdAt(now)
                .build();
        apply(meeting, request, now, true);
        meeting.setUpdatedAt(now);
        List<Employee> invitees = invitees(request.attendeeEmployeeIds(), "attendeeEmployeeIds", organizer.getId());
        if (invitees.isEmpty()) {
            throw ApiErrors.fieldError("attendeeEmployeeIds", "Invite at least one person besides yourself.");
        }
        List<Long> everyone = new ArrayList<>(List.of(organizer.getId()));
        invitees.forEach(employee -> everyone.add(employee.getId()));
        requireFree(meeting, everyone, organizer.getId());
        Meeting saved = meetingRepository.saveAndFlush(meeting);

        MeetingAttendee organizerRow = newAttendee(saved, organizer, true, now);
        List<MeetingAttendee> rows = new ArrayList<>(List.of(organizerRow));
        invitees.forEach(employee -> rows.add(newAttendee(saved, employee, false, now)));
        attendeeRepository.saveAll(rows);
        notifyInvited(saved, rows.subList(1, rows.size()), organizer.getDisplayName());
        return views.detail(saved, me);
    }

    /** Changes the details; a new time, place or link emails every attendee again and moves their reminder. */
    @Transactional
    public MeetingDTOs.Meeting update(Long id, MeetingDTOs.MeetingRequest request) {
        Long me = currentUserProvider.getCurrentUserId();
        Meeting meeting = lockScheduledAsOrganizer(id, me);
        Instant now = clock.instant();
        Instant oldStart = meeting.getStartsAt();
        Instant oldEnd = meeting.getEndsAt();
        String oldPlace = placeOf(meeting);
        apply(meeting, request, now, !request.startsAt().toInstant().truncatedTo(ChronoUnit.SECONDS).equals(oldStart));
        boolean timeChanged = !meeting.getStartsAt().equals(oldStart);
        boolean scheduleChanged = timeChanged || !placeOf(meeting).equals(oldPlace);
        meeting.setUpdatedAt(now);
        List<MeetingAttendee> live = scheduleChanged
                ? attendeeRepository.findByMeetingIdOrderByIdAsc(id).stream().filter(MeetingAttendee::live).toList() : List.of();
        if (timeChanged || !meeting.getEndsAt().equals(oldEnd)) {
            // Everyone still attending (the organiser included) must be free at the new time
            requireFree(meeting, live.stream().map(MeetingAttendee::getEmployeeId).toList(),
                    live.stream().filter(MeetingAttendee::isOrganizer).map(MeetingAttendee::getEmployeeId).findFirst().orElse(null));
        }
        if (scheduleChanged) {
            meeting.setScheduleSequence(meeting.getScheduleSequence() + 1);
            for (MeetingAttendee attendee : live) {
                attendee.queueEmail(MeetingEmailKind.UPDATED);
                if (timeChanged) {
                    planReminder(attendee, meeting.getStartsAt(), now);
                    if (!attendee.isOrganizer()) {
                        attendee.setDeclineDeadline(declineDeadline(attendee.getInvitedAt(), meeting.getStartsAt()));
                    }
                }
                attendee.setUpdatedAt(now);
            }
            attendeeRepository.saveAll(live);
            notify(live.stream().filter(a -> !a.isOrganizer()).toList(), "Meeting updated",
                    meeting.getTitle() + " (" + meeting.getCode() + ") has changed: now " + when(meeting) + ", " + where(meeting) + ".",
                    link(meeting));
        }
        meetingRepository.save(meeting);
        return views.detail(meeting, me);
    }

    /** Invites more people, or one person in place of an attendee who declined. */
    @Transactional
    public MeetingDTOs.Meeting addAttendees(Long id, MeetingDTOs.AttendeesRequest request) {
        Long me = currentUserProvider.getCurrentUserId();
        Meeting meeting = lockScheduledAsOrganizer(id, me);
        Instant now = clock.instant();
        if (!meeting.getStartsAt().isAfter(now)) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, "This meeting has already started; nobody more can be invited.");
        }
        List<MeetingAttendee> existing = attendeeRepository.findByMeetingIdOrderByIdAsc(id);
        MeetingAttendee replaced = null;
        if (request.replacesAttendeeId() != null) {
            replaced = existing.stream().filter(a -> a.getId().equals(request.replacesAttendeeId())).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Attendee not found with id: " + request.replacesAttendeeId()));
            if (replaced.getStatus() != AttendeeStatus.DECLINED) {
                throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, "Only an attendee who declined can be replaced.");
            }
            if (request.employeeIds().size() != 1) {
                throw ApiErrors.fieldError("employeeIds", "Pick exactly one person to take their place.");
            }
        }
        Set<Long> invited = existing.stream().filter(a -> a.getStatus() != AttendeeStatus.REPLACED)
                .map(MeetingAttendee::getEmployeeId).collect(Collectors.toSet());
        List<Employee> invitees = invitees(request.employeeIds(), "employeeIds", null);
        List<Employee> already = invitees.stream().filter(e -> invited.contains(e.getId())).toList();
        if (!already.isEmpty()) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_ALREADY_INVITED, already.getFirst().getDisplayName()
                    + " is already on this meeting's list.");
        }
        requireFree(meeting, invitees.stream().map(Employee::getId).toList(), null);
        List<MeetingAttendee> rows = invitees.stream().map(employee -> newAttendee(meeting, employee, false, now)).toList();
        try {
            attendeeRepository.saveAllAndFlush(rows);
        } catch (DataIntegrityViolationException exception) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_ALREADY_INVITED, "Someone on the list was just invited. Reload the meeting.");
        }
        if (replaced != null) {
            replaced.setStatus(AttendeeStatus.REPLACED);
            replaced.setReplacedById(rows.getFirst().getId());
            replaced.setUpdatedAt(now);
            attendeeRepository.save(replaced);
        }
        String organizerName = employeeRepository.findByUserId(me).map(Employee::getDisplayName).orElse(null);
        notifyInvited(meeting, rows, organizerName);
        meeting.setUpdatedAt(now);
        meetingRepository.save(meeting);
        return views.detail(meeting, me);
    }

    @Transactional
    public MeetingDTOs.Meeting cancel(Long id, MeetingDTOs.ReasonRequest request) {
        Long me = currentUserProvider.getCurrentUserId();
        Meeting meeting = lockScheduledAsOrganizer(id, me);
        Instant now = clock.instant();
        meeting.setStatus(MeetingStatus.CANCELLED);
        meeting.setCancelReason(request.reason().trim());
        meeting.setCancelledAt(now);
        meeting.setUpdatedAt(now);
        List<MeetingAttendee> live = attendeeRepository.findByMeetingIdOrderByIdAsc(id).stream().filter(MeetingAttendee::live).toList();
        for (MeetingAttendee attendee : live) {
            attendee.queueEmail(MeetingEmailKind.CANCELLED);
            attendee.setReminderDueAt(null);
            attendee.setUpdatedAt(now);
        }
        attendeeRepository.saveAll(live);
        meetingRepository.save(meeting);
        notify(live.stream().filter(a -> !a.isOrganizer()).toList(), "Meeting cancelled",
                meeting.getTitle() + " (" + meeting.getCode() + ") on " + when(meeting) + " was cancelled. Reason: "
                        + meeting.getCancelReason(), link(meeting));
        return views.detail(meeting, me);
    }

    /** Marks a meeting that has started as completed, and asks every attendee for feedback. */
    @Transactional
    public MeetingDTOs.Meeting complete(Long id) {
        Long me = currentUserProvider.getCurrentUserId();
        Meeting meeting = lockScheduledAsOrganizer(id, me);
        Instant now = clock.instant();
        if (meeting.getStartsAt().isAfter(now)) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, "A meeting can be completed once it has started ("
                    + when(meeting) + ").");
        }
        meeting.setStatus(MeetingStatus.COMPLETED);
        meeting.setCompletedAt(now);
        meeting.setUpdatedAt(now);
        List<MeetingAttendee> live = attendeeRepository.findByMeetingIdOrderByIdAsc(id).stream().filter(MeetingAttendee::live).toList();
        for (MeetingAttendee attendee : live) {
            attendee.queueEmail(MeetingEmailKind.FEEDBACK_REQUEST);
            attendee.setReminderDueAt(null);
            attendee.setUpdatedAt(now);
        }
        attendeeRepository.saveAll(live);
        meetingRepository.save(meeting);
        notify(live, "Feedback requested", "How did " + meeting.getTitle() + " (" + meeting.getCode()
                + ") go? Give your feedback: agenda, what was decided and whether a deal was confirmed.", link(meeting) + "/feedback");
        return views.detail(meeting, me);
    }

    // ------------------------------------------------------------------ attendees

    /** Declines an invite, within 24 hours of it (and before the meeting); the organiser is told. */
    @Transactional
    public MeetingDTOs.Meeting decline(Long id, MeetingDTOs.ReasonRequest request) {
        Long me = currentUserProvider.getCurrentUserId();
        Meeting meeting = meetingRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        MeetingAttendee mine = myAttendance(meeting, attendeeRepository.findByMeetingIdOrderByIdAsc(id));
        if (mine == null) {
            throw ApiErrors.forbidden(ErrorCodes.MEETING_NOT_INVITED, "You are not invited to this meeting.");
        }
        if (mine.isOrganizer()) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, "You organise this meeting, so you can't decline it. Cancel it instead.");
        }
        if (meeting.getStatus() != MeetingStatus.SCHEDULED || mine.getStatus() != AttendeeStatus.INVITED) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, "This invitation can no longer be declined.");
        }
        Instant now = clock.instant();
        if (now.isAfter(mine.getDeclineDeadline())) {
            throw ApiErrors.conflict(ErrorCodes.DECLINE_WINDOW_CLOSED, "Invitations can be declined only within 24 hours of being "
                    + "invited (yours closed " + WHEN.format(mine.getDeclineDeadline().atZone(BusinessTime.ZONE)) + "). Please attend.");
        }
        mine.setStatus(AttendeeStatus.DECLINED);
        mine.setDeclinedAt(now);
        mine.setDeclineReason(request.reason().trim());
        mine.setReminderDueAt(null);
        mine.dropEmail();
        mine.setUpdatedAt(now);
        attendeeRepository.save(mine);
        String name = employeeRepository.findById(mine.getEmployeeId()).map(Employee::getDisplayName).orElse("An attendee");
        notificationService.createEach(List.of(new NotificationService.Message(meeting.getOrganizerId(), "Meeting invite declined",
                name + " declined " + meeting.getTitle() + " (" + meeting.getCode() + "). Reason: " + mine.getDeclineReason()
                        + ". You can invite someone in their place.", link(meeting))));
        return views.detail(meeting, me);
    }

    /** One attendee's feedback on a completed meeting; final once given. */
    @Transactional
    public MeetingDTOs.Feedback submitFeedback(Long id, MeetingDTOs.FeedbackRequest request) {
        Meeting meeting = meetingRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        MeetingAttendee mine = myAttendance(meeting, attendeeRepository.findByMeetingIdOrderByIdAsc(id));
        if (mine == null || mine.getStatus() != AttendeeStatus.INVITED) {
            throw ApiErrors.forbidden(ErrorCodes.MEETING_NOT_INVITED, "Only the meeting's attendees give feedback on it.");
        }
        if (meeting.getStatus() != MeetingStatus.COMPLETED) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, "Feedback is given once the organiser marks the meeting completed.");
        }
        if (mine.getFeedbackSubmittedAt() != null) {
            throw ApiErrors.conflict(ErrorCodes.FEEDBACK_ALREADY_SUBMITTED, "You have already given feedback on this meeting.");
        }
        Instant now = clock.instant();
        MeetingFeedback feedback = feedbackRepository.save(MeetingFeedback.builder()
                .meetingId(id)
                .attendeeId(mine.getId())
                .agenda(request.agenda().trim())
                .feedback(request.feedback().trim())
                .decisions(request.decisions().trim())
                .dealConfirmed(Boolean.TRUE.equals(request.dealConfirmed()))
                .dealNotes(OptionalText.blankToNull(request.dealNotes()))
                .meetingType(request.meetingType())
                .rating(request.rating())
                .submittedAt(now)
                .build());
        mine.setFeedbackSubmittedAt(now);
        mine.setUpdatedAt(now);
        attendeeRepository.save(mine);
        String name = employeeRepository.findById(mine.getEmployeeId()).map(Employee::getDisplayName).orElse(null);
        return views.feedback(feedback, name);
    }

    // ------------------------------------------------------------------ rules

    private void apply(Meeting meeting, MeetingDTOs.MeetingRequest request, Instant now, boolean startMustBeFuture) {
        List<ApiResponse.ApiErrorItem> errors = new ArrayList<>();
        Instant startsAt = request.startsAt().toInstant().truncatedTo(ChronoUnit.SECONDS);
        Instant endsAt = request.endsAt().toInstant().truncatedTo(ChronoUnit.SECONDS);
        if (startMustBeFuture && !startsAt.isAfter(now)) {
            errors.add(ApiErrors.error("startsAt", "The meeting must start in the future."));
        }
        if (!endsAt.isAfter(startsAt)) {
            errors.add(ApiErrors.error("endsAt", "The meeting must end after it starts."));
        } else if (Duration.between(startsAt, endsAt).compareTo(MAX_LENGTH) > 0) {
            errors.add(ApiErrors.error("endsAt", "A meeting lasts at most 12 hours."));
        } else if (!startsAt.atZone(BusinessTime.ZONE).toLocalDate().equals(endsAt.atZone(BusinessTime.ZONE).toLocalDate())) {
            // One day, in the company's time zone (requests arrive in UTC, so the sender's offset is not kept)
            errors.add(ApiErrors.error("endsAt", "A meeting starts and ends on the same day."));
        }
        String joinUrl = OptionalText.blankToNull(request.joinUrl());
        String placeName = OptionalText.blankToNull(request.placeName());
        String address = OptionalText.blankToNull(request.address());
        if (request.mode() == MeetingMode.ONLINE) {
            if (joinUrl == null) {
                errors.add(ApiErrors.error("joinUrl", "Paste the meeting link (Google Meet, Teams, Zoom …)."));
            } else if (!isHttpsLink(joinUrl)) {
                errors.add(ApiErrors.error("joinUrl", "The meeting link must be an https link."));
            }
        } else {
            if (placeName == null) {
                errors.add(ApiErrors.error("placeName", "Where is the meeting? Give the place's name."));
            }
            if (address == null) {
                errors.add(ApiErrors.error("address", "Give the place's address."));
            }
        }
        if (request.outreachBusinessId() != null && !outreachBusinessRepository.existsById(request.outreachBusinessId())) {
            errors.add(ApiErrors.error("outreachBusinessId", "That business isn't on the outreach list."));
        }
        ApiErrors.throwIfAny(errors);

        meeting.setMode(request.mode());
        meeting.setTitle(request.title().trim());
        meeting.setSubject(OptionalText.blankToNull(request.subject()));
        meeting.setDescription(OptionalText.blankToNull(request.description()));
        meeting.setMeetingType(request.meetingType());
        meeting.setClientName(OptionalText.blankToNull(request.clientName()));
        meeting.setClientDescription(OptionalText.blankToNull(request.clientDescription()));
        meeting.setClientContact(OptionalText.blankToNull(request.clientContact()));
        meeting.setOutreachBusinessId(request.outreachBusinessId());
        meeting.setStartsAt(startsAt);
        meeting.setEndsAt(endsAt);
        boolean online = request.mode() == MeetingMode.ONLINE;
        meeting.setJoinUrl(online ? joinUrl : null);
        meeting.setPlaceName(online ? null : placeName);
        meeting.setAddress(online ? null : address);
        meeting.setGoogleMapsUrl(online ? null : OptionalText.blankToNull(request.googleMapsUrl()));
    }

    static boolean isHttpsLink(String value) {
        if (value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    // The invited employees, each active and listed once (the organiser, when given, is left out)
    private List<Employee> invitees(List<Long> employeeIds, String field, Long organizerEmployeeId) {
        Set<Long> ids = new LinkedHashSet<>(employeeIds == null ? List.of() : employeeIds.stream().filter(Objects::nonNull).toList());
        if (organizerEmployeeId != null) {
            ids.remove(organizerEmployeeId);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Employee> found = employeeRepository.findAllById(ids).stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<Employee> invitees = new ArrayList<>();
        for (Long employeeId : ids) {
            Employee employee = found.get(employeeId);
            if (employee == null || !INVITABLE.contains(employee.getStatus())) {
                throw ApiErrors.fieldError(field, "Employee " + employeeId + " can't be invited: only active employees can.");
            }
            invitees.add(employee);
        }
        return invitees;
    }

    /**
     * Nobody can be in two meetings at once: refuses (409 MEETING_TIME_CLASH) when any of these employees is already in
     * another scheduled meeting overlapping this one, naming each of them, the meeting and its time. Their rows are
     * locked first, so two meetings booked for the same person at the same moment can't both pass.
     */
    private void requireFree(Meeting meeting, Collection<Long> employeeIds, Long organizerEmployeeId) {
        if (employeeIds.isEmpty()) {
            return;
        }
        attendeeRepository.lockEmployees(employeeIds);
        List<MeetingClash> clashes = clashes(employeeIds, meeting.getStartsAt(), meeting.getEndsAt(), meeting.getId());
        if (clashes.isEmpty()) {
            return;
        }
        List<ApiResponse.ApiErrorItem> errors = clashes.stream().map(clash -> ApiErrors.error("employee:" + clash.employeeId(),
                (clash.employeeId().equals(organizerEmployeeId) ? "You are" : clash.name() + " is") + " already in " + clash.code()
                        + " (" + WHEN.format(clash.startsAt().atZone(BusinessTime.ZONE)) + " – "
                        + UNTIL.format(clash.endsAt().atZone(BusinessTime.ZONE)) + ")")).toList();
        throw new ApiException("Time clash: " + ApiErrors.joinMessages(errors) + ". Pick another time, or invite someone else.",
                ErrorCodes.MEETING_TIME_CLASH, HttpStatus.CONFLICT, errors);
    }

    private List<MeetingClash> clashes(Collection<Long> employeeIds, Instant from, Instant to, Long excludeMeetingId) {
        return attendeeRepository.findClashes(employeeIds, from, to, from.minus(MAX_LENGTH), excludeMeetingId == null ? 0L : excludeMeetingId);
    }

    private MeetingAttendee newAttendee(Meeting meeting, Employee employee, boolean organizer, Instant now) {
        MeetingAttendee attendee = MeetingAttendee.builder()
                .meetingId(meeting.getId())
                .employeeId(employee.getId())
                .userId(employee.getUserId())
                .organizer(organizer)
                .status(AttendeeStatus.INVITED)
                .invitedAt(now)
                // The organiser can't decline
                .declineDeadline(organizer ? now : declineDeadline(now, meeting.getStartsAt()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        attendee.queueEmail(MeetingEmailKind.INVITE);
        planReminder(attendee, meeting.getStartsAt(), now);
        return attendee;
    }

    static Instant declineDeadline(Instant invitedAt, Instant startsAt) {
        Instant window = invitedAt.plus(DECLINE_WINDOW);
        return window.isBefore(startsAt) ? window : startsAt;
    }

    // 24 hours before the start; a meeting less than 24 hours away was just announced (invite or update), which counts
    static void planReminder(MeetingAttendee attendee, Instant startsAt, Instant now) {
        Instant due = startsAt.minus(REMINDER_BEFORE);
        if (due.isAfter(now)) {
            attendee.setReminderDueAt(due);
            attendee.setReminderSentAt(null);
        } else {
            attendee.setReminderDueAt(null);
            attendee.setReminderSentAt(now);
        }
    }

    private void notifyInvited(Meeting meeting, List<MeetingAttendee> invitees, String organizerName) {
        String intro = organizerName == null ? "You are invited to " : organizerName + " invited you to ";
        notificationService.createEach(invitees.stream().map(attendee -> new NotificationService.Message(attendee.getUserId(),
                "Meeting invite", intro + meeting.getTitle() + " (" + meeting.getCode() + ") on " + when(meeting) + ", "
                + where(meeting) + ". You can decline until " + WHEN.format(attendee.getDeclineDeadline().atZone(BusinessTime.ZONE))
                + ".", link(meeting))).toList());
    }

    private void notify(List<MeetingAttendee> attendees, String title, String message, String link) {
        notificationService.createEach(attendees.stream()
                .map(attendee -> new NotificationService.Message(attendee.getUserId(), title, message, link)).toList());
    }

    private Meeting lockScheduledAsOrganizer(Long id, Long me) {
        Meeting meeting = meetingRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (!meeting.getOrganizerId().equals(me)) {
            throw ApiErrors.forbidden(ErrorCodes.MEETING_NOT_ORGANIZER, "Only the organiser can change this meeting.");
        }
        if (meeting.getStatus() != MeetingStatus.SCHEDULED) {
            throw ApiErrors.conflict(ErrorCodes.MEETING_WRONG_STATUS, meeting.getCode() + " is "
                    + meeting.getStatus().name().toLowerCase(Locale.ROOT) + " and can no longer be changed.");
        }
        return meeting;
    }

    private void requireMayView(Meeting meeting) {
        if (currentAccess.has(Permission.MEETING_VIEW_ALL)) {
            return;
        }
        Long me = currentUserProvider.getCurrentUserId();
        boolean attends = meeting.getOrganizerId().equals(me)
                || myAttendance(meeting, attendeeRepository.findByMeetingIdOrderByIdAsc(meeting.getId())) != null;
        if (!attends || !currentAccess.has(Permission.MEETING_VIEW_OWN)) {
            throw ApiErrors.forbidden(ErrorCodes.FORBIDDEN, "You are not invited to this meeting.");
        }
    }

    private MeetingAttendee myAttendance(Meeting meeting, List<MeetingAttendee> attendees) {
        Long me = currentUserProvider.getCurrentUserId();
        return attendees.stream().filter(a -> a.getUserId().equals(me) && a.getStatus() != AttendeeStatus.REPLACED)
                .findFirst().orElse(null);
    }

    // Scheduled meetings soonest first, the rest latest first (index: status, starts_at). The count query drops it
    private static void order(jakarta.persistence.criteria.Root<Meeting> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
                              jakarta.persistence.criteria.CriteriaBuilder cb, MeetingStatus status) {
        if (query == null || query.getResultType() == Long.class || query.getResultType() == long.class) {
            return;
        }
        if (status == MeetingStatus.SCHEDULED) {
            query.orderBy(cb.asc(root.get("startsAt")), cb.asc(root.get("id")));
        } else if (status != null) {
            query.orderBy(cb.desc(root.get("startsAt")), cb.desc(root.get("id")));
        } else {
            var scheduledFirst = cb.selectCase().when(cb.equal(root.get("status"), MeetingStatus.SCHEDULED), 0).otherwise(1);
            var upcoming = cb.selectCase().when(cb.equal(root.get("status"), MeetingStatus.SCHEDULED), root.get("startsAt"));
            query.orderBy(cb.asc(scheduledFirst), cb.asc(upcoming), cb.desc(root.get("startsAt")), cb.desc(root.get("id")));
        }
    }

    private Page<MeetingDTOs.Meeting> page(Page<Meeting> meetings) {
        return new PageImpl<>(views.summaries(meetings.getContent(), currentUserProvider.getCurrentUserId()),
                meetings.getPageable(), meetings.getTotalElements());
    }

    private static String placeOf(Meeting meeting) {
        return meeting.getMode() + "|" + meeting.getJoinUrl() + "|" + meeting.getPlaceName() + "|" + meeting.getAddress() + "|"
                + meeting.getGoogleMapsUrl() + "|" + meeting.getEndsAt();
    }

    static String when(Meeting meeting) {
        return WHEN.format(meeting.getStartsAt().atZone(BusinessTime.ZONE));
    }

    static String where(Meeting meeting) {
        return meeting.getMode() == MeetingMode.ONLINE ? "online" : "at " + meeting.getPlaceName();
    }

    static String link(Meeting meeting) {
        return NotificationLinks.MEETINGS + "/" + meeting.getId();
    }

    private Meeting find(Long id) {
        return meetingRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Meeting not found with id: " + id);
    }
}
