package com.tinqa.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinqa.procurement.mail.MeetingEmail;
import com.tinqa.procurement.meeting.MeetingMailer;
import com.tinqa.procurement.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * Meetings: scheduled without approval, invitees decline only within 24 hours and are replaced by the organiser,
 * changes reach attendees by in-app notification and by email sent after commit, and every attendee gives feedback
 * once after the organiser completes the meeting.
 */
class MeetingIntegrationTest extends IntegrationTestSupport {

    private static final String MEETINGS = "/v1/meetings";
    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    @Autowired
    private MeetingMailer meetingMailer;

    @BeforeEach
    void forgetEarlierEmails() {
        clearInvocations(staffMailer);
    }

    private Actor staff(String designation) throws Exception {
        String username = uniq("mt");
        long userId = insertUser(username, "ADMIN_L1", "WEB", false, true);
        long employeeId = insertEmployee(userId, designation);
        return new Actor(userId, employeeId, username, username + "@tinqa.test", login(username, SEED_PASSWORD));
    }

    private static Map<String, Object> online(OffsetDateTime start, List<Long> attendees) {
        return body("mode", "ONLINE", "title", "Pricing review", "subject", "Hotel rollout", "description", "Go through the quote",
                "meetingType", "CLIENT", "clientName", "Radisson Blu", "clientContact", "Asha Rao",
                "startsAt", start.toString(), "endsAt", start.plusHours(1).toString(),
                "joinUrl", "https://meet.google.com/abc-defg-hij", "attendeeEmployeeIds", attendees);
    }

