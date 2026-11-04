package com.tinqa.procurement.meeting;

import com.tinqa.procurement.common.response.ApiResponse;
import com.tinqa.procurement.common.response.ApiResponses;
import com.tinqa.procurement.security.access.Permission;
import com.tinqa.procurement.security.access.RequiresPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// Meetings: scheduled without approval, declined within 24 hours of the invite, completed by the organiser and then
// given feedback on by every attendee
@RestController
@RequestMapping("/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    // Organiser or attendee (checked by the service), or anyone who sees every meeting
    private static final String VIEW_ALL_OR_OWN = "hasAnyAuthority('" + Permission.AUTHORITY_PREFIX + "MEETING_VIEW_ALL', '"
            + Permission.AUTHORITY_PREFIX + "MEETING_VIEW_OWN')";

    private final MeetingService meetingService;

    @RequiresPermission(Permission.MEETING_VIEW_ALL)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<MeetingDTOs.Meeting>>> list(
            @RequestParam(required = false) MeetingStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(meetingService.list(status, from, to, search, pageable), "Meetings fetched successfully");
    }

    @RequiresPermission(Permission.MEETING_VIEW_OWN)
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<Page<MeetingDTOs.Meeting>>> mine(@RequestParam(required = false) MeetingStatus status,
                                                                       @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(meetingService.mine(status, pageable), "Your meetings fetched successfully");
    }

    @RequiresPermission(Permission.MEETING_VIEW_OWN)
    @GetMapping("/mine/pending-feedback-count")
    public ResponseEntity<ApiResponse<MeetingDTOs.Count>> pendingFeedbackCount() {
        return ApiResponses.ok(new MeetingDTOs.Count(meetingService.pendingFeedbackCount()), "Pending feedback counted");
    }

    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @GetMapping("/invitable-staff")
    public ResponseEntity<ApiResponse<Page<MeetingDTOs.StaffOption>>> invitableStaff(@RequestParam(required = false) String search,
                                                                                    @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponses.ok(meetingService.invitableStaff(search, pageable), "Staff fetched successfully");
    }

    // Small by design: one invite list over at most two days. Who is already in a meeting then (the caller included)
    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @GetMapping("/busy")
    public ResponseEntity<ApiResponse<List<MeetingDTOs.Busy>>> busy(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) List<Long> employeeIds,
            @RequestParam(required = false) Long excludeMeetingId) {
        return ApiResponses.ok(meetingService.busy(from, to, employeeIds, excludeMeetingId), "Busy times fetched successfully");
    }

    @PreAuthorize(VIEW_ALL_OR_OWN)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> get(@PathVariable Long id) {
        return ApiResponses.ok(meetingService.get(id), "Meeting fetched successfully");
    }

    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @PostMapping
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> schedule(@Valid @RequestBody MeetingDTOs.MeetingRequest request) {
        MeetingDTOs.Meeting meeting = meetingService.schedule(request);
        return ApiResponses.created(meeting, meeting.code() + " scheduled. Invitations are on their way.");
    }

    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> update(@PathVariable Long id,
                                                                   @Valid @RequestBody MeetingDTOs.MeetingRequest request) {
        MeetingDTOs.Meeting meeting = meetingService.update(id, request);
        return ApiResponses.ok(meeting, meeting.code() + " updated");
    }

    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @PostMapping("/{id}/attendees")
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> addAttendees(@PathVariable Long id,
                                                                         @Valid @RequestBody MeetingDTOs.AttendeesRequest request) {
        return ApiResponses.ok(meetingService.addAttendees(id, request), "Invitations sent");
    }

    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> cancel(@PathVariable Long id,
                                                                   @Valid @RequestBody MeetingDTOs.ReasonRequest request) {
        return ApiResponses.ok(meetingService.cancel(id, request), "Meeting cancelled. Attendees are being told.");
    }

    @RequiresPermission(Permission.MEETING_SCHEDULE)
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> complete(@PathVariable Long id) {
        return ApiResponses.ok(meetingService.complete(id), "Meeting completed. Attendees are asked for feedback.");
    }

    @RequiresPermission(Permission.MEETING_RESPOND_OWN)
    @PostMapping("/{id}/decline")
    public ResponseEntity<ApiResponse<MeetingDTOs.Meeting>> decline(@PathVariable Long id,
                                                                    @Valid @RequestBody MeetingDTOs.ReasonRequest request) {
        return ApiResponses.ok(meetingService.decline(id, request), "Invitation declined. The organiser has been told.");
    }

    @RequiresPermission(Permission.MEETING_RESPOND_OWN)
    @GetMapping("/{id}/feedback/mine")
    public ResponseEntity<ApiResponse<MeetingDTOs.Feedback>> myFeedback(@PathVariable Long id) {
        return ApiResponses.ok(meetingService.myFeedback(id), "Feedback fetched successfully");
    }

    @RequiresPermission(Permission.MEETING_RESPOND_OWN)
    @PostMapping("/{id}/feedback")
    public ResponseEntity<ApiResponse<MeetingDTOs.Feedback>> submitFeedback(@PathVariable Long id,
                                                                            @Valid @RequestBody MeetingDTOs.FeedbackRequest request) {
        return ApiResponses.created(meetingService.submitFeedback(id, request), "Thank you, your feedback is saved");
    }

    // Small by design: one entry per attendee
    @PreAuthorize(VIEW_ALL_OR_OWN)
    @GetMapping("/{id}/feedback")
    public ResponseEntity<ApiResponse<List<MeetingDTOs.Feedback>>> feedback(@PathVariable Long id) {
        return ApiResponses.ok(meetingService.feedback(id), "Feedback fetched successfully");
    }
}
