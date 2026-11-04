package com.tinqa.procurement.meeting;

import com.tinqa.procurement.common.util.BusinessTime;
import com.tinqa.procurement.employee.model.Employee;
import com.tinqa.procurement.employee.repository.EmployeeRepository;
import com.tinqa.procurement.employee.service.StaffNames;
import com.tinqa.procurement.security.access.CurrentAccess;
import com.tinqa.procurement.security.access.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds meeting views with a fixed number of queries, whatever the number of meetings or attendees. */
@Component
@RequiredArgsConstructor
class MeetingViews {

    private final MeetingAttendeeRepository attendeeRepository;
    private final EmployeeRepository employeeRepository;
    private final StaffNames staffNames;
    private final CurrentAccess currentAccess;

    /** One meeting with every attendee. */
    MeetingDTOs.Meeting detail(Meeting meeting, Long me) {
        List<MeetingAttendee> attendees = attendeeRepository.findByMeetingIdOrderByIdAsc(meeting.getId());
        Map<Long, Employee> employees = employees(attendees);
        Map<Long, MeetingAttendee> byId = attendees.stream().collect(Collectors.toMap(MeetingAttendee::getId, Function.identity()));
        List<MeetingDTOs.Attendee> views = attendees.stream().map(a -> attendee(a, employees, byId)).toList();
        MeetingAttendee mine = attendees.stream().filter(a -> a.getUserId().equals(me) && a.getStatus() != AttendeeStatus.REPLACED)
                .findFirst().orElse(null);
        long attendeeCount = attendees.stream().filter(a -> a.getStatus() != AttendeeStatus.REPLACED).count();
        long feedbackCount = attendees.stream().filter(a -> a.getFeedbackSubmittedAt() != null).count();
        String organizerName = staffNames.of(List.of(meeting.getOrganizerId())).get(meeting.getOrganizerId());
        return meeting(meeting, organizerName, views, feedbackCount, attendeeCount,
                mine == null ? null : attendee(mine, employees, byId), me);
    }

    /** A page of meetings without their attendees: counts, and the caller's own attendance. */
    List<MeetingDTOs.Meeting> summaries(List<Meeting> meetings, Long me) {
        if (meetings.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = meetings.stream().map(Meeting::getId).collect(Collectors.toSet());
        Map<Long, long[]> counts = new HashMap<>();
        attendeeRepository.countsByMeeting(ids).forEach(row -> counts.put(((Number) row[0]).longValue(),
                new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()}));
        List<MeetingAttendee> mine = attendeeRepository.findByMeetingIdInAndUserIdAndStatusNot(ids, me, AttendeeStatus.REPLACED);
        Map<Long, Employee> employees = employees(mine);
        Map<Long, MeetingAttendee> myRows = mine.stream().collect(Collectors.toMap(MeetingAttendee::getMeetingId, Function.identity(),
                (a, b) -> a));
        Map<Long, String> organizers = staffNames.of(meetings.stream().map(Meeting::getOrganizerId).toList());
        return meetings.stream().map(m -> {
            long[] count = counts.getOrDefault(m.getId(), new long[]{0, 0});
            MeetingAttendee my = myRows.get(m.getId());
            return meeting(m, organizers.get(m.getOrganizerId()), null, count[1], count[0],
                    my == null ? null : attendee(my, employees, Map.of()), me);
        }).toList();
    }

    MeetingDTOs.Feedback feedback(MeetingFeedback feedback, String attendeeName) {
        return new MeetingDTOs.Feedback(feedback.getId(), feedback.getAttendeeId(), attendeeName, feedback.getAgenda(),
                feedback.getFeedback(), feedback.getDecisions(), feedback.isDealConfirmed(), feedback.getDealNotes(),
                feedback.getMeetingType(), feedback.getRating(), BusinessTime.of(feedback.getSubmittedAt()));
    }

    Map<Long, Employee> employees(Collection<MeetingAttendee> attendees) {
        Set<Long> ids = attendees.stream().map(MeetingAttendee::getEmployeeId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : employeeRepository.findAllById(ids).stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
    }

    private MeetingDTOs.Meeting meeting(Meeting m, String organizerName, List<MeetingDTOs.Attendee> attendees, long feedbackCount,
                                        long attendeeCount, MeetingDTOs.Attendee mine, Long me) {
        boolean feedbackPending = mine != null && m.getStatus() == MeetingStatus.COMPLETED
                && mine.status() == AttendeeStatus.INVITED && mine.feedbackSubmittedAt() == null;
        boolean canManage = m.getOrganizerId().equals(me) && currentAccess.has(Permission.MEETING_SCHEDULE);
        return new MeetingDTOs.Meeting(m.getId(), m.getCode(), m.getMode(), m.getTitle(), m.getSubject(), m.getDescription(),
                m.getMeetingType(), m.getClientName(), m.getClientDescription(), m.getClientContact(), m.getOutreachBusinessId(),
                BusinessTime.of(m.getStartsAt()), BusinessTime.of(m.getEndsAt()), m.getJoinUrl(), m.getPlaceName(), m.getAddress(),
                m.getGoogleMapsUrl(), m.getStatus(), m.getCancelReason(), m.getOrganizerId(), organizerName, attendees,
                feedbackCount, attendeeCount, BusinessTime.of(m.getCompletedAt()), BusinessTime.of(m.getCreatedAt()), mine,
                canManage, feedbackPending);
    }

    private static MeetingDTOs.Attendee attendee(MeetingAttendee a, Map<Long, Employee> employees, Map<Long, MeetingAttendee> byId) {
        Employee employee = employees.get(a.getEmployeeId());
        MeetingAttendee replacement = a.getReplacedById() == null ? null : byId.get(a.getReplacedById());
        Employee replacedBy = replacement == null ? null : employees.get(replacement.getEmployeeId());
        return new MeetingDTOs.Attendee(a.getId(), a.getEmployeeId(), a.getUserId(),
                employee == null ? null : employee.getDisplayName(), employee == null ? null : employee.designationLabel(),
                a.getStatus(), a.isOrganizer(), BusinessTime.of(a.getInvitedAt()),
                a.isOrganizer() ? null : BusinessTime.of(a.getDeclineDeadline()), BusinessTime.of(a.getDeclinedAt()),
                a.getDeclineReason(), replacedBy == null ? null : replacedBy.getDisplayName(),
                BusinessTime.of(a.getFeedbackSubmittedAt()));
    }
}
