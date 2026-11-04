package com.tinqa.procurement.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeetingFeedbackRepository extends JpaRepository<MeetingFeedback, Long> {

    Optional<MeetingFeedback> findByAttendeeId(Long attendeeId);

    // One per attendee, so a handful per meeting
    List<MeetingFeedback> findByMeetingIdOrderByIdAsc(Long meetingId);
}