    /** {@code days} from today, at hh:mm India time. */
    private static OffsetDateTime at(int days, int hour, int minute) {
        return OffsetDateTime.now(IST).plusDays(days).withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    private static Map<String, Object> online(OffsetDateTime start, OffsetDateTime end, List<Long> attendees) {
        Map<String, Object> request = online(start, attendees);
        request.put("endsAt", end.toString());
        return request;
    }

    /** Sends the queued emails and returns those of this meeting. */
    private List<MeetingEmail> sentEmails(long meetingId) {
        clearInvocations(staffMailer);
        meetingMailer.sendEmails();
        ArgumentCaptor<MeetingEmail> captor = ArgumentCaptor.forClass(MeetingEmail.class);
        try {
            verify(staffMailer, atLeast(1)).sendMeetingEmail(captor.capture());
        } catch (AssertionError none) {
            return List.of();
        }
        return captor.getAllValues().stream().filter(email -> email.meetingId() == meetingId).toList();
    }

    private long attendeeId(JsonNode meeting, long employeeId) {
        for (JsonNode attendee : meeting.get("attendees")) {
            if (attendee.get("employeeId").asLong() == employeeId && !attendee.get("status").asText().equals("REPLACED")) {
                return attendee.get("id").asLong();
            }
        }
        throw new AssertionError("No attendee for employee " + employeeId);
    }

    @Test
    void aMeetingFromInviteToFeedback() throws Exception {
        Actor organizer = staff("STORE_MANAGER");
        Actor first = staff("DELIVERY_RIDER");
        Actor second = staff("DELIVERY_RIDER");
        Actor substitute = staff("SALES_AGENT");
        Actor outsider = staff("DELIVERY_RIDER");
        OffsetDateTime start = at(3, 10, 0);

        // Checked on the server: the link, the times, the invitees
        Map<String, Object> noLink = online(start, List.of(first.employeeId()));
        noLink.remove("joinUrl");
        post(MEETINGS, organizer.token(), noLink, 400);
        Map<String, Object> plainHttp = online(start, List.of(first.employeeId()));
        plainHttp.put("joinUrl", "http://meet.example.com/x");
        post(MEETINGS, organizer.token(), plainHttp, 400);
        post(MEETINGS, organizer.token(), online(OffsetDateTime.now(IST).minusHours(1), List.of(first.employeeId())), 400);
        Map<String, Object> tooLong = online(start, List.of(first.employeeId()));
        tooLong.put("endsAt", start.plusHours(13).toString());
        post(MEETINGS, organizer.token(), tooLong, 400);
        post(MEETINGS, organizer.token(), online(start, List.of(organizer.employeeId())), 400);
        post(MEETINGS, first.token(), online(start, List.of(second.employeeId())), 403);

        JsonNode meeting = data(post(MEETINGS, organizer.token(), online(start, List.of(first.employeeId(), second.employeeId())), 201));
        long id = meeting.get("id").asLong();
        assertThat(meeting.get("code").asText()).matches("MTG-\\d{4}-\\d{4,}");
        assertThat(meeting.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(meeting.get("attendees")).hasSize(3);
        assertThat(meeting.get("canManage").asBoolean()).isTrue();
        assertThat(meeting.get("startsAt").asText()).endsWith("+05:30");
        assertThat(notificationTitles(first)).contains("Meeting invite");

        // Invites are emailed after commit, once, with the calendar details
        List<MeetingEmail> invites = sentEmails(id);
        assertThat(invites).hasSize(3).allMatch(email -> email.kind().equals("INVITE"));
        assertThat(invites).anyMatch(email -> email.declineBy() != null && email.joinUrl().startsWith("https://"));
        assertThat(sentEmails(id)).isEmpty();

        // Attendees see their own meetings; others don't
        JsonNode mine = data(get(MEETINGS + "/mine", first.token(), 200));
        assertThat(mine.get("content").findValuesAsText("id")).contains(String.valueOf(id));
        get(MEETINGS + "/" + id, first.token(), 200);
        get(MEETINGS + "/" + id, outsider.token(), 403);
        get(MEETINGS, first.token(), 403);
        assertThat(data(get(MEETINGS + "?status=SCHEDULED&search=pricing&size=100", organizer.token(), 200)).get("content")
                .findValuesAsText("id")).contains(String.valueOf(id));
        assertThat(data(get(MEETINGS + "/invitable-staff?search=seed", organizer.token(), 200)).get("totalElements").asInt()).isPositive();

        // Declining: with a reason, within 24 hours of the invite; the organiser can't
        post(MEETINGS + "/" + id + "/decline", first.token(), body("reason", ""), 400);
        JsonNode declined = data(post(MEETINGS + "/" + id + "/decline", first.token(), body("reason", "On leave that day"), 200));
        long firstAttendee = attendeeId(declined, first.employeeId());
        assertThat(notificationTitles(organizer)).contains("Meeting invite declined");
        post(MEETINGS + "/" + id + "/decline", organizer.token(), body("reason", "No"), 409);
        jdbc.update("UPDATE meeting_attendee SET decline_deadline = now() - interval '1 minute' WHERE meeting_id = ? AND employee_id = ?",
                id, second.employeeId());
        assertThat(post(MEETINGS + "/" + id + "/decline", second.token(), body("reason", "Busy"), 409).get("errorCode").asText())
                .isEqualTo("DECLINE_WINDOW_CLOSED");

        // The organiser invites someone in their place, who gets their own window
        post(MEETINGS + "/" + id + "/attendees", first.token(), body("employeeIds", List.of(substitute.employeeId()),
                "replacesAttendeeId", firstAttendee), 403);
        JsonNode replaced = data(post(MEETINGS + "/" + id + "/attendees", organizer.token(), body("employeeIds",
                List.of(substitute.employeeId()), "replacesAttendeeId", firstAttendee), 200));
        assertThat(replaced.get("attendees").findValuesAsText("status")).contains("REPLACED");
        assertThat(post(MEETINGS + "/" + id + "/attendees", organizer.token(), body("employeeIds", List.of(second.employeeId())), 409)
                .get("errorCode").asText()).isEqualTo("MEETING_ALREADY_INVITED");
        get(MEETINGS + "/" + id, first.token(), 403);
        assertThat(sentEmails(id)).extracting(MeetingEmail::kind).containsExactly("INVITE");

        // A new time emails everyone still attending again
        Map<String, Object> moved = online(start.plusDays(1), List.of());
        put(MEETINGS + "/" + id, second.token(), moved, 403);
        JsonNode updated = data(put(MEETINGS + "/" + id, organizer.token(), moved, 200));
        assertThat(OffsetDateTime.parse(updated.get("startsAt").asText()).toInstant()).isEqualTo(start.plusDays(1).toInstant());
        assertThat(sentEmails(id)).hasSize(3).allMatch(email -> email.kind().equals("UPDATED") && email.sequence() == 1);
        assertThat(notificationTitles(second)).contains("Meeting updated");

        // 24 hours before: an in-app reminder and an email, once
        jdbc.update("UPDATE meeting_attendee SET reminder_due_at = now() - interval '1 minute' WHERE meeting_id = ? AND status = 'INVITED'", id);
        meetingMailer.sendReminders();
        assertThat(notificationTitles(substitute)).containsOnlyOnce("Meeting tomorrow");
        meetingMailer.sendReminders();
        assertThat(notificationTitles(substitute)).containsOnlyOnce("Meeting tomorrow");
        assertThat(sentEmails(id)).hasSize(3).allMatch(email -> email.kind().equals("REMINDER"));

        // Completed once it started; then every attendee is asked for feedback
        assertThat(post(MEETINGS + "/" + id + "/complete", organizer.token(), null, 409).get("errorCode").asText())
                .isEqualTo("MEETING_WRONG_STATUS");
        jdbc.update("UPDATE meeting SET starts_at = now() - interval '1 hour', ends_at = now() WHERE id = ?", id);
        post(MEETINGS + "/" + id + "/feedback", second.token(), body("agenda", "a", "feedback", "b", "decisions", "c"), 409);
        JsonNode completed = data(post(MEETINGS + "/" + id + "/complete", organizer.token(), null, 200));
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(notificationTitles(second)).contains("Feedback requested");
        assertThat(sentEmails(id)).hasSize(3).allMatch(email -> email.kind().equals("FEEDBACK_REQUEST"));
        assertThat(data(get(MEETINGS + "/mine/pending-feedback-count", second.token(), 200)).get("count").asLong()).isEqualTo(1);
        assertThat(data(get("/v1/notifications/unread-count", second.token(), 200)).get("meetingFeedbackPending").asLong()).isEqualTo(1);
        assertThat(data(get(MEETINGS + "/mine?status=COMPLETED", second.token(), 200)).get("content").get(0)
                .get("feedbackPending").asBoolean()).isTrue();

        // Feedback: once per attendee, final; read by the organiser
        get(MEETINGS + "/" + id + "/feedback/mine", second.token(), 404);
        post(MEETINGS + "/" + id + "/feedback", second.token(), body("agenda", "", "feedback", "Good", "decisions", "Pilot"), 400);
        JsonNode feedback = data(post(MEETINGS + "/" + id + "/feedback", second.token(), body("agenda", "Pricing", "feedback",
                "They liked it", "decisions", "Pilot in two rooms", "dealConfirmed", true, "dealNotes", "10 rooms", "rating", "GOOD",
                "meetingType", "CLIENT"), 201));
        assertThat(feedback.get("dealConfirmed").asBoolean()).isTrue();
        assertThat(post(MEETINGS + "/" + id + "/feedback", second.token(), body("agenda", "a", "feedback", "b", "decisions", "c"), 409)
                .get("errorCode").asText()).isEqualTo("FEEDBACK_ALREADY_SUBMITTED");
        post(MEETINGS + "/" + id + "/feedback", outsider.token(), body("agenda", "a", "feedback", "b", "decisions", "c"), 403);
        assertThat(data(get(MEETINGS + "/" + id + "/feedback/mine", second.token(), 200)).get("decisions").asText())
                .isEqualTo("Pilot in two rooms");
        assertThat(data(get(MEETINGS + "/" + id + "/feedback", organizer.token(), 200))).hasSize(1);
        get(MEETINGS + "/" + id + "/feedback", substitute.token(), 403);
        assertThat(data(get(MEETINGS + "/mine/pending-feedback-count", second.token(), 200)).get("count").asLong()).isZero();
        assertThat(data(get(MEETINGS + "/" + id, organizer.token(), 200)).get("feedbackCount").asLong()).isEqualTo(1);

        post(MEETINGS + "/" + id + "/cancel", organizer.token(), body("reason", "Too late"), 409);
    }

    @Test
    void aCancelledMeetingTellsItsAttendees() throws Exception {
        Actor organizer = staff("FINANCE_MANAGER");
        Actor invitee = staff("DELIVERY_RIDER");
        // Less than 24 hours away, and over by the end of its day (meetings take one day)
        OffsetDateTime start = OffsetDateTime.now(IST).plusHours(5).withSecond(0).withNano(0);
        if (!start.plusHours(2).toLocalDate().equals(start.toLocalDate())) {
            start = start.toLocalDate().plusDays(1).atTime(0, 30).atOffset(IST);
        }
        Map<String, Object> request = body("mode", "IN_PERSON", "title", "Site visit", "meetingType", "BUSINESS_TO_SUPPLIER",
                "startsAt", start.toString(), "endsAt", start.plusHours(2).toString(), "placeName", "Supplier office",
                "attendeeEmployeeIds", List.of(invitee.employeeId()));
        post(MEETINGS, organizer.token(), request, 400);
        request.put("address", "12 MG Road, Jaipur");
        request.put("googleMapsUrl", "https://maps.app.goo.gl/abc123");
        JsonNode meeting = data(post(MEETINGS, organizer.token(), request, 201));
        long id = meeting.get("id").asLong();
        // Less than 24 hours away: the invite itself is the reminder, so none is scheduled
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting_attendee WHERE meeting_id = ? AND reminder_due_at IS NOT NULL",
                Long.class, id)).isZero();
        assertThat(sentEmails(id)).hasSize(2);

        post(MEETINGS + "/" + id + "/cancel", invitee.token(), body("reason", "No"), 403);
        post(MEETINGS + "/" + id + "/cancel", organizer.token(), body("reason", ""), 400);
        JsonNode cancelled = data(post(MEETINGS + "/" + id + "/cancel", organizer.token(), body("reason", "Supplier travelling"), 200));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelReason").asText()).isEqualTo("Supplier travelling");
        assertThat(notificationTitles(invitee)).contains("Meeting cancelled");
        assertThat(sentEmails(id)).hasSize(2).allMatch(email -> email.kind().equals("CANCELLED")
                && "Supplier travelling".equals(email.cancelReason()));
        post(MEETINGS + "/" + id + "/decline", invitee.token(), body("reason", "x"), 409);
        put(MEETINGS + "/" + id, organizer.token(), request, 409);
    }

    @Test
    void nobodyIsInTwoMeetingsAtOnce() throws Exception {
        Actor organizer = staff("STORE_MANAGER");
        Actor other = staff("FINANCE_MANAGER");
        Actor busy = staff("DELIVERY_RIDER");
        Actor free = staff("DELIVERY_RIDER");
        Actor decliner = staff("SALES_AGENT");

        // One day only, in the organiser's own time zone
        post(MEETINGS, organizer.token(), online(at(4, 23, 0), at(5, 0, 30), List.of(busy.employeeId())), 400);

        long first = data(post(MEETINGS, organizer.token(), online(at(4, 10, 0), at(4, 11, 0), List.of(busy.employeeId())), 201))
                .get("id").asLong();
        String firstCode = data(get(MEETINGS + "/" + first, organizer.token(), 200)).get("code").asText();

        // The clock: who is busy that day, the caller included
        String day = "from=" + enc(at(4, 0, 0)) + "&to=" + enc(at(5, 0, 0));
        JsonNode taken = data(get(MEETINGS + "/busy?" + day + "&employeeIds=" + busy.employeeId() + "&employeeIds=" + free.employeeId(),
                organizer.token(), 200));
        assertThat(taken).hasSize(2);
        assertThat(taken.findValuesAsText("code")).containsOnly(firstCode);
        assertThat(taken.findValuesAsText("self")).containsExactlyInAnyOrder("true", "false");
        assertThat(data(get(MEETINGS + "/busy?" + day + "&employeeIds=" + busy.employeeId() + "&excludeMeetingId=" + first,
                organizer.token(), 200))).isEmpty();
        get(MEETINGS + "/busy?" + day, busy.token(), 403);
        get(MEETINGS + "/busy?from=" + enc(at(4, 0, 0)) + "&to=" + enc(at(7, 0, 0)), organizer.token(), 400);

        // Overlapping: refused, naming the person, the meeting and its time
        JsonNode clash = post(MEETINGS, other.token(), online(at(4, 10, 30), at(4, 11, 30), List.of(busy.employeeId(), free.employeeId())), 409);
        assertThat(clash.get("errorCode").asText()).isEqualTo("MEETING_TIME_CLASH");
        assertThat(clash.get("message").asText()).contains(firstCode).doesNotContain(free.username());
        // Back to back is fine
        long second = data(post(MEETINGS, other.token(), online(at(4, 11, 0), at(4, 12, 0), List.of(busy.employeeId(), decliner.employeeId())), 201))
                .get("id").asLong();

        // Moving a meeting onto someone's other meeting is refused; elsewhere is fine
        put(MEETINGS + "/" + first, organizer.token(), online(at(4, 10, 30), at(4, 11, 30), List.of()), 409);
        put(MEETINGS + "/" + first, organizer.token(), online(at(4, 9, 0), at(4, 10, 0), List.of()), 200);

        // Inviting someone busy then is refused; once they declined the other meeting, they are free
        put(MEETINGS + "/" + first, organizer.token(), online(at(4, 11, 30), at(4, 12, 30), List.of()), 409);
        put(MEETINGS + "/" + first, organizer.token(), online(at(4, 12, 0), at(4, 13, 0), List.of()), 200);
        JsonNode third = data(post(MEETINGS, organizer.token(), online(at(4, 11, 15), at(4, 11, 45), List.of(free.employeeId())), 201));
        assertThat(post(MEETINGS + "/" + third.get("id").asLong() + "/attendees", organizer.token(),
                body("employeeIds", List.of(decliner.employeeId())), 409).get("errorCode").asText()).isEqualTo("MEETING_TIME_CLASH");
        post(MEETINGS + "/" + second + "/decline", decliner.token(), body("reason", "On leave"), 200);
        post(MEETINGS + "/" + third.get("id").asLong() + "/attendees", organizer.token(), body("employeeIds", List.of(decliner.employeeId())), 200);
    }

    // In UTC ("…Z"): the request builder treats the path as a URI template, so a "+05:30" offset would not survive it
    private static String enc(OffsetDateTime time) {
        return time.toInstant().toString();
    }
}
